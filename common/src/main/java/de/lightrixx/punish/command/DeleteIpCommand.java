package de.lightrixx.punish.command;

import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.Permissions;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.CachedPlayer;
import de.lightrixx.punish.model.IpBanRecord;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.storage.IpBanRepository;
import de.lightrixx.punish.storage.IpMuteRepository;
import de.lightrixx.punish.storage.IpTimeoutRepository;
import de.lightrixx.punish.storage.PlayerCache;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class DeleteIpCommand extends PunishCommand {

    private final IpBanRepository ipBanRepository;
    private final IpMuteRepository ipMuteRepository;
    private final IpTimeoutRepository ipTimeoutRepository;
    private volatile Runnable nachLoeschung;
    private volatile de.lightrixx.punish.storage.HistoryRepository historyRepository;

    public void mitHistoryRepository(de.lightrixx.punish.storage.HistoryRepository historyRepository) {
        this.historyRepository = historyRepository;
    }

    public void setNachLoeschung(Runnable nachLoeschung) {
        this.nachLoeschung = nachLoeschung;
    }

    public DeleteIpCommand(MessageService messageService,
                           Konfiguration configManager,
                           ProtectionService protectionService,
                           PlayerCache playerCache,
                           IpBanRepository ipBanRepository,
                           IpMuteRepository ipMuteRepository,
                           IpTimeoutRepository ipTimeoutRepository,
                           SpielerVerzeichnis verzeichnis) {
        super(messageService, configManager, protectionService, playerCache, null,
                Objects.requireNonNull(verzeichnis, "Das Spielerverzeichnis darf nicht null sein."));
        this.ipBanRepository = Objects.requireNonNull(ipBanRepository, "Das IpBanRepository darf nicht null sein.");
        this.ipMuteRepository = Objects.requireNonNull(ipMuteRepository, "Das IpMuteRepository darf nicht null sein.");
        this.ipTimeoutRepository = Objects.requireNonNull(ipTimeoutRepository, "Das IpTimeoutRepository darf nicht null sein.");
    }

    @Override
    protected String recht() {
        return Permissions.PUNISH_DELETEIP.node();
    }

    @Override
    public boolean unterstuetztScope() {
        return false;
    }

    @Override
    public void ausfuehren(BefehlsSender sender, String[] args) {
        // -s wird nur ausgewertet (Rechte-Prüfung + Entfernen aus den Argumenten):
        // Delete-IP sendet keine Rundmeldung an andere Moderatoren, es gibt also
        // nichts zu unterdrücken – die Rückmeldung an den Ausführenden bleibt.
        ScopeParser.SilentErgebnis silentErgebnis = silentAusArgsOhneScope(sender, args);
        args = silentErgebnis.bereinigteArgs();
        if (args.length < 1 || args[0] == null || args[0].isBlank()) {
            messageService.senden(sender, MessageKeys.DELETE_IP_SYNTAX);
            return;
        }
        String eingabe = args[0].trim();
        if (eingabe.contains("/")) {
            messageService.senden(sender, MessageKeys.IP_UNGUELTIG, Map.of("ip", eingabe));
            return;
        }
        String kanonisch;
        try {
            kanonisch = IpBanRecord.kanonisieren(eingabe);
        } catch (IllegalArgumentException direktFehler) {
            Optional<CachedPlayer> ziel = zielAufloesen(eingabe);
            if (ziel.isEmpty()) {
                messageService.senden(sender, MessageKeys.SPIELER_NICHT_GEFUNDEN, Map.of("spieler", eingabe));
                return;
            }
            if (istAbfrageZielGeschuetzt(sender, ziel.get().uuid())) {
                return;
            }
            String letzteIp = ziel.get().letzteIp();
            if (letzteIp == null || letzteIp.isBlank() || letzteIp.equalsIgnoreCase("unbekannt")) {
                messageService.senden(sender, MessageKeys.IP_NICHT_GEFUNDEN,
                        Map.of("spieler", ziel.get().name()));
                return;
            }
            try {
                kanonisch = IpBanRecord.kanonisieren(letzteIp);
            } catch (IllegalArgumentException e) {
                messageService.senden(sender, MessageKeys.IP_NICHT_GEFUNDEN,
                        Map.of("spieler", ziel.get().name()));
                return;
            }
        }
        if (istIpGeschuetzt(sender, kanonisch)) {
            return;
        }
        if (!istBestaetigt(args)) {
            sendeLoeschBestaetigung(sender, kanonisch);
            return;
        }
        List<String> fehler = new ArrayList<>();
        int banns = 0;
        int mutes = 0;
        int timeouts = 0;
        try {
            for (var eintrag : new ArrayList<>(ipBanRepository.alleFuer(kanonisch))) {
                if (eintrag == null) {
                    continue;
                }
                if (ipBanRepository.entbannen(kanonisch, eintrag.scope()).isPresent()) {
                    banns++;
                }
            }
        } catch (Exception e) {
            fehler.add("Banns");
            LOG.warn("Delete-IP: IP-Bann-Löschung fehlgeschlagen.", e);
        }
        try {
            for (var eintrag : new ArrayList<>(ipMuteRepository.alleFuer(kanonisch))) {
                if (eintrag == null) {
                    continue;
                }
                if (ipMuteRepository.entmuten(kanonisch, eintrag.scope()).isPresent()) {
                    mutes++;
                }
            }
        } catch (Exception e) {
            fehler.add("Mutes");
            LOG.warn("Delete-IP: IP-Mute-Löschung fehlgeschlagen.", e);
        }
        try {
            for (var eintrag : new ArrayList<>(ipTimeoutRepository.alleFuer(kanonisch))) {
                if (eintrag == null) {
                    continue;
                }
                if (ipTimeoutRepository.timeoutAufheben(kanonisch, eintrag.scope()).isPresent()) {
                    timeouts++;
                }
            }
        } catch (Exception e) {
            fehler.add("Timeouts");
            LOG.warn("Delete-IP: IP-Timeout-Löschung fehlgeschlagen.", e);
        }
        int anonymisiert = 0;
        try {
            for (CachedPlayer kandidat : playerCache.findeNachIp(kanonisch)) {
                if (kandidat == null) {
                    continue;
                }
                try {
                    playerCache.aktualisieren(new CachedPlayer(kandidat.uuid(), kandidat.name(),
                            "unbekannt", System.currentTimeMillis()));
                    anonymisiert++;
                } catch (Exception e) {
                    LOG.debug("Delete-IP: Cache-Anonymisierung für {} fehlgeschlagen.", kandidat.uuid(), e);
                }
            }
        } catch (Exception e) {
            fehler.add("Cache");
            LOG.warn("Delete-IP: Cache-Suche fehlgeschlagen.", e);
        }
        try {
            playerCache.loescheIpHistorie(kanonisch);
        } catch (Exception e) {
            fehler.add("IP-Historie");
            LOG.warn("Delete-IP: IP-Historie-Löschung fehlgeschlagen.", e);
        }
        LOG.info("IP-Löschung durchgeführt (Banns={}, Mutes={}, Timeouts={}, Cache={}).",
                banns, mutes, timeouts, anonymisiert);
        int history = 0;
        try {
            de.lightrixx.punish.storage.HistoryRepository repo = this.historyRepository;
            if (repo != null) {
                history = repo.loescheIpEintraege(kanonisch);
            }
        } catch (Exception e) {
            fehler.add("History");
            LOG.warn("Delete-IP: History-Löschung fehlgeschlagen.", e);
        }
        LOG.info("IP-Löschung History-Einträge: {}.", history);
        if (banns + mutes + timeouts > 0) {
            Runnable push = nachLoeschung;
            if (push != null) {
                try {
                    push.run();
                } catch (Exception e) {
                    LOG.warn("Bridge-Sync nach IP-Löschung fehlgeschlagen (Caches evtl. veraltet).", e);
                }
            }
        }
        if (!fehler.isEmpty()) {
            LOG.warn("IP-Löschung unvollständig für {}: {} (Banns={}, Mutes={}, Timeouts={}, Cache={}, "
                            + "History={}).",
                    kanonisch, fehler, banns, mutes, timeouts, anonymisiert, history);
            messageService.senden(sender, MessageKeys.AKTION_FEHLGESCHLAGEN);
            return;
        }
        messageService.senden(sender, MessageKeys.DELETE_IP_ERFOLG, Map.of(
                "ip", kanonisch,
                "banns", String.valueOf(banns),
                "mutes", String.valueOf(mutes),
                "timeouts", String.valueOf(timeouts),
                "anzahl", String.valueOf(anonymisiert)));
    }

    private void sendeLoeschBestaetigung(BefehlsSender sender, String kanonisch) {
        String befehl = "/punish-delete-ip " + kanonisch + " " + BESTAETIGUNGS_ARGUMENT;
        try {
            Component hinweis = messageService.komponente(MessageKeys.BESTAETIGUNG_NOETIG, Map.of());
            Component umfang = Component.text("Löscht alle IP-Daten (Banns, Mutes, Timeouts, Cache, History) für "
                    + PunishStatusCommand.maskeIp(sender, kanonisch) + " unwiderruflich.", NamedTextColor.YELLOW);
            Component klickbar = Component.text(befehl, NamedTextColor.WHITE)
                    .clickEvent(ClickEvent.suggestCommand(befehl));
            messageService.sendeKomponente(sender, hinweis.append(Component.newline()).append(umfang)
                    .append(Component.newline()).append(klickbar));
        } catch (Exception e) {
            LOG.debug("IP-Lösch-Bestätigung konnte nicht gesendet werden.", e);
        }
    }

    private boolean istIpGeschuetzt(BefehlsSender sender, String kanonisch) {        try {
            for (CachedPlayer kandidat : playerCache.findeNachIp(kanonisch)) {
                if (kandidat != null && istAbfrageZielGeschuetzt(sender, kandidat.uuid())) {
                    return true;
                }
            }
        } catch (Exception e) {
            LOG.warn("Delete-IP: Schutzprüfung für {} fehlgeschlagen.", kanonisch, e);
            if (protectionService.istFailClosed()) {
                messageService.senden(sender, MessageKeys.GESCHUETZT);
                return true;
            }
        }
        return false;
    }

    private boolean istAbfrageZielGeschuetzt(BefehlsSender sender, UUID zielUuid) {
        try {
            if (istGeschuetztAusgeblendet(zielUuid)) {
                messageService.senden(sender, MessageKeys.GESCHUETZT);
                return true;
            }
        } catch (Exception e) {
            if (protectionService.istFailClosed()) {
                messageService.senden(sender, MessageKeys.GESCHUETZT);
                return true;
            }
        }
        return false;
    }

    private boolean istGeschuetztAusgeblendet(UUID zielUuid) {
        if (zielUuid == null) {
            return false;
        }
        try {
            return protectionService.istGeschuetzt(zielUuid, ProtectionService.IgnoreTyp.ALTS);
        } catch (Exception e) {
            return protectionService.istFailClosed();
        }
    }

    @Override
    public String tooltipFuer(String vorschlag, String[] args) {
        String kategorie = kategorieTooltip(vorschlag);
        return kategorie != null && !kategorie.isBlank() ? kategorie : "Spieler oder IP";
    }

    @Override
    public List<String> vervollstaendigen(BefehlsSender sender, String[] args) {
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0];
            List<String> vorschlaege = new ArrayList<>(bestrafbareSpielerNamen());
            vorschlaege.addAll(spielerNamenMitPrefix(prefix, 25));
            vorschlaege.addAll(bekannteIps(prefix, 25));
            return filtere(vorschlaege.stream().distinct().toList(), prefix);
        }
        return List.of();
    }

    private List<String> bekannteIps(String prefix, int limit) {
        String vergleich = prefix == null ? "" : prefix.trim().toLowerCase(java.util.Locale.ROOT);
        List<String> treffer = new ArrayList<>();
        try {
            long jetzt = System.currentTimeMillis();
            for (IpBanRecord record : ipBanRepository.alle()) {
                if (record == null || record.ip() == null || record.ip().isBlank()) {
                    continue;
                }
                if (!record.istAktiv(jetzt)) {
                    continue;
                }
                String ip = record.ip().trim();
                if (!vergleich.isEmpty() && !ip.toLowerCase(java.util.Locale.ROOT).startsWith(vergleich)) {
                    continue;
                }
                if (!treffer.contains(ip)) {
                    treffer.add(ip);
                }
                if (treffer.size() >= Math.max(1, limit)) {
                    break;
                }
            }
        } catch (Exception e) {
            LOG.debug("IP-Vorschläge für Delete-IP fehlgeschlagen.", e);
        }
        return treffer;
    }
}
