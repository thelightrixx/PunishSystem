package de.lightrixx.punish.command;

import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.OnlineSpieler;
import de.lightrixx.punish.core.Permissions;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.BanRecord;
import de.lightrixx.punish.model.CachedPlayer;
import de.lightrixx.punish.model.FreezeRecord;
import de.lightrixx.punish.model.IpBanRecord;
import de.lightrixx.punish.model.IpMuteRecord;
import de.lightrixx.punish.model.IpTimeoutRecord;
import de.lightrixx.punish.model.MuteRecord;
import de.lightrixx.punish.model.TimeoutRecord;
import de.lightrixx.punish.model.VerstossRecord;
import de.lightrixx.punish.model.WarnRecord;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.service.VerstossService;
import de.lightrixx.punish.service.WarnService;
import de.lightrixx.punish.storage.BanRepository;
import de.lightrixx.punish.storage.FreezeRepository;
import de.lightrixx.punish.storage.IpBanRepository;
import de.lightrixx.punish.storage.IpMuteRepository;
import de.lightrixx.punish.storage.IpTimeoutRepository;
import de.lightrixx.punish.storage.MuteRepository;
import de.lightrixx.punish.storage.PlayerCache;
import de.lightrixx.punish.storage.TimeoutRepository;
import de.lightrixx.punish.storage.VerstossRepository;
import de.lightrixx.punish.storage.WarnRepository;
import de.lightrixx.punish.util.ZeitFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class PunishStatusCommand extends PunishCommand {

    private static final int STATUS_SEITEN_GROESSE = 10;

    private final VerstossService verstossService;
    private final WarnService warnService;
    private final BanRepository banRepository;
    private final MuteRepository muteRepository;
    private final TimeoutRepository timeoutRepository;
    private final FreezeRepository freezeRepository;
    private final IpBanRepository ipBanRepository;
    private final IpMuteRepository ipMuteRepository;
    private final IpTimeoutRepository ipTimeoutRepository;
    private final VerstossRepository verstossRepository;
    private final WarnRepository warnRepository;

    public PunishStatusCommand(MessageService messageService,
                               Konfiguration configManager,
                               ProtectionService protectionService,
                               PlayerCache playerCache,
                               VerstossService verstossService,
                               BanRepository banRepository,
                               MuteRepository muteRepository,
                               TimeoutRepository timeoutRepository,
                               IpBanRepository ipBanRepository,
                               VerstossRepository verstossRepository,
                               SpielerVerzeichnis verzeichnis) {
        this(messageService, configManager, protectionService, playerCache, verstossService, null,
                banRepository, muteRepository, timeoutRepository, ipBanRepository, null, null,
                verstossRepository, null, verzeichnis);
    }

    public PunishStatusCommand(MessageService messageService,
                               Konfiguration configManager,
                               ProtectionService protectionService,
                               PlayerCache playerCache,
                               VerstossService verstossService,
                               WarnService warnService,
                               BanRepository banRepository,
                               MuteRepository muteRepository,
                               TimeoutRepository timeoutRepository,
                               IpBanRepository ipBanRepository,
                               VerstossRepository verstossRepository,
                               WarnRepository warnRepository,
                               SpielerVerzeichnis verzeichnis) {
        this(messageService, configManager, protectionService, playerCache, verstossService, warnService,
                banRepository, muteRepository, timeoutRepository, ipBanRepository, null, null,
                verstossRepository, warnRepository, verzeichnis);
    }

    public PunishStatusCommand(MessageService messageService,
                               Konfiguration configManager,
                               ProtectionService protectionService,
                               PlayerCache playerCache,
                               VerstossService verstossService,
                               WarnService warnService,
                               BanRepository banRepository,
                               MuteRepository muteRepository,
                               TimeoutRepository timeoutRepository,
                               IpBanRepository ipBanRepository,
                               IpMuteRepository ipMuteRepository,
                               IpTimeoutRepository ipTimeoutRepository,
                               VerstossRepository verstossRepository,
                               WarnRepository warnRepository,
                               SpielerVerzeichnis verzeichnis) {
        this(messageService, configManager, protectionService, playerCache, verstossService, warnService,
                banRepository, muteRepository, timeoutRepository, null, ipBanRepository, ipMuteRepository,
                ipTimeoutRepository, verstossRepository, warnRepository, verzeichnis);
    }

    public PunishStatusCommand(MessageService messageService,
                               Konfiguration configManager,
                               ProtectionService protectionService,
                               PlayerCache playerCache,
                               VerstossService verstossService,
                               WarnService warnService,
                               BanRepository banRepository,
                               MuteRepository muteRepository,
                               TimeoutRepository timeoutRepository,
                               FreezeRepository freezeRepository,
                               IpBanRepository ipBanRepository,
                               IpMuteRepository ipMuteRepository,
                               IpTimeoutRepository ipTimeoutRepository,
                               VerstossRepository verstossRepository,
                               WarnRepository warnRepository,
                               SpielerVerzeichnis verzeichnis) {
        super(messageService, configManager, protectionService, playerCache, null,
                Objects.requireNonNull(verzeichnis, "Das Spielerverzeichnis darf nicht null sein."));
        this.verstossService = Objects.requireNonNull(verstossService, "Der VerstossService darf nicht null sein.");
        this.warnService = warnService;
        this.banRepository = Objects.requireNonNull(banRepository, "Das BanRepository darf nicht null sein.");
        this.muteRepository = Objects.requireNonNull(muteRepository, "Das MuteRepository darf nicht null sein.");
        this.timeoutRepository = Objects.requireNonNull(timeoutRepository, "Das TimeoutRepository darf nicht null sein.");
        this.freezeRepository = freezeRepository;
        this.ipBanRepository = Objects.requireNonNull(ipBanRepository, "Das IpBanRepository darf nicht null sein.");
        this.ipMuteRepository = ipMuteRepository;
        this.ipTimeoutRepository = ipTimeoutRepository;
        this.verstossRepository = Objects.requireNonNull(verstossRepository, "Das VerstossRepository darf nicht null sein.");
        this.warnRepository = warnRepository;
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
    public boolean hatRecht(BefehlsSender sender, String[] args) {
        if (sender == null) {
            return false;
        }
        if (istSelbstAnfrage(sender, args)) {
            return sender.hatRecht(Permissions.PUNISH_OWN.node())
                    || sender.hatRecht(Permissions.PUNISH_STATUS.node());
        }
        if (istServerAnfrage(args)) {
            return sender.hatRecht(Permissions.PUNISH_STATUS_SERVER.node());
        }
        return sender.hatRecht(Permissions.PUNISH_STATUS.node());
    }

    @Override
    public boolean sichtbarFuer(BefehlsSender sender) {
        return hatRecht(sender, new String[0])
                || (sender != null && sender.hatRecht(Permissions.PUNISH_OWN.node()));
    }

    private boolean istSelbstAnfrage(BefehlsSender sender, String[] args) {
        if (sender == null || !sender.istSpieler()) {
            return false;
        }
        return PunishmentsQueryParser.parsen(args).query().zielTyp() == PunishmentsQuery.ZielTyp.SELF;
    }

    private boolean istServerAnfrage(String[] args) {
        try {
            return PunishmentsQueryParser.parsen(args).query().zielTyp() == PunishmentsQuery.ZielTyp.SERVER;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean istGeschuetztAusgeblendet(UUID zielUuid) {
        if (zielUuid == null) {
            return false;
        }
        try {
            return protectionService.istGeschuetzt(zielUuid);
        } catch (Exception e) {
            return protectionService.istFailClosed();
        }
    }

    @Override
    public void ausfuehren(BefehlsSender sender, String[] args) {
        String scopeFilter = null;
        String[] queryArgs = args;
        if (args != null && args.length > 0) {
            String letztes = args[args.length - 1];
            if (letztes != null && letztes.startsWith("--") && letztes.length() >= 3) {
                String rest = letztes.substring(2).trim().toLowerCase(java.util.Locale.ROOT);
                if (rest.equals("global") || rest.equals("unbekannt")) {
                    scopeFilter = rest;
                    queryArgs = java.util.Arrays.copyOf(args, args.length - 1);
                } else if (rest.startsWith("server=")) {
                    String name = de.lightrixx.punish.model.ServerScope.normalisiereServerName(
                            rest.substring("server=".length()));
                    if (name != null) {
                        scopeFilter = name;
                        queryArgs = java.util.Arrays.copyOf(args, args.length - 1);
                    }
                } else {
                    String name = de.lightrixx.punish.model.ServerScope.normalisiereServerName(rest);
                    if (name != null) {
                        scopeFilter = name;
                        queryArgs = java.util.Arrays.copyOf(args, args.length - 1);
                    }
                }
            }
        }
        var parsed = PunishmentsQueryParser.parsen(queryArgs);
        if (parsed.unbekannterFilter() != null) {
            messageService.senden(sender, MessageKeys.PUNISH_STATUS_FILTER_UNGUELTIG,
                    Map.of("filter", parsed.unbekannterFilter()));
            messageService.senden(sender, MessageKeys.PUNISH_STATUS_SYNTAX);
            return;
        }
        if (parsed.unbekannterSchluessel() != null) {
            messageService.senden(sender, MessageKeys.PUNISH_STATUS_SYNTAX);
            return;
        }
        PunishmentsQuery query = parsed.query();
        if (query.filter() == PunishmentsFilter.KICKS) {
            messageService.senden(sender, MessageKeys.PUNISH_STATUS_FILTER_UNGUELTIG,
                    Map.of("filter", query.filter().name().toLowerCase(Locale.ROOT)));
            messageService.senden(sender, MessageKeys.PUNISH_STATUS_SYNTAX);
            return;
        }

        if (query.zielTyp() == PunishmentsQuery.ZielTyp.SERVER) {
            serverUebersicht(sender, query, scopeFilter);
            return;
        }

        UUID zielUuid;
        String zielName;
        if (query.zielTyp() == PunishmentsQuery.ZielTyp.SELF) {
            if (!sender.istSpieler() || sender.spielerUuid() == null) {
                messageService.senden(sender, MessageKeys.NUR_SPIELER);
                return;
            }
            zielUuid = sender.spielerUuid();
            zielName = sender.name();
        } else {
            Optional<CachedPlayer> ziel = zielAufloesen(query.spielerName());
            if (ziel.isEmpty()) {
                messageService.senden(sender, MessageKeys.SPIELER_NICHT_GEFUNDEN,
                        Map.of("spieler", query.spielerName()));
                return;
            }
            zielUuid = ziel.get().uuid();
            zielName = ziel.get().name();
        }
        spielerAnsicht(sender, query, zielUuid, zielName, scopeFilter);
    }

    private static boolean passtScopeFilter(String eintragScope, String filter) {
        if (filter == null) {
            return true;
        }
        if (eintragScope == null || eintragScope.isBlank()) {
            eintragScope = de.lightrixx.punish.model.ServerScope.UNBEKANNT_WERT;
        }
        return eintragScope.trim().equalsIgnoreCase(filter);
    }

    private void spielerAnsicht(BefehlsSender sender, PunishmentsQuery query, UUID zielUuid, String zielName,
                                String scopeFilter) {
        if (query.zielTyp() != PunishmentsQuery.ZielTyp.SELF && istGeschuetztAusgeblendet(zielUuid)) {
            messageService.senden(sender, MessageKeys.GESCHUETZT);
            return;
        }
        long jetzt = System.currentTimeMillis();
        PunishmentsFilter filter = query.filter();

        List<Component> aktivZeilen = new ArrayList<>();
        int aktivAnzahl = 0;

        if (filter.zeigtBanns()) {
            var banns = banRepository.alleFuer(zielUuid).stream()
                    .filter(b -> b != null && b.istAktiv(jetzt))
                    .filter(b -> passtScopeFilter(b.scope().anzeige(), scopeFilter))
                    .sorted(Comparator.comparingLong((BanRecord record) -> record.erstelltAm()).reversed())
                    .toList();
            for (var bann : banns) {
                aktivZeilen.add(baueBannZeile(sender, bann, jetzt));
                aktivAnzahl++;
            }
        }
        if (filter.zeigtMutes()) {
            var mutes = muteRepository.alleFuer(zielUuid).stream()
                    .filter(m -> m != null && m.istAktiv(jetzt))
                    .filter(m -> passtScopeFilter(m.scope().anzeige(), scopeFilter))
                    .sorted(Comparator.comparingLong((MuteRecord record) -> record.erstelltAm()).reversed())
                    .toList();
            for (var mute : mutes) {
                aktivZeilen.add(baueMuteZeile(sender, mute, jetzt));
                aktivAnzahl++;
            }
        }
        if (filter.zeigtTimeouts()) {
            var timeouts = timeoutRepository.alleFuer(zielUuid).stream()
                    .filter(t -> t != null && t.istAktiv(jetzt))
                    .filter(t -> passtScopeFilter(t.scope().anzeige(), scopeFilter))
                    .sorted(Comparator.comparingLong((TimeoutRecord record) -> record.erstelltAm()).reversed())
                    .toList();
            for (var timeout : timeouts) {
                aktivZeilen.add(baueTimeoutZeile(sender, timeout, jetzt));
                aktivAnzahl++;
            }
        }
        if (filter.zeigtFreezes() && freezeRepository != null) {
            var freezes = freezeRepository.alleFuer(zielUuid).stream()
                    .filter(f -> f != null && f.istAktiv(jetzt))
                    .filter(f -> passtScopeFilter(f.scope().anzeige(), scopeFilter))
                    .sorted(Comparator.comparingLong((FreezeRecord record) -> record.erstelltAm()).reversed())
                    .toList();
            for (var freeze : freezes) {
                aktivZeilen.add(baueFreezeZeile(sender, freeze, jetzt));
                aktivAnzahl++;
            }
        }
        try {
            if (filter.zeigtIpBanns()) {
                var ipBanns = findeIpBannsFuer(zielUuid, jetzt).stream()
                        .filter(b -> passtScopeFilter(b.scope().anzeige(), scopeFilter))
                        .sorted(Comparator.comparingLong((IpBanRecord record) -> record.erstelltAm()).reversed())
                        .toList();
                for (var ipBann : ipBanns) {
                    aktivZeilen.add(baueIpBannZeile(sender, ipBann));
                    aktivAnzahl++;
                }
            }
            if (filter.zeigtIpMutes()) {
                var ipMutes = findeIpMutesFuer(zielUuid, jetzt).stream()
                        .filter(b -> passtScopeFilter(b.scope().anzeige(), scopeFilter))
                        .sorted(Comparator.comparingLong((IpMuteRecord record) -> record.erstelltAm()).reversed())
                        .toList();
                for (var ipMute : ipMutes) {
                    aktivZeilen.add(baueIpMuteZeile(sender, ipMute));
                    aktivAnzahl++;
                }
            }
            if (filter.zeigtIpTimeouts()) {
                var ipTimeouts = findeIpTimeoutsFuer(zielUuid, jetzt).stream()
                        .filter(b -> passtScopeFilter(b.scope().anzeige(), scopeFilter))
                        .sorted(Comparator.comparingLong((IpTimeoutRecord record) -> record.erstelltAm()).reversed())
                        .toList();
                for (var ipTimeout : ipTimeouts) {
                    aktivZeilen.add(baueIpTimeoutZeile(sender, ipTimeout));
                    aktivAnzahl++;
                }
            }
        } catch (IllegalStateException e) {
            LOG.warn("IP-Strafen für {} konnten nicht gelesen werden.", zielUuid, e);
            aktivZeilen.add(messageService.komponenteOhnePraefix(
                    MessageKeys.AKTION_FEHLGESCHLAGEN, Map.of()));
        }
        List<VerstossRecord> verstoesse = filter.zeigtVerstoesse()
                ? verstossService.findeVerstoesse(zielUuid)
                : List.of();
        if (!verstoesse.isEmpty()) {
            aktivZeilen.add(messageService.komponenteOhnePraefix(MessageKeys.PUNISH_STATUS_VERSTOESSE_KOPF, Map.of(
                    "spieler", zielName,
                    "anzahl", String.valueOf(verstoesse.size()),
                    "punkte", String.valueOf(verstossService.punkte(zielUuid)),
                    "max", String.valueOf(configManager.verstoesseMaxPunkte()))));
            for (VerstossRecord verstoss : verstoesse) {
                aktivZeilen.add(baueVerstossZeile(sender, verstoss, zielName));
            }
            aktivAnzahl += verstoesse.size();
        }
        List<WarnRecord> warns = filter.zeigtWarns() && warnService != null
                ? warnService.findeWarns(zielUuid)
                : List.of();
        if (!warns.isEmpty()) {
            aktivZeilen.add(messageService.komponenteOhnePraefix(MessageKeys.PUNISH_STATUS_WARNS_KOPF, Map.of(
                    "spieler", zielName,
                    "anzahl", String.valueOf(warns.size()))));
            for (WarnRecord warn : warns) {
                aktivZeilen.add(baueWarnZeile(sender, warn, zielName));
            }
            aktivAnzahl += warns.size();
        }

        sendeErgebnis(sender, query, zielName,
                query.zielTyp() == PunishmentsQuery.ZielTyp.SELF ? "self" : zielName,
                scopeFilter, aktivZeilen, aktivAnzahl);
    }

    private List<IpBanRecord> findeIpBannsFuer(UUID zielUuid, long jetzt) {
        Optional<String> rohIp = letzteIpFuer(zielUuid);
        if (rohIp.isEmpty()) {
            return List.of();
        }
        String ip = rohIp.get().trim();
        String kanonisch;
        try {
            kanonisch = de.lightrixx.punish.model.IpBanRecord.kanonisieren(ip);
        } catch (IllegalArgumentException e) {
            kanonisch = ip;
        } catch (Exception e) {
            return List.of();
        }
        try {
            return ipBanRepository.alleFuer(kanonisch).stream()
                    .filter(bann -> bann != null && bann.istAktiv(jetzt))
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("IP-Banns konnten nicht gelesen werden.", e);
        }
    }

    private Optional<String> letzteIpFuer(UUID zielUuid) {
        return playerCache.finden(zielUuid)
                .map(player -> player.letzteIp())
                .filter(ip -> ip != null && !ip.isBlank() && !"unbekannt".equalsIgnoreCase(ip.trim()));
    }

    private List<IpMuteRecord> findeIpMutesFuer(UUID zielUuid, long jetzt) {
        if (ipMuteRepository == null) {
            return List.of();
        }
        Optional<String> rohIp = letzteIpFuer(zielUuid);
        if (rohIp.isEmpty()) {
            return List.of();
        }
        String kanonisch;
        try {
            kanonisch = de.lightrixx.punish.model.IpBanRecord.kanonisieren(rohIp.get().trim());
        } catch (Exception e) {
            return List.of();
        }
        try {
            return ipMuteRepository.alleFuer(kanonisch).stream()
                    .filter(m -> m != null && m.istAktiv(jetzt))
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("IP-Mutes konnten nicht gelesen werden.", e);
        }
    }

    private List<IpTimeoutRecord> findeIpTimeoutsFuer(UUID zielUuid, long jetzt) {
        if (ipTimeoutRepository == null) {
            return List.of();
        }
        Optional<String> rohIp = letzteIpFuer(zielUuid);
        if (rohIp.isEmpty()) {
            return List.of();
        }
        String kanonisch;
        try {
            kanonisch = de.lightrixx.punish.model.IpBanRecord.kanonisieren(rohIp.get().trim());
        } catch (Exception e) {
            return List.of();
        }
        try {
            return ipTimeoutRepository.alleFuer(kanonisch).stream()
                    .filter(t -> t != null && t.istAktiv(jetzt))
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("IP-Timeouts konnten nicht gelesen werden.", e);
        }
    }

    private void serverUebersicht(BefehlsSender sender, PunishmentsQuery query, String scopeFilter) {
        long jetzt = System.currentTimeMillis();
        PunishmentsFilter filter = query.filter();

        List<Component> aktivZeilen = new ArrayList<>();
        int aktivAnzahl = 0;

        if (filter.zeigtBanns()) {
            var banns = banRepository.alle().stream()
                    .filter(Objects::nonNull)
                    .filter(b -> b.istAktiv(jetzt))
                    .filter(b -> !istGeschuetztAusgeblendet(b.spielerUuid()))
                    .filter(b -> passtScopeFilter(b.scope().anzeige(), scopeFilter))
                    .sorted(Comparator.comparingLong((BanRecord record) -> record.erstelltAm()).reversed())
                    .toList();
            aktivAnzahl += banns.size();
            aktivZeilen.addAll(kappeServerZeilen(banns, b -> baueBannZeile(sender, b, jetzt)));
        }
        if (filter.zeigtMutes()) {
            var mutes = muteRepository.alle().stream()
                    .filter(Objects::nonNull)
                    .filter(m -> m.istAktiv(jetzt))
                    .filter(m -> !istGeschuetztAusgeblendet(m.spielerUuid()))
                    .filter(m -> passtScopeFilter(m.scope().anzeige(), scopeFilter))
                    .sorted(Comparator.comparingLong((MuteRecord record) -> record.erstelltAm()).reversed())
                    .toList();
            aktivAnzahl += mutes.size();
            aktivZeilen.addAll(kappeServerZeilen(mutes, m -> baueMuteZeile(sender, m, jetzt)));
        }
        if (filter.zeigtTimeouts()) {
            var timeouts = timeoutRepository.alle().stream()
                    .filter(Objects::nonNull)
                    .filter(t -> t.istAktiv(jetzt))
                    .filter(t -> !istGeschuetztAusgeblendet(t.spielerUuid()))
                    .filter(t -> passtScopeFilter(t.scope().anzeige(), scopeFilter))
                    .sorted(Comparator.comparingLong((TimeoutRecord record) -> record.erstelltAm()).reversed())
                    .toList();
            aktivAnzahl += timeouts.size();
            aktivZeilen.addAll(kappeServerZeilen(timeouts, t -> baueTimeoutZeile(sender, t, jetzt)));
        }
        if (filter.zeigtFreezes() && freezeRepository != null) {
            var freezes = freezeRepository.alle().stream()
                    .filter(Objects::nonNull)
                    .filter(f -> f.istAktiv(jetzt))
                    .filter(f -> !istGeschuetztAusgeblendet(f.spielerUuid()))
                    .filter(f -> passtScopeFilter(f.scope().anzeige(), scopeFilter))
                    .sorted(Comparator.comparingLong((FreezeRecord record) -> record.erstelltAm()).reversed())
                    .toList();
            aktivAnzahl += freezes.size();
            aktivZeilen.addAll(kappeServerZeilen(freezes, f -> baueFreezeZeile(sender, f, jetzt)));
        }
        if (filter.zeigtIpBanns()) {
            var ipBanns = ipBanRepository.alle().stream()
                    .filter(Objects::nonNull)
                    .filter(ip -> ip.istAktiv(jetzt))
                    .filter(ip -> passtScopeFilter(ip.scope().anzeige(), scopeFilter))
                    .sorted(Comparator.comparingLong((IpBanRecord record) -> record.erstelltAm()).reversed())
                    .toList();
            aktivAnzahl += ipBanns.size();
            aktivZeilen.addAll(kappeServerZeilen(ipBanns, ip -> baueIpBannZeile(sender, ip)));
        }
        if (filter.zeigtIpMutes() && ipMuteRepository != null) {
            var ipMutes = ipMuteRepository.alle().stream()
                    .filter(Objects::nonNull)
                    .filter(ip -> ip.istAktiv(jetzt))
                    .filter(ip -> passtScopeFilter(ip.scope().anzeige(), scopeFilter))
                    .sorted(Comparator.comparingLong((IpMuteRecord record) -> record.erstelltAm()).reversed())
                    .toList();
            aktivAnzahl += ipMutes.size();
            aktivZeilen.addAll(kappeServerZeilen(ipMutes, ip -> baueIpMuteZeile(sender, ip)));
        }
        if (filter.zeigtIpTimeouts() && ipTimeoutRepository != null) {
            var ipTimeouts = ipTimeoutRepository.alle().stream()
                    .filter(Objects::nonNull)
                    .filter(ip -> ip.istAktiv(jetzt))
                    .filter(ip -> passtScopeFilter(ip.scope().anzeige(), scopeFilter))
                    .sorted(Comparator.comparingLong((IpTimeoutRecord record) -> record.erstelltAm()).reversed())
                    .toList();
            aktivAnzahl += ipTimeouts.size();
            aktivZeilen.addAll(kappeServerZeilen(ipTimeouts, ip -> baueIpTimeoutZeile(sender, ip)));
        }
        if (filter.zeigtVerstoesse()) {
            var proSpieler = verstossRepository.alle().entrySet().stream()
                    .filter(e -> e.getKey() != null && !istGeschuetztAusgeblendet(e.getKey()))
                    .filter(e -> e.getValue() != null && !e.getValue().isEmpty())
                    .sorted(Comparator.<Map.Entry<UUID, List<VerstossRecord>>>comparingInt(
                            e -> e.getValue().size()).reversed()
                            .thenComparing(e -> anzeigeNameFuer(e.getKey(), e.getValue())))
                    .toList();
            aktivAnzahl += proSpieler.stream().mapToInt(e -> e.getValue().size()).sum();
            for (var eintrag : proSpieler) {
                List<VerstossRecord> liste = eintrag.getValue();
                VerstossRecord letzter = liste.get(liste.size() - 1);
                aktivZeilen.add(messageService.komponenteOhnePraefix(MessageKeys.PUNISH_STATUS_VERSTOSS_ZEILE, Map.of(
                        "spieler", anzeigeNameFuer(eintrag.getKey(), liste),
                        "anzahl", String.valueOf(liste.size()),
                        "max", String.valueOf(configManager.verstoesseMaxPunkte()),
                        "stufe", letzter.stufe().schluessel(),
                        "grund", letzter.grund(),
                        "datum", ZeitFormat.formatZeitpunkt(letzter.erstelltAm()))));
            }
        }
        if (filter.zeigtWarns() && warnRepository != null) {
            var proSpielerWarn = warnRepository.alle().entrySet().stream()
                    .filter(e -> e.getKey() != null && !istGeschuetztAusgeblendet(e.getKey()))
                    .filter(e -> e.getValue() != null && !e.getValue().isEmpty())
                    .sorted(Comparator.<Map.Entry<UUID, List<WarnRecord>>>comparingInt(
                            e -> e.getValue().size()).reversed()
                            .thenComparing(e -> anzeigeNameFuerWarn(e.getKey(), e.getValue())))
                    .toList();
            aktivAnzahl += proSpielerWarn.stream().mapToInt(e -> e.getValue().size()).sum();
            for (var eintrag : proSpielerWarn) {
                List<WarnRecord> liste = eintrag.getValue();
                WarnRecord letzter = liste.get(liste.size() - 1);
                aktivZeilen.add(messageService.komponenteOhnePraefix(MessageKeys.PUNISH_STATUS_WARN_EINTRAG, Map.of(
                        "id", String.valueOf(letzter.nummer()),
                        "spieler", anzeigeNameFuerWarn(eintrag.getKey(), liste),
                        "grund", letzter.grund(),
                        "ausfuehrer", letzter.ausfuehrer(),
                        "datum", ZeitFormat.formatZeitpunkt(letzter.erstelltAm()))));
            }
        }

        sendeErgebnis(sender, query, "Server", "server", scopeFilter, aktivZeilen, aktivAnzahl);
    }

    private String anzeigeNameFuer(UUID uuid, List<VerstossRecord> liste) {
        if (liste != null && !liste.isEmpty() && liste.get(0) != null) {
            return liste.get(0).spielerName();
        }
        return playerCache.finden(uuid).map(player -> player.name()).orElse(uuid.toString());
    }

    private String anzeigeNameFuerWarn(UUID uuid, List<WarnRecord> liste) {
        if (liste != null && !liste.isEmpty() && liste.get(0) != null) {
            return liste.get(0).spielerName();
        }
        return playerCache.finden(uuid).map(player -> player.name()).orElse(uuid.toString());
    }

    private <T> List<Component> kappeServerZeilen(List<T> alle, java.util.function.Function<T, Component> mapper) {
        List<Component> zeilen = new ArrayList<>();
        for (T eintrag : alle) {
            zeilen.add(mapper.apply(eintrag));
        }
        return zeilen;
    }

    private void sendeErgebnis(BefehlsSender sender, PunishmentsQuery query, String zielName,
                               String zielToken, String scopeFilter,
                               List<Component> aktivZeilen, int aktivAnzahl) {
        int seitenGroesse = configManager.historyEintraegeProSeite();
        if (seitenGroesse <= 0) {
            seitenGroesse = STATUS_SEITEN_GROESSE;
        }
        int seiten = Math.max(1, (aktivZeilen.size() + seitenGroesse - 1) / seitenGroesse);
        int seite = Math.min(Math.max(1, query.seite()), seiten);
        int von = (seite - 1) * seitenGroesse;
        int bis = Math.min(von + seitenGroesse, aktivZeilen.size());
        List<Component> seitenZeilen = von < bis ? aktivZeilen.subList(von, bis) : List.of();

        List<Component> zeilen = new ArrayList<>();
        zeilen.add(messageService.komponente(MessageKeys.PUNISH_STATUS_KOPF, Map.of(
                "ziel", zielName,
                "filter", query.filter().anzeigename(),
                "seite", String.valueOf(seite),
                "seiten", String.valueOf(seiten))));

        zeilen.add(messageService.komponenteOhnePraefix(MessageKeys.PUNISH_STATUS_AKTIV_KOPF,
                Map.of("anzahl", String.valueOf(aktivAnzahl),
                        "seite", String.valueOf(seite),
                        "seiten", String.valueOf(seiten))));
        if (aktivZeilen.isEmpty()) {
            zeilen.add(messageService.komponenteOhnePraefix(MessageKeys.PUNISH_STATUS_AKTIV_LEER,
                    Map.of("ziel", zielName)));
        } else {
            zeilen.addAll(seitenZeilen);
        }
        if (seiten > 1) {
            zeilen.add(baueNavigation(query, zielToken, scopeFilter, seite, seiten));
        }
        messageService.sendeBlock(sender, zeilen);
    }

    private Component baueNavigation(PunishmentsQuery query, String zielToken, String scopeFilter,
                                     int seite, int seiten) {
        String basis = "/punish-status " + zielToken
                + " filter:" + query.filter().name().toLowerCase(Locale.ROOT) + " page:";
        String scope = scopeFilter == null || scopeFilter.isBlank() ? "" : " --" + scopeFilter;
        Component nav = Component.text("‹ Zurück", seite > 1 ? NamedTextColor.YELLOW : NamedTextColor.DARK_GRAY);
        if (seite > 1) {
            String befehl = basis + (seite - 1) + scope;
            nav = nav.clickEvent(ClickEvent.runCommand(befehl))
                    .hoverEvent(HoverEvent.showText(Component.text(befehl, NamedTextColor.GRAY)));
        }
        nav = nav.append(Component.text("  |  Seite " + seite + "/" + seiten + "  |  ", NamedTextColor.GRAY));
        Component weiter = Component.text("Weiter ›", seite < seiten ? NamedTextColor.YELLOW : NamedTextColor.DARK_GRAY);
        if (seite < seiten) {
            String befehl = basis + (seite + 1) + scope;
            weiter = weiter.clickEvent(ClickEvent.runCommand(befehl))
                    .hoverEvent(HoverEvent.showText(Component.text(befehl, NamedTextColor.GRAY)));
        }
        return nav.append(weiter);
    }

    private Component baueBannZeile(BefehlsSender sender, BanRecord bann, long jetzt) {
        String typ = (bann.autoBann() ? "Auto-Bann"
                : (bann.ablaufAm() == null ? "Permanenter Bann" : "Bann"))
                + " [" + bann.scope().anzeige() + "]";
        Map<String, String> ph = aktivPlatzhalter(typ, bann.spielerName(), bann.ausfuehrer(),
                bann.grund(), bann.erstelltAm(), bann.ablaufAm(), jetzt);
        ph.put("scope", bann.scope().anzeige());
        Component zeile = messageService.komponenteOhnePraefix(MessageKeys.PUNISH_STATUS_EINTRAG, ph);
        if (sender.hatRecht(Permissions.PUNISH_PARDON.node()) && gueltigerName(bann.spielerName())) {
            return mitKlick(zeile, "/pardon " + bann.spielerName(), ph);
        }
        return zeile;
    }

    private Component baueMuteZeile(BefehlsSender sender, MuteRecord mute, long jetzt) {
        Map<String, String> ph = aktivPlatzhalter("Mute [" + mute.scope().anzeige() + "]",
                mute.spielerName(), mute.ausfuehrer(),
                mute.grund(), mute.erstelltAm(), mute.ablaufAm(), jetzt);
        ph.put("scope", mute.scope().anzeige());
        Component zeile = messageService.komponenteOhnePraefix(MessageKeys.PUNISH_STATUS_EINTRAG, ph);
        if (sender.hatRecht(Permissions.PUNISH_UNMUTE.node()) && gueltigerName(mute.spielerName())) {
            return mitKlick(zeile, "/unmute " + mute.spielerName(), ph);
        }
        return zeile;
    }

    private Component baueTimeoutZeile(BefehlsSender sender, TimeoutRecord timeout, long jetzt) {
        Map<String, String> ph = aktivPlatzhalter("Timeout [" + timeout.scope().anzeige() + "]",
                timeout.spielerName(), timeout.ausfuehrer(),
                timeout.grund(), timeout.erstelltAm(), timeout.ablaufAm(), jetzt);
        ph.put("scope", timeout.scope().anzeige());
        Component zeile = messageService.komponenteOhnePraefix(MessageKeys.PUNISH_STATUS_EINTRAG, ph);
        if (sender.hatRecht(Permissions.PUNISH_UNTIMEOUT.node()) && gueltigerName(timeout.spielerName())) {
            return mitKlick(zeile, "/untimeout " + timeout.spielerName(), ph);
        }
        return zeile;
    }

    private Component baueFreezeZeile(BefehlsSender sender, FreezeRecord freeze, long jetzt) {
        Long ablauf = freeze.istPermanent() ? null : freeze.ablaufAm();
        Map<String, String> ph = aktivPlatzhalter("Freeze [" + freeze.scope().anzeige() + "]",
                freeze.spielerName(), freeze.ausfuehrer(),
                freeze.grund(), freeze.erstelltAm(), ablauf, jetzt);
        ph.put("scope", freeze.scope().anzeige());
        Component zeile = messageService.komponenteOhnePraefix(MessageKeys.PUNISH_STATUS_EINTRAG, ph);
        if (sender.hatRecht(Permissions.PUNISH_UNFREEZE.node()) && gueltigerName(freeze.spielerName())) {
            return mitKlick(zeile, "/unfreeze " + freeze.spielerName(), ph);
        }
        return zeile;
    }

    public static String maskeIp(BefehlsSender sender, String ip) {
        if (ip == null || ip.isBlank() || sender == null
                || sender.hatRecht(Permissions.PUNISH_ALTS.node())) {
            return ip;
        }
        String text = ip.trim();
        int doppelpunkt = text.indexOf(':');
        if (doppelpunkt >= 0) {
            String[] teile = text.split(":");
            String kopf = teile.length > 1 && !teile[1].isBlank() ? teile[0] + ":" + teile[1] : teile[0];
            return kopf + ":…";
        }
        int ersterPunkt = text.indexOf('.');
        if (ersterPunkt < 0) {
            return "…";
        }
        int zweiterPunkt = text.indexOf('.', ersterPunkt + 1);
        return (zweiterPunkt < 0 ? text.substring(0, ersterPunkt) : text.substring(0, zweiterPunkt)) + ".…";
    }

    private Component baueIpBannZeile(BefehlsSender sender, IpBanRecord ipBann) {
        long jetzt = System.currentTimeMillis();
        Map<String, String> ph = aktivPlatzhalter("IP-Bann [" + ipBann.scope().anzeige() + "]",
                maskeIp(sender, ipBann.ip()), ipBann.ausfuehrer(),
                ipBann.grund(), ipBann.erstelltAm(), ipBann.ablaufAm(), jetzt);
        ph.put("scope", ipBann.scope().anzeige());
        Component zeile = messageService.komponenteOhnePraefix(MessageKeys.PUNISH_STATUS_EINTRAG, ph);
        if (sender.hatRecht(Permissions.PUNISH_ALTS.node())
                && sender.hatRecht(Permissions.PUNISH_UNBANIP.node())) {
            return mitKlick(zeile, "/unban-ip " + ipBann.ip(), ph);
        }
        return zeile;
    }

    private Component baueIpMuteZeile(BefehlsSender sender, IpMuteRecord ipMute) {
        long jetzt = System.currentTimeMillis();
        Map<String, String> ph = aktivPlatzhalter("IP-Mute [" + ipMute.scope().anzeige() + "]",
                maskeIp(sender, ipMute.ip()), ipMute.ausfuehrer(),
                ipMute.grund(), ipMute.erstelltAm(), ipMute.ablaufAm(), jetzt);
        ph.put("scope", ipMute.scope().anzeige());
        Component zeile = messageService.komponenteOhnePraefix(MessageKeys.PUNISH_STATUS_EINTRAG, ph);
        if (sender.hatRecht(Permissions.PUNISH_ALTS.node())
                && sender.hatRecht(Permissions.PUNISH_UNMUTEIP.node())) {
            return mitKlick(zeile, "/unmute-ip " + ipMute.ip(), ph);
        }
        return zeile;
    }

    private Component baueIpTimeoutZeile(BefehlsSender sender, IpTimeoutRecord ipTimeout) {
        long jetzt = System.currentTimeMillis();
        Map<String, String> ph = aktivPlatzhalter("IP-Timeout [" + ipTimeout.scope().anzeige() + "]",
                maskeIp(sender, ipTimeout.ip()), ipTimeout.ausfuehrer(),
                ipTimeout.grund(), ipTimeout.erstelltAm(), ipTimeout.ablaufAm(), jetzt);
        ph.put("scope", ipTimeout.scope().anzeige());
        Component zeile = messageService.komponenteOhnePraefix(MessageKeys.PUNISH_STATUS_EINTRAG, ph);
        if (sender.hatRecht(Permissions.PUNISH_ALTS.node())
                && sender.hatRecht(Permissions.PUNISH_UNTIMEOUTIP.node())) {
            return mitKlick(zeile, "/untimeout-ip " + ipTimeout.ip(), ph);
        }
        return zeile;
    }

    private Component baueVerstossZeile(BefehlsSender sender, VerstossRecord verstoss, String spielerName) {
        Map<String, String> ph = new java.util.HashMap<>(Map.of(
                "id", String.valueOf(verstoss.nummer()),
                "spieler", spielerName,
                "grund", verstoss.grund(),
                "stufe", verstoss.stufe().schluessel(),
                "ausfuehrer", verstoss.ausfuehrer(),
                "datum", ZeitFormat.formatZeitpunkt(verstoss.erstelltAm())));

        ph.put("kennung", de.lightrixx.punish.model.VerstossKennung.kennung(verstoss));
        Component zeile = messageService.komponenteOhnePraefix(MessageKeys.PUNISH_STATUS_VERSTOSS_EINTRAG, ph);
        if ((sender.hatRecht(Permissions.PUNISH_VERSTOSS_REMOVE.node()) || sender.hatRecht(Permissions.PUNISH_VERSTOSS.node()))
                && gueltigerName(spielerName)) {
            return mitKlick(zeile, "/verstoss remove " + spielerName + " " + verstoss.nummer(), ph);
        }
        return zeile;
    }

    private Component baueWarnZeile(BefehlsSender sender, WarnRecord warn, String spielerName) {
        Map<String, String> ph = new java.util.HashMap<>(Map.of(
                "id", String.valueOf(warn.nummer()),
                "spieler", spielerName,
                "grund", warn.grund(),
                "text", warn.grund(),
                "ausfuehrer", warn.ausfuehrer(),
                "datum", ZeitFormat.formatZeitpunkt(warn.erstelltAm())));
        Component zeile = messageService.komponenteOhnePraefix(MessageKeys.PUNISH_STATUS_WARN_EINTRAG, ph);
        if (sender.hatRecht(Permissions.PUNISH_WARN.node()) && gueltigerName(spielerName)) {
            return mitKlick(zeile, "/warn delete " + spielerName + " " + warn.nummer(), ph);
        }
        return zeile;
    }

    private Map<String, String> aktivPlatzhalter(String typ, String ziel, String ausfuehrer,
                                                 String grund, long erstelltAm, Long ablaufAm, long jetzt) {
        Map<String, String> ph = new java.util.HashMap<>();
        ph.put("typ", typ);
        ph.put("ziel", ziel);
        ph.put("ausfuehrer", ausfuehrer);
        ph.put("grund", ScopeParser.saeubereGrund(grund, bekannteServer()));
        ph.put("datum", ZeitFormat.formatZeitpunkt(erstelltAm));
        ph.put("ablauf", ablaufAm != null ? ZeitFormat.formatZeitpunkt(ablaufAm) : "Permanent");
        ph.put("verbleibend", ablaufAm != null
                ? ZeitFormat.formatRestzeit(Math.max(0L, ablaufAm - jetzt))
                : "Permanent");
        return ph;
    }

    private Component mitKlick(Component zeile, String befehl, Map<String, String> ph) {
        Component hover = messageService.komponenteOhnePraefix(MessageKeys.PUNISH_STATUS_HOVER, ph);
        return zeile.hoverEvent(HoverEvent.showText(hover))
                .clickEvent(ClickEvent.suggestCommand(befehl));
    }

    private boolean gueltigerName(String name) {
        return name != null && name.matches("[A-Za-z0-9_]{3,16}");
    }

    @Override
    public String tooltipFuer(String vorschlag, String[] args) {
        String kategorie = kategorieTooltip(vorschlag);
        if (kategorie != null && !kategorie.isBlank()) {
            return kategorie;
        }
        if (vorschlag != null) {
            if (vorschlag.equalsIgnoreCase("self")) {
                return "Eigene Daten";
            }
            if (vorschlag.equalsIgnoreCase("server")) {
                return "Server-Daten";
            }
        }
        return "Spieler";
    }

    @Override
    public List<String> vervollstaendigen(BefehlsSender sender, String[] args) {
        if (args.length == 0) {
            return List.of();
        }
        String aktuell = args[args.length - 1] == null ? "" : args[args.length - 1];

        if (aktuell.startsWith("--")) {
            java.util.List<String> scope = new java.util.ArrayList<>(
                    ScopeParser.vorschlaege(aktuell, bekannteServer()));
            if ("--unbekannt".startsWith(aktuell.toLowerCase(java.util.Locale.ROOT))
                    && !scope.contains("--unbekannt")) {
                scope.add("--unbekannt");
            }
            return scope;
        }
        int trenner = Math.max(aktuell.indexOf(':'), aktuell.indexOf('='));
        if (trenner >= 0) {
            String schluessel = aktuell.substring(0, trenner).toLowerCase(Locale.ROOT);
            String praefix = aktuell.substring(0, trenner + 1);
            String rest = aktuell.substring(trenner + 1);
            return switch (schluessel) {
                case "filter", "typ", "type" -> filtere(
                        List.of("all", "bans", "mutes", "timeouts", "freezes", "verstoesse", "warns", "ipbans",
                                        "ipmutes", "iptimeouts")
                                .stream().map(w -> praefix + w).toList(), aktuell);
                case "player", "spieler", "target", "ziel", "p" ->
                        filtere(spielerKandidatenMitPraefix(sender, praefix, rest), aktuell);
                default -> List.of();
            };
        }

        boolean darfFremd = sender == null || !sender.istSpieler()
                || sender.hatRecht(Permissions.PUNISH_STATUS.node());
        boolean hatZiel = enthaeltZiel(args, args.length - 1);
        boolean hatFilter = enthaeltFilter(args, args.length - 1);

        if (!darfFremd) {
            if (!hatZiel) {
                return filtere(List.of("self"), aktuell);
            }
            if (!hatFilter) {
                return filtere(FILTER_VORSCHLAEGE, aktuell);
            }
            return List.of();
        }

        if (!hatZiel) {
            return filtere(bareZielKandidaten(sender, aktuell), aktuell);
        }
        if (!hatFilter) {
            java.util.List<String> gemischt = new java.util.ArrayList<>(FILTER_VORSCHLAEGE);
            gemischt.addAll(ScopeParser.vorschlaege("--", bekannteServer()));
            gemischt.add("--unbekannt");
            return filtere(gemischt, aktuell);
        }

        java.util.List<String> scope = new java.util.ArrayList<>(
                ScopeParser.vorschlaege("--", bekannteServer()));
        scope.add("--unbekannt");
        return filtere(scope, aktuell);
    }

    private static final List<String> FILTER_VORSCHLAEGE = List.of(
            "filter:all", "filter:bans", "filter:mutes", "filter:timeouts", "filter:freezes",
            "filter:verstoesse", "filter:warns", "filter:ipbans",
            "filter:ipmutes", "filter:iptimeouts");

    private static boolean enthaeltFilter(String[] args, int bisExklusiv) {
        for (int i = 0; i < bisExklusiv && i < args.length; i++) {
            String token = args[i];
            if (token == null || token.isBlank()) {
                continue;
            }
            int trenner = Math.max(token.indexOf(':'), token.indexOf('='));
            if (trenner < 0) {
                continue;
            }
            String schluessel = token.substring(0, trenner).trim().toLowerCase(Locale.ROOT);
            if (schluessel.equals("filter") || schluessel.equals("typ") || schluessel.equals("type")) {
                return true;
            }
        }
        return false;
    }

    private static boolean enthaeltZiel(String[] args, int bisExklusiv) {
        for (int i = 0; i < bisExklusiv && i < args.length; i++) {
            String token = args[i];
            if (token == null || token.isBlank()) {
                continue;
            }
            String getrimmt = token.trim();
            int trenner = Math.max(getrimmt.indexOf(':'), getrimmt.indexOf('='));
            if (trenner >= 0) {
                String schluessel = getrimmt.substring(0, trenner).trim().toLowerCase(Locale.ROOT);
                if (schluessel.equals("player") || schluessel.equals("spieler")
                        || schluessel.equals("target") || schluessel.equals("ziel")
                        || schluessel.equals("p")) {
                    return true;
                }
                continue;
            }
            if (getrimmt.matches("\\d+")) {
                continue;
            }

            return true;
        }
        return false;
    }

    private List<String> bareZielKandidaten(BefehlsSender sender, String prefix) {
        Set<String> kombiniert = new LinkedHashSet<>();
        kombiniert.add("self");
        if (sender == null || sender.hatRecht(Permissions.PUNISH_STATUS_SERVER.node())) {
            kombiniert.add("server");
        }
        kombiniert.addAll(onlineTreffer(prefix, 30));
        if (prefix != null && prefix.trim().length() >= 3) {
            kombiniert.addAll(offlineSpielerVorschlaege(prefix));
        }
        return new ArrayList<>(kombiniert);
    }

    private List<String> spielerKandidatenMitPraefix(BefehlsSender sender, String praefix, String rest) {
        boolean darfFremd = sender == null || !sender.istSpieler()
                || sender.hatRecht(Permissions.PUNISH_STATUS.node());
        Set<String> kombiniert = new LinkedHashSet<>();
        if (!darfFremd) {
            kombiniert.add(praefix + "self");
            return new ArrayList<>(kombiniert);
        }
        kombiniert.add(praefix + "self");
        if (sender == null || sender.hatRecht(Permissions.PUNISH_STATUS_SERVER.node())) {
            kombiniert.add(praefix + "server");
        }
        for (String name : onlineTreffer(rest, 30)) {
            kombiniert.add(praefix + name);
        }
        if (rest != null && rest.trim().length() >= 3) {
            for (String name : offlineSpielerVorschlaege(rest)) {
                kombiniert.add(praefix + name);
            }
        }
        return new ArrayList<>(kombiniert);
    }

    private List<String> onlineTreffer(String prefix, int limit) {
        String vergleich = prefix == null ? "" : prefix.trim().toLowerCase(Locale.ROOT);
        List<String> treffer = new ArrayList<>();
        if (verzeichnis == null) {
            return treffer;
        }
        try {
            for (OnlineSpieler online : verzeichnis.onlineSpieler()) {
                if (online == null) {
                    continue;
                }
                String name = online.name();
                if (name == null || name.isBlank()) {
                    continue;
                }
                if (!vergleich.isEmpty() && !name.toLowerCase(Locale.ROOT).startsWith(vergleich)) {
                    continue;
                }
                if (!treffer.contains(name)) {
                    treffer.add(name);
                }
                if (treffer.size() >= limit) {
                    break;
                }
            }
        } catch (Exception e) {
            LOG.debug("Online-Scan für Status-Tab-Complete übersprungen.", e);
        }
        treffer.sort(String.CASE_INSENSITIVE_ORDER);
        return treffer;
    }
}
