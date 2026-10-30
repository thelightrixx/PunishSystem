package de.lightrixx.punish.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.model.CachedPlayer;
import de.lightrixx.punish.model.HistoryEntry;
import de.lightrixx.punish.model.ReportRecord;
import de.lightrixx.punish.net.Protokoll;
import de.lightrixx.punish.service.HistoryService;
import de.lightrixx.punish.service.ReportService;
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
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class GuiBrueckenDienst {

    private static final int SEITEN_GROESSE = 45;

    private final ProxyServer proxy;
    private final Logger logger;
    private final Konfiguration config;
    private final PlayerCache playerCache;
    private final BanRepository banRepository;
    private final MuteRepository muteRepository;
    private final TimeoutRepository timeoutRepository;
    private volatile FreezeRepository freezeRepository;
    private final VerstossRepository verstossRepository;
    private final WarnRepository warnRepository;
    private final HistoryService historyService;
    private final ReportService reportService;
    private final de.lightrixx.punish.service.ProtectionService protectionService;

    @SuppressWarnings("checkstyle:parameternumber")
    public GuiBrueckenDienst(ProxyServer proxy, Logger logger, Konfiguration config,
                             PlayerCache playerCache,
                             BanRepository banRepository, MuteRepository muteRepository,
                             TimeoutRepository timeoutRepository, IpBanRepository ipBanRepository,
                             IpMuteRepository ipMuteRepository, IpTimeoutRepository ipTimeoutRepository,
                             VerstossRepository verstossRepository, WarnRepository warnRepository,
                             HistoryService historyService, ReportService reportService) {
        this(proxy, logger, config, playerCache, banRepository, muteRepository, timeoutRepository,
                ipBanRepository, ipMuteRepository, ipTimeoutRepository, verstossRepository, warnRepository,
                historyService, reportService, null);
    }

    @SuppressWarnings("checkstyle:parameternumber")
    public GuiBrueckenDienst(ProxyServer proxy, Logger logger, Konfiguration config,
                             PlayerCache playerCache,
                             BanRepository banRepository, MuteRepository muteRepository,
                             TimeoutRepository timeoutRepository, IpBanRepository ipBanRepository,
                             IpMuteRepository ipMuteRepository, IpTimeoutRepository ipTimeoutRepository,
                             VerstossRepository verstossRepository, WarnRepository warnRepository,
                             HistoryService historyService, ReportService reportService,
                             de.lightrixx.punish.service.ProtectionService protectionService) {
        this.proxy = Objects.requireNonNull(proxy, "Der Proxy darf nicht null sein.");
        this.logger = Objects.requireNonNull(logger, "Der Logger darf nicht null sein.");
        this.config = Objects.requireNonNull(config, "Die Konfiguration darf nicht null sein.");
        this.playerCache = Objects.requireNonNull(playerCache, "Der PlayerCache darf nicht null sein.");
        this.banRepository = Objects.requireNonNull(banRepository, "Das BanRepository darf nicht null sein.");
        this.muteRepository = Objects.requireNonNull(muteRepository, "Das MuteRepository darf nicht null sein.");
        this.timeoutRepository = Objects.requireNonNull(timeoutRepository, "Das TimeoutRepository darf nicht null sein.");
        this.verstossRepository = verstossRepository;
        this.warnRepository = warnRepository;
        this.historyService = historyService;
        this.reportService = reportService;
        this.protectionService = protectionService;
    }

    public void setFreezeRepository(FreezeRepository freezeRepository) {
        this.freezeRepository = freezeRepository;
    }

    public Protokoll.GuiAntwort beantworte(Protokoll.GuiAnfrage anfrage) {
        if (anfrage == null) {
            return new Protokoll.GuiAntwort(1L, false, "Leere Anfrage.", 1, 1, 0, List.of());
        }
        try {
            return switch (anfrage.typ()) {
                case Protokoll.GUI_STATUS -> status(anfrage);
                case Protokoll.GUI_HISTORY -> history(anfrage);
                case Protokoll.GUI_REPORTS -> reports(anfrage);
                case Protokoll.GUI_REPORT_INFO -> reportInfo(anfrage);
                case Protokoll.GUI_KATALOG -> katalog(anfrage);
                case Protokoll.GUI_DOSSIER -> dossier(anfrage);
                default -> new Protokoll.GuiAntwort(anfrage.requestId(), false,
                        "Unbekannter GUI-Typ: " + anfrage.typ(), 1, 1, 0, List.of());
            };
        } catch (Exception e) {
            logger.warn("GUI-Anfrage {} fehlgeschlagen.", anfrage.typ(), e);
            return new Protokoll.GuiAntwort(anfrage.requestId(), false,
                    "Fehler auf dem Proxy.", 1, 1, 0, List.of());
        }
    }

    private Optional<CachedPlayer> loeseZiel(String ziel) {
        if (ziel == null || ziel.isBlank()) {
            return Optional.empty();
        }
        String name = ziel.trim();
        try {
            Optional<CachedPlayer> direkt = playerCache.findenNachName(name);
            if (direkt.isPresent()) {
                return direkt;
            }
        } catch (Exception e) {
            logger.debug("GUI-Ziel Cache-Lookup fehlgeschlagen.", e);
        }
        try {
            Optional<CachedPlayer> extern = playerCache.findeExternOffline(name);
            if (extern.isPresent()) {
                return extern;
            }
        } catch (Exception e) {
            logger.debug("GUI-Ziel Extern-Lookup fehlgeschlagen.", e);
        }
        return Optional.empty();
    }

    private boolean istGeschuetzt(UUID zielUuid) {
        if (protectionService == null || zielUuid == null) {
            return false;
        }
        try {
            return protectionService.istGeschuetzt(zielUuid);
        } catch (Exception e) {
            return protectionService.istFailClosed();
        }
    }

    private Protokoll.GuiAntwort status(Protokoll.GuiAnfrage anfrage) {
        Optional<CachedPlayer> ziel = loeseZiel(anfrage.ziel());
        if (ziel.isEmpty()) {
            return new Protokoll.GuiAntwort(anfrage.requestId(), false,
                    "Spieler nicht gefunden: " + anfrage.ziel(), 1, 1, 0, List.of());
        }
        if (istGeschuetzt(ziel.get().uuid())) {
            return new Protokoll.GuiAntwort(anfrage.requestId(), false,
                    "Dieser Spieler ist geschützt.", 1, 1, 0, List.of());
        }
        UUID uuid = ziel.get().uuid();
        String name = ziel.get().name();
        long jetzt = System.currentTimeMillis();
        List<Protokoll.GuiZeile> zeilen = new ArrayList<>();

        try {
            for (var bann : banRepository.alleFuer(uuid)) {
                if (bann != null && bann.istAktiv(jetzt)) {
                    zeilen.add(new Protokoll.GuiZeile("BAN",
                            "Bann [" + bann.scope().anzeige() + "]",
                            "Grund: " + bann.grund() + "\nVon: " + bann.ausfuehrer()
                                    + "\nAblauf: " + formatAblauf(bann.ablaufAm()),
                            "pardon " + name, name));
                }
            }
        } catch (Exception e) {
            logger.debug("GUI-Status Bans fehlgeschlagen.", e);
        }
        try {
            for (var mute : muteRepository.alleFuer(uuid)) {
                if (mute != null && mute.istAktiv(jetzt)) {
                    zeilen.add(new Protokoll.GuiZeile("MUTE",
                            "Mute [" + mute.scope().anzeige() + "]",
                            "Grund: " + mute.grund() + "\nVon: " + mute.ausfuehrer()
                                    + "\nAblauf: " + formatAblauf(mute.ablaufAm()),
                            "unmute " + name, name));
                }
            }
        } catch (Exception e) {
            logger.debug("GUI-Status Mutes fehlgeschlagen.", e);
        }
        try {
            for (var timeout : timeoutRepository.alleFuer(uuid)) {
                if (timeout != null && timeout.istAktiv(jetzt)) {
                    zeilen.add(new Protokoll.GuiZeile("TIMEOUT",
                            "Timeout [" + timeout.scope().anzeige() + "]",
                            "Grund: " + timeout.grund() + "\nVon: " + timeout.ausfuehrer()
                                    + "\nAblauf: " + formatAblauf(timeout.ablaufAm()),
                            "untimeout " + name, name));
                }
            }
        } catch (Exception e) {
            logger.debug("GUI-Status Timeouts fehlgeschlagen.", e);
        }
        try {
            FreezeRepository freezes = freezeRepository;
            if (freezes != null) {
                for (var freeze : freezes.alleFuer(uuid)) {
                    if (freeze != null && freeze.istAktiv(jetzt)) {
                        zeilen.add(new Protokoll.GuiZeile("FREEZE",
                                "Freeze [" + freeze.scope().anzeige() + "]",
                                "Grund: " + freeze.grund() + "\nVon: " + freeze.ausfuehrer()
                                        + "\nAblauf: " + (freeze.istPermanent() ? "Permanent"
                                                : formatAblauf(freeze.ablaufAm())),
                                "unfreeze " + name, name));
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("GUI-Status Freezes fehlgeschlagen.", e);
        }
        try {
            if (verstossRepository != null) {
                for (var verstoss : verstossRepository.finden(uuid)) {
                    if (verstoss != null) {
                        zeilen.add(new Protokoll.GuiZeile("VERSTOSS",
                                "Verstoß #" + verstoss.nummer() + " [" + verstoss.stufe().schluessel() + "]",
                                "Grund: " + verstoss.grund() + "\nVon: " + verstoss.ausfuehrer()
                                        + "\nAm: " + formatZeitpunkt(verstoss.erstelltAm()),
                                "verstoss remove " + name + " " + verstoss.nummer(), name));
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("GUI-Status Verstöße fehlgeschlagen.", e);
        }
        try {
            if (warnRepository != null) {
                for (var warn : warnRepository.finden(uuid)) {
                    if (warn != null) {
                        zeilen.add(new Protokoll.GuiZeile("WARN",
                                "Warnung #" + warn.nummer(),
                                "Grund: " + warn.grund() + "\nVon: " + warn.ausfuehrer()
                                        + "\nAm: " + formatZeitpunkt(warn.erstelltAm()),
                                "warn list " + name, name));
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("GUI-Status Warns fehlgeschlagen.", e);
        }
        zeilen.sort(Comparator.comparing(
                        (Protokoll.GuiZeile z) -> Objects.requireNonNull(z, "GuiZeile").typ())
                .thenComparing((Protokoll.GuiZeile z) -> Objects.requireNonNull(z, "GuiZeile").titel()));
        return paginieren(anfrage, zeilen, "Status für " + name);
    }

    private Protokoll.GuiAntwort history(Protokoll.GuiAnfrage anfrage) {
        if (historyService == null) {
            return new Protokoll.GuiAntwort(anfrage.requestId(), false,
                    "History-Service fehlt.", 1, 1, 0, List.of());
        }
        Optional<CachedPlayer> ziel = loeseZiel(anfrage.ziel());
        if (ziel.isEmpty()) {
            return new Protokoll.GuiAntwort(anfrage.requestId(), false,
                    "Spieler nicht gefunden: " + anfrage.ziel(), 1, 1, 0, List.of());
        }
        if (istGeschuetzt(ziel.get().uuid())) {
            return new Protokoll.GuiAntwort(anfrage.requestId(), false,
                    "Dieser Spieler ist geschützt.", 1, 1, 0, List.of());
        }
        List<HistoryEntry> verlauf;
        try {
            verlauf = historyService.findeSpielerHistory(ziel.get().uuid(), 500);
        } catch (Exception e) {
            return new Protokoll.GuiAntwort(anfrage.requestId(), false,
                    "History konnte nicht geladen werden.", 1, 1, 0, List.of());
        }
        List<Protokoll.GuiZeile> zeilen = new ArrayList<>();
        String name = ziel.get().name();
        for (HistoryEntry eintrag : verlauf) {
            if (eintrag == null) {
                continue;
            }
            String aktion = klickFuerHistory(eintrag);
            zeilen.add(new Protokoll.GuiZeile("HISTORY",
                    eintrag.typ().anzeigename() + " [" + eintrag.scope() + "]",
                    "Ziel: " + eintrag.ziel() + "\nGrund: " + eintrag.grund()
                            + "\nVon: " + eintrag.ausfuehrer() + "\nAm: " + formatZeitpunkt(eintrag.zeitstempel()),
                    aktion == null ? "" : aktion, name));
        }
        return paginieren(anfrage, zeilen, "History für " + name);
    }

    private String klickFuerHistory(HistoryEntry eintrag) {
        String ziel = eintrag.ziel();
        if (ziel == null || ziel.isBlank()) {
            return null;
        }
        return switch (eintrag.typ()) {
            case BAN, BAN_EDIT, PERMABANN, AUTO_BANN -> "pardon " + ziel;
            case MUTE, MUTE_EDIT -> "unmute " + ziel;
            case TIMEOUT, TIMEOUT_EDIT -> "untimeout " + ziel;
            case FREEZE -> "unfreeze " + ziel;
            case BAN_IP, IP_BAN_EDIT -> "unban-ip " + ziel;
            case IP_MUTE, IP_MUTE_EDIT -> "unmute-ip " + ziel;
            case IP_TIMEOUT, IP_TIMEOUT_EDIT -> "untimeout-ip " + ziel;
            default -> null;
        };
    }

    private Protokoll.GuiAntwort reports(Protokoll.GuiAnfrage anfrage) {
        if (reportService == null) {
            return new Protokoll.GuiAntwort(anfrage.requestId(), false,
                    "Report-Service fehlt.", 1, 1, 0, List.of());
        }
        boolean nurOffen = !"alle".equalsIgnoreCase(anfrage.extra())
                && !"all".equalsIgnoreCase(anfrage.extra())
                && !"all".equalsIgnoreCase(anfrage.filter());
        List<ReportRecord> liste;
        try {
            liste = nurOffen ? reportService.offene() : reportService.alle();
        } catch (Exception e) {
            return new Protokoll.GuiAntwort(anfrage.requestId(), false,
                    "Reports konnten nicht geladen werden.", 1, 1, 0, List.of());
        }
        List<Protokoll.GuiZeile> zeilen = new ArrayList<>();
        for (ReportRecord report : liste) {
            if (report == null) {
                continue;
            }
            zeilen.add(new Protokoll.GuiZeile("REPORT",
                    "#" + report.id() + " " + report.zielName() + " (" + report.status().name() + ")",
                    "Grund: " + report.grund() + "\nVon: " + report.reporterName()
                            + "\nServer: " + report.server() + "\nAm: " + formatZeitpunkt(report.erstelltAm()),
                    "reports info " + report.id(), String.valueOf(report.id())));
        }
        return paginieren(anfrage, zeilen, nurOffen ? "Offene Reports" : "Alle Reports");
    }

    private Protokoll.GuiAntwort reportInfo(Protokoll.GuiAnfrage anfrage) {
        if (reportService == null) {
            return new Protokoll.GuiAntwort(anfrage.requestId(), false,
                    "Report-Service fehlt.", 1, 1, 0, List.of());
        }
        long id;
        try {
            String roh = anfrage.extra().trim();
            if (roh.startsWith("#")) {
                roh = roh.substring(1);
            }
            id = Long.parseLong(roh);
        } catch (Exception e) {
            return new Protokoll.GuiAntwort(anfrage.requestId(), false,
                    "Ungültige Report-ID: " + anfrage.extra(), 1, 1, 0, List.of());
        }
        Optional<ReportRecord> gefunden;
        try {
            gefunden = reportService.findenNachId(id);
        } catch (Exception e) {
            return new Protokoll.GuiAntwort(anfrage.requestId(), false,
                    "Report konnte nicht geladen werden.", 1, 1, 0, List.of());
        }
        if (gefunden.isEmpty()) {
            return new Protokoll.GuiAntwort(anfrage.requestId(), false,
                    "Report #" + id + " existiert nicht.", 1, 1, 0, List.of());
        }
        ReportRecord report = gefunden.get();
        List<Protokoll.GuiZeile> zeilen = List.of(
                new Protokoll.GuiZeile("REPORT",
                        "#" + report.id() + " " + report.zielName(),
                        "Grund: " + report.grund() + "\nVon: " + report.reporterName()
                                + "\nServer: " + report.server() + "\nStatus: " + report.status().name(),
                        "", String.valueOf(report.id())),
                new Protokoll.GuiZeile("REPORT_AKTION", "Annehmen",
                        "Report #" + report.id() + " annehmen.", "reports accept " + report.id(),
                        String.valueOf(report.id())),
                new Protokoll.GuiZeile("REPORT_AKTION", "Ablehnen",
                        "Report #" + report.id() + " ablehnen.", "reports deny " + report.id(),
                        String.valueOf(report.id())),
                new Protokoll.GuiZeile("REPORT_AKTION", "Erledigt",
                        "Report #" + report.id() + " schließen.", "reports close " + report.id(),
                        String.valueOf(report.id())));
        return new Protokoll.GuiAntwort(anfrage.requestId(), true, "Report #" + id, 1, 1, zeilen.size(), zeilen);
    }

    private Protokoll.GuiAntwort katalog(Protokoll.GuiAnfrage anfrage) {
        List<Protokoll.GuiZeile> zeilen = new ArrayList<>();
        String befehl = anfrage.extra() == null ? "" : anfrage.extra().trim();
        boolean gefiltert = !befehl.isBlank();
        try {
            for (String grund : gefiltert ? config.grundListe(befehl) : config.tabGrundListe()) {
                if (grund != null && !grund.isBlank()) {
                    zeilen.add(new Protokoll.GuiZeile("GRUND", grund.trim(), "", "", ""));
                }
            }
        } catch (Exception e) {
            logger.debug("Katalog-Gründe fehlgeschlagen.", e);
        }
        try {
            for (String dauer : gefiltert ? config.dauerListe(befehl) : config.tabDauerListe()) {
                if (dauer != null && !dauer.isBlank()) {
                    zeilen.add(new Protokoll.GuiZeile("DAUER", dauer.trim(), "", "", ""));
                }
            }
        } catch (Exception e) {
            logger.debug("Katalog-Dauern fehlgeschlagen.", e);
        }
        if (gefiltert) {
            try {
                for (var vorlage : config.vorlagenFuer(befehl).entrySet()) {
                    if (vorlage.getKey() != null && !vorlage.getKey().isBlank()
                            && vorlage.getValue() != null && !vorlage.getValue().isBlank()) {
                        zeilen.add(new Protokoll.GuiZeile("VORLAGE",
                                vorlage.getKey().trim(), vorlage.getValue().trim(), "", ""));
                    }
                }
            } catch (Exception e) {
                logger.debug("Katalog-Vorlagen fehlgeschlagen.", e);
            }
        }
        try {
            List<String> server = new ArrayList<>(config.bekannteServer());
            for (var registered : proxy.getAllServers()) {
                try {
                    String name = registered.getServerInfo().getName();
                    if (name != null && !name.isBlank() && !server.contains(name)) {
                        server.add(name);
                    }
                } catch (Exception e) {
                    logger.debug("Katalog Servername-Lookup fehlgeschlagen.", e);
                }
            }
            server.sort(String.CASE_INSENSITIVE_ORDER);
            for (String s : server) {
                zeilen.add(new Protokoll.GuiZeile("SERVER", s, "", "", ""));
            }
        } catch (Exception e) {
            logger.debug("Katalog-Server fehlgeschlagen.", e);
        }
        return katalogSeite(anfrage, zeilen);
    }

    static Protokoll.GuiAntwort katalogSeite(Protokoll.GuiAnfrage anfrage,
                                             java.util.List<Protokoll.GuiZeile> alle) {
        java.util.List<Protokoll.GuiZeile> quelle = alle == null ? java.util.List.of() : alle;
        int proSeite = Protokoll.MAX_GUI_ZEILEN;
        int gesamt = quelle.size();
        int seiten = Math.max(1, (gesamt + proSeite - 1) / proSeite);
        int seite = Math.min(Math.max(1, anfrage.seite()), seiten);
        int von = (seite - 1) * proSeite;
        int bis = Math.min(von + proSeite, gesamt);
        java.util.List<Protokoll.GuiZeile> ausschnitt =
                gesamt == 0 ? java.util.List.of() : java.util.List.copyOf(quelle.subList(von, bis));
        return new Protokoll.GuiAntwort(anfrage.requestId(), true, "Katalog",
                seite, seiten, gesamt, ausschnitt);
    }

    private Protokoll.GuiAntwort dossier(Protokoll.GuiAnfrage anfrage) {
        Optional<CachedPlayer> ziel = loeseZiel(anfrage.ziel());
        if (ziel.isEmpty()) {
            return new Protokoll.GuiAntwort(anfrage.requestId(), false,
                    "Spieler nicht gefunden: " + anfrage.ziel(), 1, 1, 0, List.of());
        }
        if (istGeschuetzt(ziel.get().uuid())) {
            return new Protokoll.GuiAntwort(anfrage.requestId(), false,
                    "Dieser Spieler ist geschützt.", 1, 1, 0, List.of());
        }
        UUID uuid = ziel.get().uuid();
        String name = ziel.get().name();
        long jetzt = System.currentTimeMillis();
        List<Protokoll.GuiZeile> zeilen = new ArrayList<>();

        int banns = 0;
        int mutes = 0;
        int timeouts = 0;
        int freezes = 0;
        int verstoesse = 0;
        int warns = 0;
        long warnAblauf = 0L;
        try {
            warnAblauf = Math.max(0L, config.warnAblaufMillis());
        } catch (Exception e) {
            logger.debug("Warn-Ablauf für Dossier nicht lesbar.", e);
        }
        try {
            for (var bann : banRepository.alleFuer(uuid)) {
                if (bann != null && bann.istAktiv(jetzt)) {
                    banns++;
                    zeilen.add(new Protokoll.GuiZeile("BAN",
                            "Bann [" + bann.scope().anzeige() + "]",
                            "Grund: " + bann.grund() + "\nVon: " + bann.ausfuehrer()
                                    + "\nAblauf: " + formatAblauf(bann.ablaufAm()),
                            "pardon " + name, name));
                }
            }
        } catch (Exception e) {
            logger.debug("Dossier-Bans fehlgeschlagen.", e);
        }
        try {
            for (var mute : muteRepository.alleFuer(uuid)) {
                if (mute != null && mute.istAktiv(jetzt)) {
                    mutes++;
                    zeilen.add(new Protokoll.GuiZeile("MUTE",
                            "Mute [" + mute.scope().anzeige() + "]",
                            "Grund: " + mute.grund() + "\nVon: " + mute.ausfuehrer()
                                    + "\nAblauf: " + formatAblauf(mute.ablaufAm()),
                            "unmute " + name, name));
                }
            }
        } catch (Exception e) {
            logger.debug("Dossier-Mutes fehlgeschlagen.", e);
        }
        try {
            for (var timeout : timeoutRepository.alleFuer(uuid)) {
                if (timeout != null && timeout.istAktiv(jetzt)) {
                    timeouts++;
                    zeilen.add(new Protokoll.GuiZeile("TIMEOUT",
                            "Timeout [" + timeout.scope().anzeige() + "]",
                            "Grund: " + timeout.grund() + "\nVon: " + timeout.ausfuehrer()
                                    + "\nAblauf: " + formatAblauf(timeout.ablaufAm()),
                            "untimeout " + name, name));
                }
            }
        } catch (Exception e) {
            logger.debug("Dossier-Timeouts fehlgeschlagen.", e);
        }
        try {
            FreezeRepository freezesRepo = freezeRepository;
            if (freezesRepo != null) {
                for (var freeze : freezesRepo.alleFuer(uuid)) {
                    if (freeze != null && freeze.istAktiv(jetzt)) {
                        freezes++;
                        zeilen.add(new Protokoll.GuiZeile("FREEZE",
                                "Freeze [" + freeze.scope().anzeige() + "]",
                                "Grund: " + freeze.grund() + "\nVon: " + freeze.ausfuehrer()
                                        + "\nAblauf: " + (freeze.istPermanent() ? "Permanent"
                                                : formatAblauf(freeze.ablaufAm())),
                                "unfreeze " + name, name));
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Dossier-Freezes fehlgeschlagen.", e);
        }
        try {
            if (verstossRepository != null) {
                for (var verstoss : verstossRepository.finden(uuid)) {
                    if (verstoss != null) {
                        verstoesse++;
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Dossier-Verstöße fehlgeschlagen.", e);
        }
        try {
            if (warnRepository != null) {
                for (var warn : warnRepository.finden(uuid)) {
                    if (warn == null) {
                        continue;
                    }
                    boolean abgelaufen = warnAblauf > 0L
                            && warn.erstelltAm() + warnAblauf <= jetzt;
                    if (!abgelaufen) {
                        warns++;
                    }
                    zeilen.add(new Protokoll.GuiZeile("WARN",
                            "Warnung #" + warn.nummer() + (abgelaufen ? " (abgelaufen)" : ""),
                            "Grund: " + warn.grund() + "\nVon: " + warn.ausfuehrer()
                                    + "\nAm: " + formatZeitpunkt(warn.erstelltAm()),
                            "warn list " + name, name));
                }
            }
        } catch (Exception e) {
            logger.debug("Dossier-Warns fehlgeschlagen.", e);
        }

        int offeneReports = 0;
        try {
            if (reportService != null) {
                for (ReportRecord report : reportService.alle()) {
                    if (report == null || !uuid.equals(report.zielUuid()) || !report.istOffen()) {
                        continue;
                    }
                    offeneReports++;
                    zeilen.add(new Protokoll.GuiZeile("REPORT",
                            "Report #" + report.id() + " (" + report.status().name() + ")",
                            "Von: " + report.reporterName() + "\nGrund: " + report.grund()
                                    + "\nServer: " + report.server()
                                    + "\nAm: " + formatZeitpunkt(report.erstelltAm()),
                            "reports info " + report.id(), name));
                }
            }
        } catch (Exception e) {
            logger.debug("Dossier-Reports fehlgeschlagen.", e);
        }

        List<Protokoll.GuiZeile> kopf = new ArrayList<>();
        kopf.add(new Protokoll.GuiZeile("DOSSIER_STATUS", "Spielerakte " + name,
                "UUID: " + uuid
                        + "\nAktiv: Bans " + banns + ", Mutes " + mutes
                        + ", Timeouts " + timeouts + ", Freezes " + freezes
                        + "\nVerstöße: " + verstoesse + " | Warns: " + warns
                        + " | Offene Reports: " + offeneReports,
                "", name));
        kopf.add(new Protokoll.GuiZeile("DOSSIER_AKTION", "Bannen",
                "Öffnet die GUI-Auswahl für einen Bann.", "gui:ban", name));
        kopf.add(new Protokoll.GuiZeile("DOSSIER_AKTION", "Muten",
                "Öffnet die GUI-Auswahl für einen Mute.", "gui:mute", name));
        kopf.add(new Protokoll.GuiZeile("DOSSIER_AKTION", "Verwarnen",
                "Öffnet die GUI-Auswahl für eine Verwarnung.", "gui:warn", name));
        kopf.add(new Protokoll.GuiZeile("DOSSIER_AKTION", "Kicken",
                "Öffnet die GUI-Auswahl für einen Kick.", "gui:kick", name));
        kopf.add(new Protokoll.GuiZeile("DOSSIER_AKTION", "Strafen",
                "Zeigt alle aktiven Strafen.", "punish-status " + name, name));
        kopf.add(new Protokoll.GuiZeile("DOSSIER_AKTION", "Verlauf",
                "Zeigt /punish-history im Chat.", "punish-history " + name, name));
        kopf.add(new Protokoll.GuiZeile("DOSSIER_AKTION", "Notizen",
                "Zeigt Notizen im Chat.", "punish-note list " + name, name));
        kopf.add(new Protokoll.GuiZeile("DOSSIER_AKTION", "Reports",
                "Öffnet die offene Report-Liste.", "reports", name));

        try {
            if (historyService != null) {
                List<HistoryEntry> verlauf = historyService.findeSpielerHistory(uuid, 10);
                for (HistoryEntry eintrag : verlauf) {
                    if (eintrag == null) {
                        continue;
                    }
                    String aktion = klickFuerHistory(eintrag);
                    zeilen.add(new Protokoll.GuiZeile("HISTORY",
                            eintrag.typ().anzeigename() + " [" + eintrag.scope() + "]",
                            "Grund: " + eintrag.grund() + "\nVon: " + eintrag.ausfuehrer()
                                    + "\nAm: " + formatZeitpunkt(eintrag.zeitstempel()),
                            aktion == null ? "" : aktion, name));
                }
            }
        } catch (Exception e) {
            logger.debug("Dossier-History fehlgeschlagen.", e);
        }

        List<Protokoll.GuiZeile> alle = new ArrayList<>(kopf);
        alle.addAll(zeilen);
        return paginieren(anfrage, alle, "Dossier für " + name);
    }

    private Protokoll.GuiAntwort paginieren(Protokoll.GuiAnfrage anfrage, List<Protokoll.GuiZeile> alle, String kopf) {
        int seite = Math.max(1, anfrage.seite());
        int seiten = Math.max(1, (alle.size() + SEITEN_GROESSE - 1) / SEITEN_GROESSE);
        seite = Math.min(seite, seiten);
        int von = (seite - 1) * SEITEN_GROESSE;
        int bis = Math.min(von + SEITEN_GROESSE, alle.size());
        List<Protokoll.GuiZeile> teil = von < bis ? alle.subList(von, bis) : List.of();
        return new Protokoll.GuiAntwort(anfrage.requestId(), true, kopf, seite, seiten, alle.size(), List.copyOf(teil));
    }

    private static String formatZeitpunkt(long zeitstempel) {
        try {
            return ZeitFormat.formatZeitpunkt(zeitstempel);
        } catch (Exception e) {
            return String.valueOf(zeitstempel);
        }
    }

    private static String formatAblauf(Long ablaufAm) {
        if (ablaufAm == null) {
            return "Permanent";
        }
        try {
            return ZeitFormat.formatZeitpunkt(ablaufAm);
        } catch (Exception e) {
            return String.valueOf(ablaufAm);
        }
    }
}
