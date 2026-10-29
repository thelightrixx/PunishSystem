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

import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class KickCommand extends PunishCommand {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(KickCommand.class);

    private final KickService kickService;

    public KickCommand(MessageService messageService,
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
        return Permissions.PUNISH_KICK.node();
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
        if (args.length < 1) {
            messageService.senden(sender, MessageKeys.SPIELER_NICHT_ONLINE, Map.of("spieler", ""));
            return;
        }

        String roh = args[0] == null ? "" : args[0].trim();
        OnlineSpieler online = verzeichnis.onlineSpielerNachName(roh).orElse(null);
        if (online == null && !roh.isEmpty()) {
            for (OnlineSpieler kandidat : verzeichnis.onlineSpieler()) {
                if (kandidat != null && kandidat.name() != null
                        && kandidat.name().equalsIgnoreCase(roh)) {
                    online = kandidat;
                    break;
                }
            }
        }
        if (online == null) {
            messageService.senden(sender, MessageKeys.SPIELER_NICHT_ONLINE, Map.of("spieler", args[0]));
            return;
        }

        if (protectionService.istSelbstbestrafung(sender, online.uuid())) {
            messageService.senden(sender, MessageKeys.SELBSTBESTRAFUNG);
            return;
        }
        try {
            if (protectionService.istRangBlockiert(sender, online.uuid())) {
                messageService.senden(sender, MessageKeys.GESCHUETZT);
                return;
            }
        } catch (Exception e) {
            LOG.debug("Rang-Hierarchie-Check übersprungen.", e);
        }
        if (protectionService.istGeschuetzt(online, ProtectionService.IgnoreTyp.KICK)) {
            messageService.senden(sender, MessageKeys.GESCHUETZT);
            return;
        }

        String grund = grundAusArgumenten(args, 1, MessageKeys.DEFAULT_KICK_GRUND);
        String ausfuehrer = ausfuehrerName(sender);

        boolean erfolg = kickService.kickenSpieler(online.uuid(), online.name(), grund, ausfuehrer,
                silent);
        if (!erfolg) {
            messageService.senden(sender, MessageKeys.SPIELER_NICHT_ONLINE, Map.of("spieler", online.name()));
            return;
        }
        messageService.senden(sender, MessageKeys.KICK_ERFOLGREICH,
                Map.of("spieler", online.name(), "grund", grund));
    }

    @Override
    public String tooltipFuer(String vorschlag, String[] args) {
        String kategorie = kategorieTooltip(vorschlag);
        if (kategorie != null && !kategorie.isBlank()) {
            return kategorie;
        }
        int position = args == null ? 0 : args.length;
        if (position <= 1) {
            return "Spieler";
        }
        return "Grund";
    }

    @Override
    public List<String> vervollstaendigen(BefehlsSender sender, String[] args) {
        String letztes = args.length == 0 ? "" : args[args.length - 1];
        if (letztes != null && letztes.startsWith("-")) {
            return silentVorschlag(sender, letztes);
        }
        if (args.length == 1) {
            return spielerVorschlaegeSmart(sender, args[0]);
        }
        if (args.length == 2) {
            java.util.List<String> gemischt = new java.util.ArrayList<>(grundVorschlaege("kick"));
            gemischt.addAll(silentVorschlag(sender, ""));
            return filtere(gemischt, args[args.length - 1]);
        }
        return silentVorschlag(sender, args[args.length - 1]);
    }
}
