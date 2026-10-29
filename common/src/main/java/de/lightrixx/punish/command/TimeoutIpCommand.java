package de.lightrixx.punish.command;

import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.OnlineSpieler;
import de.lightrixx.punish.core.Permissions;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.CachedPlayer;
import de.lightrixx.punish.model.IpBanRecord;
import de.lightrixx.punish.model.ParseResult;
import de.lightrixx.punish.service.IpTimeoutService;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.storage.PlayerCache;
import de.lightrixx.punish.util.TimeParser;
import de.lightrixx.punish.util.ZeitFormat;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class TimeoutIpCommand extends PunishCommand {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(TimeoutIpCommand.class);

    private final IpTimeoutService ipTimeoutService;
    private final TimeParser timeParser;

    public TimeoutIpCommand(MessageService messageService,
                            Konfiguration configManager,
                            ProtectionService protectionService,
                            PlayerCache playerCache,
                            IpTimeoutService ipTimeoutService,
                            TimeParser timeParser,
                            AuftragsPlaner taskScheduler,
                            SpielerVerzeichnis verzeichnis) {
        super(messageService, configManager, protectionService, playerCache, taskScheduler, verzeichnis);
        this.ipTimeoutService = Objects.requireNonNull(ipTimeoutService, "Der IpTimeoutService darf nicht null sein.");
        this.timeParser = Objects.requireNonNull(timeParser, "Der TimeParser darf nicht null sein.");
        Objects.requireNonNull(verzeichnis, "Das Spielerverzeichnis darf nicht null sein.");
    }

    @Override
    protected String recht() {
        return Permissions.PUNISH_TIMEOUTIP.node();
    }

    @Override
    public void ausfuehren(BefehlsSender sender, String[] args) {
        ScopeParser.Ergebnis scope = scopeAusArgs(args);
        if (blockiereBeiUnbekanntemScope(sender, scope)) {
            return;
        }
        boolean silent = silentAuswerten(sender, scope);
        String[] roh = scope.bereinigteArgs();
        if (roh.length < 1) {
            messageService.senden(sender, MessageKeys.SPIELER_NICHT_GEFUNDEN, Map.of("spieler", ""));
            return;
        }
        boolean bestaetigt = istBestaetigt(roh);
        String[] bereinigt = ohneBestaetigung(roh);
        String eingabe = bereinigt[0];
        String ausfuehrer = ausfuehrerName(sender);
        Optional<IpZiel> ziel = ipZielAufloesen(sender, eingabe, args, bestaetigt);
        if (ziel.isEmpty()) {
            return;
        }
        if (ziel.get().spieler() != null) {
            CachedPlayer spielerZiel = ziel.get().spieler();
            if (protectionService.istSelbstbestrafung(sender, spielerZiel.uuid())) {
                messageService.senden(sender, MessageKeys.SELBSTBESTRAFUNG);
                return;
            }
            try {
                if (protectionService.istRangBlockiert(sender, spielerZiel.uuid())) {
                    messageService.senden(sender, MessageKeys.GESCHUETZT);
                    return;
                }
            } catch (Exception e) {
                LOG.debug("Rang-Hierarchie-Check übersprungen (/timeoutip).", e);
            }
            try {
                if (protectionService.istGeschuetzt(spielerZiel.uuid(), ProtectionService.IgnoreTyp.TIMEOUT)) {
                    messageService.senden(sender, MessageKeys.GESCHUETZT);
                    return;
                }
            } catch (Exception e) {
                LOG.warn("Schutz-Check (/timeoutip) fehlgeschlagen.", e);
                if (protectionService.istFailClosed()) {
                    messageService.senden(sender, MessageKeys.SCHUTZ_OFFLINE_UNBEKANNT,
                            Map.of("spieler", spielerZiel.name()));
                    return;
                }
            }
        }
        verarbeitenTimeoutIpNeu(sender, bereinigt, ziel.get().ip(), ausfuehrer, scope.scope(), silent);
    }

    private void verarbeitenTimeoutIpNeu(BefehlsSender sender, String[] args, String ip, String ausfuehrer,
                                         de.lightrixx.punish.model.ServerScope scope, boolean silent) {
        if (args.length < 2) {
            messageService.senden(sender, MessageKeys.DAUER_UNGUELTIG);
            return;
        }
        ParseResult vorab = timeParser.parsen(args[1]);
        if (vorab instanceof ParseResult.Permanent) {
            messageService.senden(sender, MessageKeys.PERM_NICHT_ERLAUBT);
            return;
        }
        if (!(vorab instanceof ParseResult.Geltzeit(long dauerMillis))) {
            messageService.senden(sender, MessageKeys.DAUER_UNGUELTIG);
            return;
        }
        long maximalMillis = configManager.timeoutMaxMinuten() * 60000L;
        if (dauerMillis > maximalMillis) {
            messageService.senden(sender, MessageKeys.LIMIT_UEBERSCHRITTEN,
                    Map.of("max", String.valueOf(configManager.timeoutMaxMinuten())));
            return;
        }
        if (!istAblaufMoeglich(dauerMillis)) {
            messageService.senden(sender, MessageKeys.DAUER_UNGUELTIG);
            return;
        }
        if (ipTimeoutService.istAbgedeckt(ip, scope)) {
            messageService.senden(sender, MessageKeys.BEREITS_TIMEOUT_IP, Map.of("ip", ip));
            return;
        }
        String grund = grundAusArgumenten(args, 2, MessageKeys.DEFAULT_TIMEOUT_GRUND);
        boolean erfolg = ipTimeoutService.timeoutVerhaengen(ip, dauerMillis, grund, ausfuehrer,
                scope, herkunftVon(sender), silent);
        if (!erfolg) {
            if (ipTimeoutService.istAbgedeckt(ip, scope)) {
                messageService.senden(sender, MessageKeys.BEREITS_TIMEOUT_IP, Map.of("ip", ip));
            } else {
                messageService.senden(sender, MessageKeys.AKTION_FEHLGESCHLAGEN);
            }
            return;
        }
        messageService.senden(sender, MessageKeys.IP_TIMEOUT_ERFOLG, Map.of(
                "ip", ip,
                "dauer", ZeitFormat.formatRestzeit(dauerMillis),
                "grund", grund));
    }

    private record IpZiel(String ip, CachedPlayer spieler) {
    }

    private Optional<IpZiel> ipZielAufloesen(BefehlsSender sender, String eingabe,
                                               String[] args, boolean bestaetigt) {
        if (eingabe != null && eingabe.contains("/")) {
            messageService.senden(sender, MessageKeys.IP_UNGUELTIG, Map.of("ip", eingabe));
            return Optional.empty();
        }
        Optional<String> direkteIp = kanonisierenIp(eingabe);
        if (direkteIp.isPresent()) {
            String kanonischeIp = direkteIp.get();
            if (istEigeneOderServerIp(sender, kanonischeIp)) {
                messageService.senden(sender, MessageKeys.SELBST_IP_BANN);
                return Optional.empty();
            }
            if (istIpGeschuetzt(kanonischeIp)) {
                messageService.senden(sender, MessageKeys.GESCHUETZT);
                return Optional.empty();
            }
            int betroffen = warneBeiGeteilterIp(sender, kanonischeIp);
            if (!pruefeGeteilteIp(sender, "timeout-ip", args, kanonischeIp, betroffen, bestaetigt)) {
                return Optional.empty();
            }
            return Optional.of(new IpZiel(kanonischeIp, null));
        }
        Optional<CachedPlayer> ziel = zielAufloesen(eingabe);
        if (ziel.isEmpty()) {
            messageService.senden(sender, MessageKeys.SPIELER_NICHT_GEFUNDEN, Map.of("spieler", eingabe));
            return Optional.empty();
        }
        CachedPlayer spieler = ziel.get();
        String ip = spieler.letzteIp();
        if (ip == null || ip.isBlank() || ip.equalsIgnoreCase("unbekannt")) {
            messageService.senden(sender, MessageKeys.IP_NICHT_GEFUNDEN, Map.of("spieler", spieler.name()));
            return Optional.empty();
        }
        Optional<String> kanonisierteIp = kanonisierenIp(ip);
        if (kanonisierteIp.isEmpty()) {
            messageService.senden(sender, MessageKeys.IP_NICHT_GEFUNDEN, Map.of("spieler", spieler.name()));
            return Optional.empty();
        }
        if (istEigeneOderServerIp(sender, kanonisierteIp.get())) {
            messageService.senden(sender, MessageKeys.SELBST_IP_BANN);
            return Optional.empty();
        }
        if (istIpGeschuetzt(kanonisierteIp.get())) {
            messageService.senden(sender, MessageKeys.GESCHUETZT);
            return Optional.empty();
        }
        int betroffen = warneBeiGeteilterIp(sender, kanonisierteIp.get());
        if (!pruefeGeteilteIp(sender, "timeout-ip", args, kanonisierteIp.get(), betroffen, bestaetigt)) {
            return Optional.empty();
        }
        return Optional.of(new IpZiel(kanonisierteIp.get(), spieler));
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
        String vorlage = tooltipFuerVorlage("timeout", vorschlag);
        return vorlage != null && !vorlage.isBlank() ? vorlage : "Grund";
    }

    @Override
    public List<String> vervollstaendigen(BefehlsSender sender, String[] args) {
        String letztes = args[args.length - 1];
        if (letztes != null && letztes.startsWith("--")) {
            return scopeVorschlaege(letztes);
        }
        if (letztes != null && letztes.startsWith("-")) {
            return silentVorschlag(sender, letztes);
        }
        if (args.length == 1) {
            return spielerVorschlaegeSmart(sender, args[0]);
        }
        if (args.length == 2) {
            return filtere(dauerVorschlaegeOhnePermFuer("timeout-ip"), args[1]);
        }
        return grundOderEnde(sender, args, 2, "timeout");
    }

    private Optional<String> kanonisierenIp(String eingabe) {
        if (eingabe != null && eingabe.contains("/")) {
            return Optional.empty();
        }
        try {
            return Optional.of(IpBanRecord.kanonisieren(eingabe));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
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
                LOG.warn("IP-Timeout für {} betrifft {} Online-Spieler. Ausführer: {}",
                        kanonischeIp, betroffen, sender.name());
                try {
                    messageService.senden(sender, MessageKeys.IP_GETEILT_WARNUNG, Map.of(
                            "ip", PunishStatusCommand.maskeIp(sender, kanonischeIp),
                            "anzahl", String.valueOf(betroffen)));
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
                LOG.warn("IP-Timeout auf privates Netz ({}): NAT/LAN-Kollateralschaden möglich, geteilte-IP-Prüfung beachten.", kanonischeIp);
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
                            && protectionService.istOnlineGeschuetzt(online, ProtectionService.IgnoreTyp.TIMEOUT)) {
                        return true;
                    }
                } catch (IllegalArgumentException e) {
                    LOG.trace("Ungültige IP übersprungen.", e);
                }
            }
        }
        for (CachedPlayer gecacht : playerCache.mitKanonischerIp(kanonischeIp)) {
            if (protectionService.istGeschuetzt(gecacht.uuid(), ProtectionService.IgnoreTyp.TIMEOUT)) {
                return true;
            }
        }
        return false;
    }
}
