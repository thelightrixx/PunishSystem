package de.lightrixx.punish.command;

import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.Permissions;
import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.OnlineSpieler;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.service.KickService;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.storage.PlayerCache;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class KickAllCommand extends PunishCommand {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(KickAllCommand.class);
    private static final String BESTAETIGUNGS_ARGUMENT = "confirm";

    private final KickService kickService;

    public KickAllCommand(MessageService messageService,
                          Konfiguration configManager,
                          ProtectionService protectionService,
                          PlayerCache playerCache,
                          KickService kickService,
                          SpielerVerzeichnis verzeichnis) {
        super(messageService, configManager, protectionService, playerCache, null,
                Objects.requireNonNull(verzeichnis, "Das Spielerverzeichnis darf nicht null sein."));
        this.kickService = Objects.requireNonNull(kickService, "Der KickService darf nicht null sein.");
    }

    @Override
    protected String recht() {
        return Permissions.PUNISH_KICKALL.node();
    }

    @Override
    public boolean unterstuetztScope() {
        return false;
    }

    @Override
    public void ausfuehren(BefehlsSender sender, String[] args) {
        ScopeParser.SilentErgebnis silentErgebnis = silentAusArgsOhneScope(sender, args);
        args = silentErgebnis.bereinigteArgs();
        boolean silent = silentErgebnis.silent();
        if (blockiereBeiIoRueckstau(sender)) {
            return;
        }
        int grundStartIndex;
        if (configManager.bestaetigungKickAll()) {
            if (args.length < 1 || !BESTAETIGUNGS_ARGUMENT.equalsIgnoreCase(args[0])) {
                sendeBestaetigung(sender, args, silent);
                return;
            }
            grundStartIndex = 1;
        } else {
            grundStartIndex = 0;
        }

        String grund = grundAusArgumenten(args, grundStartIndex, MessageKeys.DEFAULT_KICK_GRUND);
        String ausfuehrer = ausfuehrerName(sender);
        UUID ausfuehrerUuid = eigeneUuid(sender).orElse(null);

        List<OnlineSpieler> gekickt = kickService.kickenAlle(grund, ausfuehrer, ausfuehrerUuid, silent);
        messageService.senden(sender, MessageKeys.BESTAETIGUNG_ERFOLGREICH,
                Map.of("anzahl", String.valueOf(gekickt.size())));
    }

    @Override
    public String tooltipFuer(String vorschlag, String[] args) {
        String kategorie = kategorieTooltip(vorschlag);
        return kategorie != null && !kategorie.isBlank() ? kategorie : "Grund";
    }

    @Override
    public List<String> vervollstaendigen(BefehlsSender sender, String[] args) {
        String letztes = args.length == 0 ? "" : args[args.length - 1];
        if (letztes != null && letztes.startsWith("-")) {
            return silentVorschlag(sender, letztes);
        }
        boolean bestaetigungAktiv = configManager.bestaetigungKickAll();
        if (bestaetigungAktiv) {
            if (args.length == 1) {
                return filtere(List.of(BESTAETIGUNGS_ARGUMENT), args[0]);
            }
            if (args.length == 2) {
                java.util.List<String> gemischt = new java.util.ArrayList<>(grundVorschlaege("kick"));
                gemischt.addAll(silentVorschlag(sender, ""));
                return filtere(gemischt, args[args.length - 1]);
            }
            if (args.length > 2) {
                return silentVorschlag(sender, args[args.length - 1]);
            }
        } else if (args.length == 1) {
            java.util.List<String> gemischt = new java.util.ArrayList<>(grundVorschlaege("kick"));
            gemischt.addAll(silentVorschlag(sender, ""));
            return filtere(gemischt, args[args.length - 1]);
        } else if (args.length > 1) {
            return silentVorschlag(sender, args[args.length - 1]);
        }
        return List.of();
    }

    private void sendeBestaetigung(BefehlsSender sender, String[] args, boolean silent) {
        String befehl = baueBestaetigungsBefehl(args, silent);
        int kandidaten = 0;
        try {
            UUID ausfuehrerUuid = eigeneUuid(sender).orElse(null);
            kandidaten = kickService.zaehleKickAlleKandidaten(ausfuehrerUuid);
        } catch (Exception e) {
            LOG.warn("KickAll-Kandidatenzählung fehlgeschlagen (zeige 0).", e);
        }
        Component hinweis = messageService.komponente(MessageKeys.BESTAETIGUNG_NOETIG, Map.of());
        Component umfang = Component.text(
                "Betroffen sind " + kandidaten + " Online-Spieler (ohne dich/geschützte).",
                NamedTextColor.YELLOW);
        Component klickbar = Component.text(befehl, NamedTextColor.WHITE)
                .clickEvent(ClickEvent.suggestCommand(befehl));
        messageService.sendeKomponente(sender, hinweis.append(Component.newline()).append(umfang)
                .append(Component.newline()).append(klickbar));
    }

    private String baueBestaetigungsBefehl(String[] args, boolean silent) {
        StringBuilder befehl = new StringBuilder("/kick-all ").append(BESTAETIGUNGS_ARGUMENT);

        int start = (args.length > 0 && BESTAETIGUNGS_ARGUMENT.equalsIgnoreCase(args[0])) ? 1 : 0;
        for (int i = start; i < args.length; i++) {
            befehl.append(" ").append(args[i]);
        }
        if (silent) {
            befehl.append(" -s");
        }
        return befehl.toString();
    }
}
