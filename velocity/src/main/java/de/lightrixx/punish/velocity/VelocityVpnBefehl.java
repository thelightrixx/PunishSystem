package de.lightrixx.punish.velocity;

import de.lightrixx.punish.command.PunishCommand;
import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.Permissions;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.storage.PlayerCache;
import de.lightrixx.punish.util.ZeitFormat;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

final class VelocityVpnBefehl extends PunishCommand {

    private final VpnBlocklistenDienst dienst;

    VelocityVpnBefehl(MessageService messageService, VelocityKonfiguration config, ProtectionService protection,
                      PlayerCache cache, AuftragsPlaner planer, VpnBlocklistenDienst dienst) {
        super(messageService, config, protection, cache, planer);
        this.dienst = Objects.requireNonNull(dienst, "Der VPN-Dienst darf nicht null sein.");
    }

    @Override
    protected String recht() {
        return Permissions.PUNISH_VPN.node();
    }

    @Override
    public boolean unterstuetztScope() {
        return false;
    }

    @Override
    public void ausfuehren(BefehlsSender sender, String[] args) {
        if (args.length == 0 || args.length == 1 && "status".equalsIgnoreCase(args[0])) {
            status(sender);
        } else if (args.length == 2 && "check".equalsIgnoreCase(args[0])) {
            pruefe(sender, args[1]);
        } else {
            messageService.senden(sender, MessageKeys.VPN_SYNTAX);
        }
    }

    private void pruefe(BefehlsSender sender, String ip) {
        VpnBlocklistenDienst.Ergebnis ergebnis;
        try {
            ergebnis = dienst.pruefe(ip);
        } catch (IllegalArgumentException e) {
            messageService.senden(sender, MessageKeys.VPN_IP_UNGUELTIG);
            return;
        }
        String entscheidung = switch (ergebnis.aktion()) {
            case DEAKTIVIERT -> "VPN-Schutz deaktiviert";
            case OHNE_LISTEN -> "vorübergehend erlaubt, keine gültigen Listen";
            case FREI -> "kein Listentreffer";
            case FREIGEGEBEN -> "durch VPN-Freigabe erlaubt";
            case MELDEN -> "Routing-Treffer, nur Hinweis";
            case SPERREN -> "Login wird durch VPN-Schutz abgelehnt";
        };
        messageService.senden(sender, MessageKeys.VPN_CHECK, Map.of("ip", ip, "entscheidung", entscheidung,
                "freigabe", ergebnis.freigabe().orElse("keine")));
        for (var treffer : ergebnis.treffer()) {
            messageService.senden(sender, MessageKeys.VPN_TREFFER, Map.of("quelle", treffer.quelle().name(),
                    "url", treffer.quelle().url().toASCIIString(), "art", treffer.quelle().art().name(),
                    "cidr", treffer.cidr()));
        }
    }

    private void status(BefehlsSender sender) {
        var status = dienst.status();
        messageService.senden(sender, MessageKeys.VPN_STATUS, Map.of("aktiv", status.aktiv() ? "an" : "aus",
                "modus", status.modus().name().toLowerCase(Locale.ROOT), "bereit", String.valueOf(status.bereit()),
                "gesamt", String.valueOf(status.quellen().size()), "naechster-download", zeit(status.naechsterDownload())));
        for (var quelle : status.quellen()) {
            messageService.senden(sender, MessageKeys.VPN_QUELLE, Map.of("quelle", quelle.quelle().name(),
                    "url", quelle.quelle().url().toASCIIString(), "art", quelle.quelle().art().name(),
                    "eintraege", String.valueOf(quelle.eintraege()), "download", zeit(quelle.letzterDownload()),
                    "aenderung", zeit(quelle.letzteAenderung())));
            if (!quelle.fehler().isEmpty()) {
                messageService.senden(sender, MessageKeys.VPN_QUELLENFEHLER, Map.of("quelle", quelle.quelle().name(),
                        "zeit", zeit(quelle.fehlerSeit()), "fehler", quelle.fehler()));
            }
        }
        messageService.senden(sender, MessageKeys.VPN_QUELLENHINWEIS);
    }

    private static String zeit(long millis) {
        return millis <= 0L ? "unbekannt/noch nicht erfolgt" : ZeitFormat.formatZeitpunkt(millis);
    }

    @Override
    public List<String> vervollstaendigen(BefehlsSender sender, String[] args) {
        if (!hatRecht(sender, args) || args.length > 1) {
            return List.of();
        }
        String anfang = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        return List.of("check", "status").stream().filter(wert -> wert.startsWith(anfang)).toList();
    }
}
