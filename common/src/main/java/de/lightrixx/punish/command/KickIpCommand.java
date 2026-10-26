package de.lightrixx.punish.command;

import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.OnlineSpieler;
import de.lightrixx.punish.core.Permissions;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.CachedPlayer;
import de.lightrixx.punish.model.IpBanRecord;
import de.lightrixx.punish.service.KickService;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.storage.PlayerCache;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class KickIpCommand extends PunishCommand {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(KickIpCommand.class);

    private final KickService kickService;

    public KickIpCommand(MessageService messageService,
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
        return Permissions.PUNISH_KICKIP.node();
    }

    @Override
    public boolean unterstuetztScope() {
        return false;
    }

    @Override
    public void ausfuehren(BefehlsSender sender, String[] args) {
        String[] rohArgs = args;
        ScopeParser.SilentErgebnis silentErgebnis = silentAusArgsOhneScope(sender, args);
        String[] roh = silentErgebnis.bereinigteArgs();
        boolean silent = silentErgebnis.silent();
        if (roh.length < 1) {
            messageService.senden(sender, MessageKeys.SPIELER_NICHT_ONLINE, Map.of("spieler", ""));
            return;
        }
        boolean bestaetigt = istBestaetigt(roh);
        String[] wirksam = ohneBestaetigung(roh);
        String eingabe = wirksam[0];
        if (eingabe != null && eingabe.contains("/")) {
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
            if (protectionService.istSelbstbestrafung(sender, ziel.get().uuid())) {
                messageService.senden(sender, MessageKeys.SELBSTBESTRAFUNG);
                return;
            }
            try {
                if (protectionService.istGeschuetzt(ziel.get().uuid(), ProtectionService.IgnoreTyp.KICK)) {
                    messageService.senden(sender, MessageKeys.GESCHUETZT);
                    return;
                }
            } catch (Exception e) {
                LOG.warn("Schutz-Check (/kickip) fehlgeschlagen.", e);
                if (protectionService.istFailClosed()) {
                    messageService.senden(sender, MessageKeys.SCHUTZ_OFFLINE_UNBEKANNT,
                            Map.of("spieler", ziel.get().name()));
                    return;
                }
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

        if (istEigeneOderServerIp(sender, kanonisch)) {
            messageService.senden(sender, MessageKeys.SELBST_IP_BANN);
            return;
        }
        if (istIpGeschuetzt(kanonisch)) {
            messageService.senden(sender, MessageKeys.GESCHUETZT);
            return;
        }
        int betroffen = warneBeiGeteilterIp(sender, kanonisch);
        if (!pruefeGeteilteIp(sender, "kick-ip", rohArgs, kanonisch, betroffen, bestaetigt)) {
            return;
        }

        String grund = grundAusArgumenten(wirksam, 1, MessageKeys.DEFAULT_KICK_GRUND);
        String ausfuehrer = ausfuehrerName(sender);
        var ausfuehrerUuid = eigeneUuid(sender).orElse(null);

        var gekickt = kickService.kickenIp(kanonisch, grund, ausfuehrer, ausfuehrerUuid, silent);
        if (gekickt.isEmpty()) {
            messageService.senden(sender, MessageKeys.SPIELER_NICHT_ONLINE, Map.of("spieler", kanonisch));
            return;
        }
        messageService.senden(sender, MessageKeys.IP_KICK_ERFOLG, Map.of(
                "ip", kanonisch,
                "anzahl", String.valueOf(gekickt.size()),
                "grund", grund));
    }

    @Override
    public String tooltipFuer(String vorschlag, String[] args) {
        String kategorie = kategorieTooltip(vorschlag);
        if (kategorie != null && !kategorie.isBlank()) {
            return kategorie;
        }
        int position = args == null ? 0 : args.length;
        if (position <= 1) {
            return "Spieler oder IP";
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
            java.util.Set<String> vorschlaege = new java.util.LinkedHashSet<>();
            try {
                for (OnlineSpieler online : verzeichnis.onlineSpieler()) {
                    if (online == null || online.ip() == null || online.ip().isBlank()
                            || online.ip().equalsIgnoreCase("unbekannt")) {
                        continue;
                    }
                    try {
                        if (protectionService.istOnlineGeschuetzt(online, ProtectionService.IgnoreTyp.KICK)) {
                            continue;
                        }
                    } catch (Exception e) {
                        LOG.debug("Online-Schutzcheck für Kick-IP-Tab übersprungen.", e);
                        continue;
                    }
                    try {
                        vorschlaege.add(IpBanRecord.kanonisieren(online.ip().trim()));
                    } catch (IllegalArgumentException e) {
                        LOG.trace("Ungültige IP übersprungen.", e);
                    }
                    if (vorschlaege.size() >= 25) {
                        break;
                    }
                }
            } catch (Exception e) {
                LOG.debug("Online-IP-Scan für Tab-Complete fehlgeschlagen.", e);
            }
            vorschlaege.addAll(bestrafbareSpielerNamen(ProtectionService.IgnoreTyp.KICK));
            return filtere(new java.util.ArrayList<>(vorschlaege), args[0]);
        }
        if (args.length == 2) {
            java.util.List<String> gemischt = new java.util.ArrayList<>(grundVorschlaege("kick"));
            gemischt.addAll(silentVorschlag(sender, ""));
            return filtere(gemischt, args[args.length - 1]);
        }
        if (args.length > 2) {
            return silentVorschlag(sender, args[args.length - 1]);
        }
        return List.of();
    }

    private int warneBeiGeteilterIp(BefehlsSender sender, String kanonischeIp) {
        int betroffen = 0;
        try {
            for (OnlineSpieler online : verzeichnis.onlineSpieler()) {
                if (online == null || online.ip() == null || online.ip().isBlank()
                        || online.ip().equalsIgnoreCase("unbekannt")) {
                    continue;
                }
                try {
                    if (IpBanRecord.kanonisieren(online.ip()).equals(kanonischeIp)) {
                        betroffen++;
                    }
                } catch (IllegalArgumentException e) {
                    LOG.trace("Ungültige IP übersprungen.", e);
                }
            }
            if (betroffen >= ipGeteiltWarnAb()) {
                LOG.warn("IP-Kick für {} betrifft {} Online-Spieler. Ausführer: {}",
                        kanonischeIp, betroffen, sender.name());
                try {
                    messageService.senden(sender, MessageKeys.IP_GETEILT_WARNUNG, Map.of(
                            "ip", kanonischeIp, "anzahl", String.valueOf(betroffen)));
                } catch (Exception e) {
                    LOG.debug("Geteilte-IP-Warnung konnte nicht gesendet werden.", e);
                }
            }
        } catch (Exception e) {
            LOG.debug("Geteilte-IP-Warnung fehlgeschlagen.", e);
        }
        return betroffen;
    }

    private boolean istEigeneOderServerIp(BefehlsSender sender, String kanonischeIp) {
        if (sender != null && sender.istSpieler() && sender.spielerUuid() != null && verzeichnis != null) {
            try {
                var selbst = verzeichnis.onlineSpieler(sender.spielerUuid());
                if (selbst.isPresent() && selbst.get().ip() != null && !selbst.get().ip().isBlank()
                        && !selbst.get().ip().equalsIgnoreCase("unbekannt")) {
                    try {
                        if (IpBanRecord.kanonisieren(selbst.get().ip()).equals(kanonischeIp)) {
                            return true;
                        }
                    } catch (IllegalArgumentException e) {
                        LOG.trace("Ungültige IP übersprungen.", e);
                    }
                }
            } catch (Exception e) {
                LOG.debug("Eigene-IP-Prüfung übersprungen.", e);
            }
        }
        try {
            java.net.InetAddress adresse = java.net.InetAddress.getByName(kanonischeIp);
            if (adresse.isLoopbackAddress() || adresse.isAnyLocalAddress() || adresse.isLinkLocalAddress()) {
                return true;
            }
            if (adresse.isSiteLocalAddress()) {
                LOG.warn("IP-Kick auf privates Netz ({}): NAT/LAN-Kollateralschaden möglich, geteilte-IP-Prüfung beachten.", kanonischeIp);
            }
        } catch (Exception e) {
            LOG.debug("Server-IP-Prüfung übersprungen.", e);
        }
        return kanonischeIp.equals("127.0.0.1") || kanonischeIp.equals("::1")
                || kanonischeIp.equals("0:0:0:0:0:0:0:1")
                || kanonischeIp.equals("0.0.0.0") || kanonischeIp.equals("::")
                || kanonischeIp.equals("0:0:0:0:0:0:0:0");
    }

    private boolean istIpGeschuetzt(String kanonischeIp) {
        if (verzeichnis != null) {
            for (OnlineSpieler online : verzeichnis.onlineSpieler()) {
                if (online == null || online.ip() == null || online.ip().isBlank()
                        || online.ip().equalsIgnoreCase("unbekannt")) {
                    continue;
                }
                try {
                    if (IpBanRecord.kanonisieren(online.ip()).equals(kanonischeIp)
                            && protectionService.istOnlineGeschuetzt(online, ProtectionService.IgnoreTyp.KICK)) {
                        return true;
                    }
                } catch (IllegalArgumentException e) {
                    LOG.trace("Ungültige IP übersprungen.", e);
                }
            }
        }
        for (CachedPlayer gecacht : playerCache.mitKanonischerIp(kanonischeIp)) {
            if (protectionService.istGeschuetzt(gecacht.uuid(), ProtectionService.IgnoreTyp.KICK)) {
                return true;
            }
        }
        return false;
    }
}
