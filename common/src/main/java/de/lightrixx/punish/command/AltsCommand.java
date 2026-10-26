package de.lightrixx.punish.command;

import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.Permissions;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.BanRecord;
import de.lightrixx.punish.model.CachedPlayer;
import de.lightrixx.punish.model.IpBanRecord;
import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.storage.BanRepository;
import de.lightrixx.punish.storage.IpBanRepository;
import de.lightrixx.punish.storage.PlayerCache;
import de.lightrixx.punish.util.ZeitFormat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class AltsCommand extends PunishCommand {

    private final BanRepository banRepository;
    private final IpBanRepository ipBanRepository;

    public AltsCommand(MessageService messageService,
                       Konfiguration configManager,
                       ProtectionService protectionService,
                       PlayerCache playerCache,
                       BanRepository banRepository,
                       IpBanRepository ipBanRepository,
                       SpielerVerzeichnis verzeichnis) {
        super(messageService, configManager, protectionService, playerCache, null,
                Objects.requireNonNull(verzeichnis, "Das Spielerverzeichnis darf nicht null sein."));
        this.banRepository = Objects.requireNonNull(banRepository, "Das BanRepository darf nicht null sein.");
        this.ipBanRepository = Objects.requireNonNull(ipBanRepository, "Das IpBanRepository darf nicht null sein.");
    }

    @Override
    protected String recht() {
        return Permissions.PUNISH_ALTS.node();
    }

    @Override
    public void ausfuehren(BefehlsSender sender, String[] args) {
        ScopeParser.Ergebnis scope = scopeAusArgsMitUnbekannt(args);
        String[] bereinigt = scope.bereinigteArgs();
        if (bereinigt.length < 1) {
            messageService.senden(sender, MessageKeys.ALTS_SYNTAX);
            return;
        }
        String eingabe = bereinigt[0];
        String ip = loeseIp(sender, eingabe);
        if (ip == null) {
            return;
        }
        if (istAbfrageZielGeschuetzt(sender, eingabe)) {
            return;
        }
        ServerScope filter = scope.hatteFlag() ? scope.scope() : null;

        List<CachedPlayer> alts = new ArrayList<>();
        for (CachedPlayer kandidat : playerCache.findeNachIpMitHistorie(ip)) {
            if (kandidat == null) {
                continue;
            }
            if (istGeschuetztAusgeblendet(kandidat.uuid())) {
                continue;
            }
            alts.add(kandidat);
        }
        alts.sort(Comparator.comparingLong(CachedPlayer::zuletztGesehen).reversed());
        if (alts.size() > 2 && istLoopbackIp(ip)) {
            LOG.warn("/alts für '{}' listet {} Treffer mit Loopback-IP {} – vermutlich Testnetz, keine echten Alts.",
                    eingabe, alts.size(), ip);
        }

        long jetzt = System.currentTimeMillis();
        Boolean ipGebannt = istIpGebannt(ip, jetzt, filter);
        if (alts.isEmpty()) {
            messageService.senden(sender, MessageKeys.ALTS_LEER, Map.of("ip", ip));
            return;
        }
        Map<String, String> kopf = new java.util.HashMap<>();
        kopf.put("spieler", eingabe);
        kopf.put("ip", ip);
        kopf.put("anzahl", String.valueOf(alts.size()));
        kopf.put("ipstatus", ipGebannt == null
                ? miniText(MessageKeys.AKTION_FEHLGESCHLAGEN, Map.of())
                : ipStatusText(ip, jetzt, filter, ipGebannt));
        messageService.senden(sender, MessageKeys.ALTS_KOPF, kopf, Set.of("ipstatus"));

        List<net.kyori.adventure.text.Component> zeilen = new ArrayList<>(alts.size());
        for (CachedPlayer alt : alts) {
            Map<String, String> ph = new java.util.HashMap<>();
            ph.put("spieler", alt.name());
            ph.put("ip", ip);
            ph.put("zuletzt", ZeitFormat.formatZeitpunkt(alt.zuletztGesehen()));
            ph.put("banstatus", banStatusText(alt, jetzt, filter));
            zeilen.add(messageService.komponenteOhnePraefix(MessageKeys.ALTS_EINTRAG, ph, Set.of("banstatus")));
        }
        messageService.sendeBlock(sender, zeilen);
    }

    private static boolean istLoopbackIp(String ip) {
        if (ip == null) {
            return false;
        }
        String normiert = ip.trim().toLowerCase(java.util.Locale.ROOT);
        return normiert.equals("127.0.0.1")
                || normiert.equals("::1")
                || normiert.equals("0:0:0:0:0:0:0:1")
                || normiert.equals("::ffff:127.0.0.1");
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

    private boolean istAbfrageZielGeschuetzt(BefehlsSender sender, String eingabe) {
        if (eingabe == null || eingabe.isBlank() || eingabe.contains("/")) {
            return false;
        }
        try {
            Optional<CachedPlayer> ziel = zielAufloesen(eingabe);
            if (ziel.isPresent() && istGeschuetztAusgeblendet(ziel.get().uuid())) {
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

    private String loeseIp(BefehlsSender sender, String eingabe) {
        if (eingabe != null && !eingabe.contains("/")) {
            try {
                return IpBanRecord.kanonisieren(eingabe);
            } catch (IllegalArgumentException e) {
                LOG.trace("Ungültige IP übersprungen.", e);
            }
        }
        if (eingabe != null && eingabe.contains("/")) {
            messageService.senden(sender, MessageKeys.IP_UNGUELTIG, Map.of("ip", eingabe));
            return null;
        }
        Optional<CachedPlayer> ziel = zielAufloesen(eingabe);
        if (ziel.isEmpty()) {
            messageService.senden(sender, MessageKeys.SPIELER_NICHT_GEFUNDEN, Map.of("spieler", eingabe));
            return null;
        }
        String ip = ziel.get().letzteIp();
        if (ip == null || ip.isBlank() || ip.equalsIgnoreCase("unbekannt")) {
            messageService.senden(sender, MessageKeys.IP_NICHT_GEFUNDEN,
                    Map.of("spieler", ziel.get().name()));
            return null;
        }
        try {
            return IpBanRecord.kanonisieren(ip);
        } catch (IllegalArgumentException e) {
            messageService.senden(sender, MessageKeys.IP_NICHT_GEFUNDEN,
                    Map.of("spieler", ziel.get().name()));
            return null;
        }
    }

    private boolean scopePasst(ServerScope bannScope, ServerScope filter) {
        if (filter == null) {
            return true;
        }
        return bannScope != null && bannScope.speicherwert().equals(filter.speicherwert());
    }

    private String banStatusText(CachedPlayer alt, long jetzt, ServerScope filter) {
        List<BanRecord> aktiv = new ArrayList<>();
        try {
            for (BanRecord bann : banRepository.alleFuer(alt.uuid())) {
                if (bann != null && bann.istAktiv(jetzt) && scopePasst(bann.scope(), filter)) {
                    aktiv.add(bann);
                }
            }
        } catch (Exception e) {
            LOG.warn("Ban-Status für Alt '{}' konnte nicht gelesen werden.", alt.name(), e);
            return miniText(MessageKeys.AKTION_FEHLGESCHLAGEN, Map.of());
        }
        if (aktiv.isEmpty()) {
            return miniText(MessageKeys.ALTS_BAN_FREI, Map.of());
        }
        aktiv.sort(Comparator.comparingLong(b -> b.ablaufAm() == null ? Long.MAX_VALUE : b.ablaufAm()));
        BanRecord erster = aktiv.get(0);
        Map<String, String> ph = new java.util.HashMap<>();
        ph.put("spieler", alt.name());
        ph.put("scope", erster.scope().anzeige());
        ph.put("grund", erster.grund());
        if (erster.istPermanent()) {
            ph.put("rest", "Permanent");
        } else {
            ph.put("rest", ZeitFormat.formatRestzeit(Math.max(0L, erster.ablaufAm() - jetzt)));
        }
        if (aktiv.size() > 1) {
            ph.put("scope", erster.scope().anzeige() + " (+" + (aktiv.size() - 1) + ")");
        }
        return miniText(MessageKeys.ALTS_BAN_AKTIV, ph);
    }

    private Boolean istIpGebannt(String ip, long jetzt, ServerScope filter) {
        try {
            return ipBanRepository.alleFuer(ip).stream()
                    .anyMatch(b -> b != null && b.istAktiv(jetzt)
                            && scopePasst(b.scope(), filter));
        } catch (Exception e) {
            LOG.warn("IP-Bann-Check für '{}' fehlgeschlagen – Status bleibt unbekannt.", ip, e);
            return null;
        }
    }

    private String ipStatusText(String ip, long jetzt, ServerScope filter, boolean ipGebannt) {
        if (!ipGebannt) {
            return miniText(MessageKeys.ALTS_IP_FREI, Map.of("ip", ip));
        }
        String scope = "";
        try {
            for (var bann : ipBanRepository.alleFuer(ip)) {
                if (bann != null && bann.istAktiv(jetzt) && scopePasst(bann.scope(), filter)) {
                    scope = bann.scope().anzeige();
                    break;
                }
            }
        } catch (Exception e) {
            LOG.debug("IP-Bann-Scope für '{}' fehlgeschlagen.", ip, e);
        }
        return miniText(MessageKeys.ALTS_IP_GEBANNT, Map.of("ip", ip, "scope", scope));
    }

    private String miniText(String schluessel, Map<String, String> platzhalter) {
        try {
            String vorlage = configManager.nachricht(schluessel);
            return de.lightrixx.punish.util.MiniMessageUtil.ersetzen(vorlage, platzhalter);
        } catch (Exception e) {
            LOG.debug("Alts-Text für '{}' konnte nicht gebaut werden.", schluessel, e);
            return "";
        }
    }

    @Override
    public String tooltipFuer(String vorschlag, String[] args) {
        String kategorie = kategorieTooltip(vorschlag);
        return kategorie != null && !kategorie.isBlank() ? kategorie : "Spieler";
    }

    @Override
    public List<String> vervollstaendigen(BefehlsSender sender, String[] args) {
        String letztes = args.length == 0 ? "" : args[args.length - 1];
        if (letztes != null && letztes.startsWith("--")) {
            return scopeVorschlaegeMitUnbekannt(letztes);
        }
        if (args.length <= 1) {
            return filtere(alleSpielerNamen(50), letztes);
        }
        return List.of();
    }
}
