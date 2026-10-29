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
import de.lightrixx.punish.service.IpBanService;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.storage.PlayerCache;
import de.lightrixx.punish.util.TimeParser;
import de.lightrixx.punish.util.ZeitFormat;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class BanIpCommand extends PunishCommand {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(BanIpCommand.class);

    private final IpBanService ipBanService;
    private final TimeParser timeParser;

    public BanIpCommand(MessageService messageService,
                        Konfiguration configManager,
                        ProtectionService protectionService,
                        PlayerCache playerCache,
                        IpBanService ipBanService,
                        TimeParser timeParser,
                        AuftragsPlaner taskScheduler,
                        SpielerVerzeichnis verzeichnis) {
        super(messageService, configManager, protectionService, playerCache, taskScheduler, verzeichnis);
        this.ipBanService = Objects.requireNonNull(ipBanService, "Der IpBanService darf nicht null sein.");
        this.timeParser = Objects.requireNonNull(timeParser, "Der TimeParser darf nicht null sein.");
        Objects.requireNonNull(verzeichnis, "Das Spielerverzeichnis darf nicht null sein.");
    }

    @Override
    protected String recht() {
        return Permissions.PUNISH_BANIP.node();
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
        de.lightrixx.punish.model.ServerScope wirksam = scope.scope();

        String eingabe = bereinigt[0];
        String ausfuehrer = ausfuehrerName(sender);

        Optional<IpZiel> ziel = ipZielAufloesen(sender, eingabe, args, bestaetigt);
        if (ziel.isEmpty()) {
            return;
        }

        if (ziel.get().spieler() != null) {
            String ip = ziel.get().ip();
            CachedPlayer cached = ziel.get().spieler();
            final boolean still = silent;
            mitSchutz(sender, bereinigt, cached, ProtectionService.IgnoreTyp.BAN,
                    () -> verarbeitenBanIpNeu(sender, bereinigt, ip, ausfuehrer, wirksam, still));
            return;
        }
        verarbeitenBanIpNeu(sender, bereinigt, ziel.get().ip(), ausfuehrer, wirksam, silent);
    }

    private void verarbeitenBanIpNeu(BefehlsSender sender, String[] args, String ip, String ausfuehrer,
                                      de.lightrixx.punish.model.ServerScope scope, boolean silent) {

        Long dauerMillis = null;
        int grundAb = 1;
        if (args.length >= 2) {
            ParseResult geparst = timeParser.parsen(args[1]);
            if (geparst instanceof ParseResult.Geltzeit(long millis)) {
                dauerMillis = millis;
                grundAb = 2;
            } else if (geparst instanceof ParseResult.Permanent) {
                grundAb = 2;
            } else if (siehtNachDauerVersuchAus(args[1])) {
                messageService.senden(sender, MessageKeys.DAUER_UNGUELTIG);
                return;
            }
        }
        if (dauerMillis != null) {
            if (!istAblaufMoeglich(dauerMillis)) {
                messageService.senden(sender, MessageKeys.DAUER_UNGUELTIG);
                return;
            }
            long maximalMillis = configManager.banMaxMinuten() * 60000L;
            if (dauerMillis > maximalMillis) {
                messageService.senden(sender, MessageKeys.LIMIT_UEBERSCHRITTEN,
                        Map.of("max", String.valueOf(configManager.banMaxMinuten())));
                return;
            }
            long minimalMillis = configManager.banMinMinuten() * 60000L;
            if (dauerMillis < minimalMillis) {
                messageService.senden(sender, MessageKeys.LIMIT_UNTERSCHRITTEN,
                        Map.of("min", String.valueOf(configManager.banMinMinuten())));
                return;
            }
        }

        String grund = grundAusArgumenten(args, grundAb, MessageKeys.DEFAULT_BAN_IP_GRUND);
        if (dauerMillis != null) {
            boolean erfolg = ipBanService.ipBannenTemporaer(ip, dauerMillis, grund, ausfuehrer,
                    scope, herkunftVon(sender), silent);
            if (!erfolg) {
                if (ipBanService.istAbgedeckt(ip, scope)) {
                    messageService.senden(sender, MessageKeys.BEREITS_GEBANNT_IP, Map.of("ip", ip));
                } else {
                    messageService.senden(sender, MessageKeys.AKTION_FEHLGESCHLAGEN);
                }
                return;
            }
            messageService.senden(sender, MessageKeys.TEMPBAN_IP_ERFOLG, Map.of(
                    "ip", ip,
                    "dauer", ZeitFormat.formatRestzeit(dauerMillis),
                    "grund", grund));
            return;
        }

        if (sender == null || !sender.hatRecht(Permissions.PUNISH_PERMABANN.node())) {
            messageService.senden(sender, MessageKeys.PERM_NICHT_ERLAUBT);
            return;
        }
        if (!ipBanService.ipBannen(ip, grund, ausfuehrer, scope, herkunftVon(sender), silent)) {
            if (ipBanService.istAbgedeckt(ip, scope)) {
                messageService.senden(sender, MessageKeys.BEREITS_GEBANNT_IP, Map.of("ip", ip));
            } else {
                messageService.senden(sender, MessageKeys.AKTION_FEHLGESCHLAGEN);
            }
            return;
        }
        messageService.senden(sender, MessageKeys.BAN_IP_ERFOLG,
                Map.of("ip", ip, "grund", grund));
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
            if (!pruefeGeteilteIp(sender, "ban-ip", args, kanonischeIp, betroffen, bestaetigt)) {
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
        if (!pruefeGeteilteIp(sender, "ban-ip", args, kanonisierteIp.get(), betroffen, bestaetigt)) {
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
        String vorlage = tooltipFuerVorlage("ban", vorschlag);
        if (vorlage != null && !vorlage.isBlank()) {
            return vorlage;
        }
        return dauerFoermig(vorschlag) ? "Dauer" : "Grund";
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

            return filtere(dauerUndGrundVorschlaege(sender), args[1]);
        }
        return grundOderEnde(sender, args, 2, "ban");
    }

    private List<String> dauerUndGrundVorschlaege(BefehlsSender sender) {
        java.util.ArrayList<String> vorschlaege = new java.util.ArrayList<>(dauerVorschlaegeFuer("ban-ip"));

        if ((sender == null || sender.hatRecht(Permissions.PUNISH_PERMABANN.node()))
                && vorschlaege.stream().noneMatch(d -> d.equalsIgnoreCase("perm"))) {
            vorschlaege.add("perm");
        }
        vorschlaege.addAll(grundVorschlaege("ban"));
        return vorschlaege;
    }

    static boolean siehtNachDauerVersuchAus(String arg) {
        if (arg == null || arg.isBlank()) {
            return false;
        }
        String normiert = arg.trim().toLowerCase(java.util.Locale.ROOT);

        if (normiert.matches("^(s|m|h|d|w|mo|y|sec|secs|sek|min|mins|hour|hours|day|days|week|weeks|month|months|year|years)+$")) {
            return true;
        }

        for (int i = 0; i < normiert.length(); i++) {
            if (Character.isDigit(normiert.charAt(i))) {
                return true;
            }
        }
        return false;
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
                    String spielerIp = IpBanRecord.kanonisieren(online.ip());
                    if (spielerIp.equals(kanonischeIp)) {
                        betroffen++;
                    }
                } catch (IllegalArgumentException e) {
                    LOG.trace("Ungültige IP übersprungen.", e);
                }
            }
            if (betroffen >= ipGeteiltWarnAb()) {
                LOG.warn("IP-Bann für {} betrifft {} Online-Spieler (Proxy/NAT geteilte IP?). Ausführer: {}",
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
                        String eigene = IpBanRecord.kanonisieren(selbst.get().ip());
                        if (eigene.equals(kanonischeIp)) {
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
                LOG.warn("IP-Bann auf privates Netz ({}): NAT/LAN-Kollateralschaden möglich, geteilte-IP-Prüfung beachten.", kanonischeIp);
            }
        } catch (Exception e) {
            LOG.debug("Server-IP-Prüfung übersprungen.", e);
        }

        if (kanonischeIp.equals("127.0.0.1") || kanonischeIp.equals("::1")
                || kanonischeIp.equals("0:0:0:0:0:0:0:1")
                || kanonischeIp.equals("0.0.0.0") || kanonischeIp.equals("::")
                || kanonischeIp.equals("0:0:0:0:0:0:0:0")) {
            return true;
        }

        if (kanonischeIp.contains("/")) {
            return true;
        }

        return false;
    }

    private boolean istIpGeschuetzt(String kanonischeIp) {

        if (verzeichnis != null) {
            for (OnlineSpieler online : verzeichnis.onlineSpieler()) {
                if (online == null || online.ip() == null || online.ip().isBlank()
                        || online.ip().equalsIgnoreCase("unbekannt")) {
                    continue;
                }
                try {
                    String spielerIp = IpBanRecord.kanonisieren(online.ip());
                    if (spielerIp.equals(kanonischeIp) && protectionService.istOnlineGeschuetzt(online, ProtectionService.IgnoreTyp.BAN)) {
                        return true;
                    }
                } catch (IllegalArgumentException e) {
                    LOG.trace("Ungültige IP übersprungen.", e);
                }
            }
        }

        for (CachedPlayer gecacht : playerCache.mitKanonischerIp(kanonischeIp)) {
            if (protectionService.istGeschuetzt(gecacht.uuid(), ProtectionService.IgnoreTyp.BAN)) {
                return true;
            }
        }
        return false;
    }
}
