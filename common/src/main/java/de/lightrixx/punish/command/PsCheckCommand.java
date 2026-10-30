package de.lightrixx.punish.command;

import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.Permissions;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.BanRecord;
import de.lightrixx.punish.model.CachedPlayer;
import de.lightrixx.punish.model.MuteRecord;
import de.lightrixx.punish.model.TimeoutRecord;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.service.VerstossService;
import de.lightrixx.punish.service.WarnService;
import de.lightrixx.punish.storage.BanRepository;
import de.lightrixx.punish.storage.MuteRepository;
import de.lightrixx.punish.storage.PlayerCache;
import de.lightrixx.punish.storage.TimeoutRepository;
import de.lightrixx.punish.util.TabHinweis;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class PsCheckCommand extends PunishCommand {

    private final VerstossService verstossService;
    private final WarnService warnService;
    private final BanRepository banRepository;
    private final MuteRepository muteRepository;
    private final TimeoutRepository timeoutRepository;

    public PsCheckCommand(MessageService messageService,
                          Konfiguration configManager,
                          ProtectionService protectionService,
                          PlayerCache playerCache,
                          VerstossService verstossService,
                          WarnService warnService,
                          BanRepository banRepository,
                          MuteRepository muteRepository,
                          TimeoutRepository timeoutRepository,
                          SpielerVerzeichnis verzeichnis) {
        super(messageService, configManager, protectionService, playerCache, null,
                Objects.requireNonNull(verzeichnis, "Das Spielerverzeichnis darf nicht null sein."));
        this.verstossService = Objects.requireNonNull(verstossService, "Der VerstossService darf nicht null sein.");
        this.warnService = warnService;
        this.banRepository = Objects.requireNonNull(banRepository, "Das BanRepository darf nicht null sein.");
        this.muteRepository = Objects.requireNonNull(muteRepository, "Das MuteRepository darf nicht null sein.");
        this.timeoutRepository = Objects.requireNonNull(timeoutRepository, "Das TimeoutRepository darf nicht null sein.");
    }

    @Override
    protected String recht() {
        return Permissions.PUNISH_STATUS.node();
    }

    @Override
    public boolean unterstuetztScope() {
        return false;
    }

    @Override
    public void ausfuehren(BefehlsSender sender, String[] args) {
        if (args == null || args.length < 1 || args[0] == null || args[0].isBlank()) {
            messageService.senden(sender, MessageKeys.PSCHECK_SYNTAX);
            return;
        }
        String eingabe = args[0].trim();
        Optional<CachedPlayer> gefunden = zielAufloesen(eingabe);
        if (gefunden.isEmpty()) {
            messageService.senden(sender, MessageKeys.SPIELER_NICHT_GEFUNDEN,
                    Map.of("spieler", eingabe));
            return;
        }
        CachedPlayer ziel = gefunden.get();
        UUID zielUuid = ziel.uuid();
        if (istGeschuetztAusgeblendet(zielUuid)) {
            messageService.senden(sender, MessageKeys.GESCHUETZT);
            return;
        }
        long jetzt = System.currentTimeMillis();
        int banns = zaehle(banRepository.alleFuer(zielUuid),
                bann -> bann != null && bann.istAktiv(jetzt));
        int mutes = zaehle(muteRepository.alleFuer(zielUuid),
                mute -> mute != null && mute.istAktiv(jetzt));
        int timeouts = zaehle(timeoutRepository.alleFuer(zielUuid),
                timeout -> timeout != null && timeout.istAktiv(jetzt));
        int verstoesse = 0;
        try {
            verstoesse = verstossService.findeVerstoesse(zielUuid).size();
        } catch (Exception e) {
            LOG.debug("Verstöße für /pscheck konnten nicht geladen werden.", e);
        }
        int punkte = 0;
        try {
            punkte = verstossService.punkte(zielUuid);
        } catch (Exception e) {
            LOG.debug("Punkte für /pscheck konnten nicht geladen werden.", e);
        }
        int warns = 0;
        if (warnService != null) {
            try {
                warns = warnService.anzahl(zielUuid);
            } catch (Exception e) {
                LOG.debug("Warns für /pscheck konnten nicht geladen werden.", e);
            }
        }
        String ip = playerCache.finden(zielUuid)
                .map(CachedPlayer::letzteIp)
                .filter(wert -> wert != null && !wert.isBlank()
                        && !"unbekannt".equalsIgnoreCase(wert.trim()))
                .orElse("");
        String ipAnzeige = ip.isEmpty() ? "-" : PunishStatusCommand.maskeIp(sender, ip);
        int alts = zaehleAlts(zielUuid, ip);

        Map<String, String> status = Map.of(
                "banns", String.valueOf(banns),
                "mutes", String.valueOf(mutes),
                "timeouts", String.valueOf(timeouts),
                "verstoesse", String.valueOf(verstoesse),
                "punkte", String.valueOf(punkte),
                "max", String.valueOf(configManager.verstoesseMaxPunkte()),
                "warns", String.valueOf(warns),
                "alts", String.valueOf(alts));

        List<Component> zeilen = new ArrayList<>();
        zeilen.add(messageService.komponente(MessageKeys.PSCHECK_KOPF, Map.of(
                "ziel", ziel.name(),
                "ip", ipAnzeige)));
        zeilen.add(messageService.komponenteOhnePraefix(MessageKeys.PSCHECK_STATUS, status));
        zeilen.add(messageService.komponenteOhnePraefix(MessageKeys.PSCHECK_HINWEIS, Map.of()));

        Component aktionen = aktion("Status", "/punish-status " + ziel.name(),
                "Detaillierte Strafen anzeigen");
        aktionen = aktionen.append(Component.text("  ")).append(
                aktion("GUI", "/punish-gui " + ziel.name(), "PunishGUI öffnen"));
        aktionen = aktionen.append(Component.text("  ")).append(
                aktion("Dossier", "/punish-dossier " + ziel.name(), "Spieler-Dossier öffnen"));
        if (sender != null && sender.hatRecht(Permissions.PUNISH_ALTS.node())) {
            aktionen = aktionen.append(Component.text("  ")).append(
                    aktion("Alts", "/alts " + ziel.name(), "Alt-Accounts anzeigen"));
        }
        zeilen.add(aktionen);

        Component unban = unbanLink();
        if (unban != null) {
            zeilen.add(unban);
        }
        messageService.sendeBlock(sender, zeilen);
    }

    private int zaehleAlts(UUID zielUuid, String ip) {
        if (ip == null || ip.isBlank()) {
            return 0;
        }
        int anzahl = 0;
        try {
            for (CachedPlayer kandidat : playerCache.findeNachIpMitHistorie(ip)) {
                if (kandidat == null || zielUuid.equals(kandidat.uuid())) {
                    continue;
                }
                try {
                    if (protectionService.istGeschuetzt(kandidat.uuid(), ProtectionService.IgnoreTyp.ALTS)) {
                        continue;
                    }
                } catch (Exception e) {
                    continue;
                }
                anzahl++;
            }
        } catch (Exception e) {
            LOG.debug("Alt-Zählung für /pscheck fehlgeschlagen.", e);
        }
        return anzahl;
    }

    private boolean istGeschuetztAusgeblendet(UUID zielUuid) {
        try {
            return protectionService.istGeschuetzt(zielUuid);
        } catch (Exception e) {
            return protectionService.istFailClosed();
        }
    }

    private Component aktion(String label, String befehl, String hover) {
        Component text = messageService.komponenteOhnePraefix(MessageKeys.PSCHECK_LINK,
                Map.of("label", label));
        return text.clickEvent(ClickEvent.runCommand(befehl))
                .hoverEvent(HoverEvent.showText(Component.text(hover, NamedTextColor.GRAY)));
    }

    private Component unbanLink() {
        String link = configManager.bannUnbanAntragLink();
        if (link == null || link.isBlank()) {
            return null;
        }
        String ziel = link.trim();
        String geprueft = ziel.toLowerCase(Locale.ROOT);
        if (!geprueft.startsWith("http://") && !geprueft.startsWith("https://")) {
            LOG.warn("Ungültiger Unban-Antrag-Link '{}' (nur http/https erlaubt).", ziel);
            return null;
        }
        return messageService.komponenteOhnePraefix(MessageKeys.PSCHECK_UNBAN_LINK, Map.of())
                .clickEvent(ClickEvent.openUrl(ziel))
                .hoverEvent(HoverEvent.showText(Component.text("Unban-Antrag öffnen")));
    }

    private static <T> int zaehle(List<T> liste, java.util.function.Predicate<T> filter) {
        if (liste == null || liste.isEmpty()) {
            return 0;
        }
        int anzahl = 0;
        for (T eintrag : liste) {
            if (filter.test(eintrag)) {
                anzahl++;
            }
        }
        return anzahl;
    }

    @Override
    public List<String> vervollstaendigen(BefehlsSender sender, String[] args) {
        if (args == null || args.length != 1) {
            return List.of();
        }
        String aktuell = args[0] == null ? "" : args[0];
        List<String> namen = new ArrayList<>();
        try {
            for (CachedPlayer spieler : playerCache.alle()) {
                if (spieler != null && spieler.name() != null && !spieler.name().isBlank()) {
                    namen.add(spieler.name());
                }
            }
        } catch (Exception e) {
            LOG.debug("Spieler-Vorschläge für /pscheck fehlgeschlagen.", e);
        }
        return TabHinweis.filtere(namen, aktuell);
    }
}
