package de.lightrixx.punish.velocity;

import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.model.CachedPlayer;
import de.lightrixx.punish.net.Protokoll;
import de.lightrixx.punish.service.HistoryService;
import de.lightrixx.punish.service.ReportService;
import de.lightrixx.punish.storage.BanRepository;
import de.lightrixx.punish.storage.FreezeRepository;
import de.lightrixx.punish.storage.MuteRepository;
import de.lightrixx.punish.storage.NoteRepository;
import de.lightrixx.punish.storage.PlayerCache;
import de.lightrixx.punish.storage.TimeoutRepository;
import de.lightrixx.punish.storage.VerstossRepository;
import de.lightrixx.punish.storage.WarnRepository;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.Optional;

public final class PlatzhalterDienst {

    private final Logger logger;
    private final Konfiguration config;
    private final PlayerCache playerCache;
    private final BanRepository banRepository;
    private final MuteRepository muteRepository;
    private final TimeoutRepository timeoutRepository;
    private volatile FreezeRepository freezeRepository;
    private volatile NoteRepository noteRepository;
    private volatile HistoryService historyService;
    private final VerstossRepository verstossRepository;
    private final WarnRepository warnRepository;
    private final ReportService reportService;

    @SuppressWarnings("checkstyle:parameternumber")
    public PlatzhalterDienst(Logger logger, Konfiguration config, PlayerCache playerCache,
                             BanRepository banRepository, MuteRepository muteRepository,
                             TimeoutRepository timeoutRepository, VerstossRepository verstossRepository,
                             WarnRepository warnRepository, ReportService reportService) {
        this.logger = Objects.requireNonNull(logger, "Der Logger darf nicht null sein.");
        this.config = Objects.requireNonNull(config, "Die Konfiguration darf nicht null sein.");
        this.playerCache = Objects.requireNonNull(playerCache, "Der PlayerCache darf nicht null sein.");
        this.banRepository = Objects.requireNonNull(banRepository, "Das BanRepository darf nicht null sein.");
        this.muteRepository = Objects.requireNonNull(muteRepository, "Das MuteRepository darf nicht null sein.");
        this.timeoutRepository = Objects.requireNonNull(timeoutRepository,
                "Das TimeoutRepository darf nicht null sein.");
        this.verstossRepository = Objects.requireNonNull(verstossRepository,
                "Das VerstossRepository darf nicht null sein.");
        this.warnRepository = Objects.requireNonNull(warnRepository, "Das WarnRepository darf nicht null sein.");
        this.reportService = reportService;
    }

    public void setFreezeRepository(FreezeRepository freezeRepository) {
        this.freezeRepository = freezeRepository;
    }

    public void setNoteRepository(NoteRepository noteRepository) {
        this.noteRepository = noteRepository;
    }

    public void setHistoryService(HistoryService historyService) {
        this.historyService = historyService;
    }

    public Protokoll.PlatzhalterAntwort beantworte(Protokoll.PlatzhalterAnfrage anfrage) {
        if (anfrage == null) {
            return Protokoll.PlatzhalterAntwort.fehler(1L);
        }
        try {
            Optional<CachedPlayer> ziel = loeseZiel(anfrage.ziel());
            if (ziel.isEmpty()) {
                return Protokoll.PlatzhalterAntwort.leer(anfrage.requestId());
            }
            long jetzt = System.currentTimeMillis();
            var uuid = ziel.get().uuid();

            boolean muted = false;
            long muteAblauf = Protokoll.PlatzhalterAntwort.INAKTIV;
            String muteGrund = "";
            String muteVon = "";
            long muteSeit = 0L;
            try {
                for (var mute : muteRepository.alleFuer(uuid)) {
                    if (mute != null && mute.istAktiv(jetzt)) {
                        muted = true;
                        if (mute.ablaufAm() >= muteAblauf) {
                            muteAblauf = mute.ablaufAm();
                            muteGrund = text(mute.grund());
                            muteVon = text(mute.ausfuehrer());
                            muteSeit = mute.erstelltAm();
                        }
                    }
                }
            } catch (Exception e) {
                logger.debug("Platzhalter-Mutes fehlgeschlagen.", e);
            }

            boolean banned = false;
            long banAblauf = Protokoll.PlatzhalterAntwort.INAKTIV;
            String banGrund = "";
            String banVon = "";
            long banSeit = 0L;
            try {
                for (var bann : banRepository.alleFuer(uuid)) {
                    if (bann != null && bann.istAktiv(jetzt)) {
                        banned = true;
                        Long ablauf = bann.ablaufAm();
                        long wert = ablauf == null ? Long.MAX_VALUE : ablauf;
                        if (wert >= banAblauf) {
                            banAblauf = wert;
                            banGrund = text(bann.grund());
                            banVon = text(bann.ausfuehrer());
                            banSeit = bann.erstelltAm();
                        }
                    }
                }
            } catch (Exception e) {
                logger.debug("Platzhalter-Bans fehlgeschlagen.", e);
            }

            boolean timeouted = false;
            long timeoutAblauf = Protokoll.PlatzhalterAntwort.INAKTIV;
            String timeoutGrund = "";
            String timeoutVon = "";
            long timeoutSeit = 0L;
            try {
                for (var timeout : timeoutRepository.alleFuer(uuid)) {
                    if (timeout != null && timeout.istAktiv(jetzt)) {
                        timeouted = true;
                        if (timeout.ablaufAm() >= timeoutAblauf) {
                            timeoutAblauf = timeout.ablaufAm();
                            timeoutGrund = text(timeout.grund());
                            timeoutVon = text(timeout.ausfuehrer());
                            timeoutSeit = timeout.erstelltAm();
                        }
                    }
                }
            } catch (Exception e) {
                logger.debug("Platzhalter-Timeouts fehlgeschlagen.", e);
            }

            boolean frozen = false;
            long freezeAblauf = Protokoll.PlatzhalterAntwort.INAKTIV;
            String freezeGrund = "";
            String freezeVon = "";
            long freezeSeit = 0L;
            try {
                FreezeRepository freezes = freezeRepository;
                if (freezes != null) {
                    for (var freeze : freezes.alleFuer(uuid)) {
                        if (freeze != null && freeze.istAktiv(jetzt)) {
                            frozen = true;
                            if (freeze.ablaufAm() >= freezeAblauf) {
                                freezeAblauf = freeze.ablaufAm();
                                freezeGrund = text(freeze.grund());
                                freezeVon = text(freeze.ausfuehrer());
                                freezeSeit = freeze.erstelltAm();
                            }
                        }
                    }
                }
            } catch (Exception e) {
                logger.debug("Platzhalter-Freezes fehlgeschlagen.", e);
            }

            int verstossAnzahl = 0;
            int verstossPunkte = 0;
            String verstossStufe = "";
            try {
                var verstoesse = verstossRepository.finden(uuid);
                if (verstoesse != null) {
                    var wirksam = verstoesse.stream().filter(Objects::nonNull)
                            .filter(v -> !istVerfallen(v, jetzt))
                            .toList();
                    verstossAnzahl = wirksam.size();
                    int maxPunkte = -1;
                    for (var verstoss : wirksam) {
                        int punkte = punkteFuer(verstoss);
                        verstossPunkte += punkte;
                        if (punkte > maxPunkte && verstoss.stufe() != null) {
                            maxPunkte = punkte;
                            verstossStufe = verstoss.stufe().schluessel();
                        }
                    }
                }
            } catch (Exception e) {
                logger.debug("Platzhalter-Verstöße fehlgeschlagen.", e);
            }

            int warns = 0;
            String warnsLetzterGrund = "";
            try {
                warns = warnRepository.anzahl(uuid);
                int maxNummer = -1;
                for (var warn : warnRepository.finden(uuid)) {
                    if (warn != null && warn.nummer() > maxNummer) {
                        maxNummer = warn.nummer();
                        warnsLetzterGrund = text(warn.grund());
                    }
                }
            } catch (Exception e) {
                logger.debug("Platzhalter-Warns fehlgeschlagen.", e);
            }

            int notizen = 0;
            try {
                NoteRepository notizenRepo = noteRepository;
                if (notizenRepo != null) {
                    notizen = notizenRepo.anzahl(uuid);
                }
            } catch (Exception e) {
                logger.debug("Platzhalter-Notizen fehlgeschlagen.", e);
            }

            int historyAnzahl = 0;
            try {
                HistoryService verlaufDienst = historyService;
                if (verlaufDienst != null) {
                    var verlauf = verlaufDienst.findeSpielerHistory(uuid, 500);
                    if (verlauf != null) {
                        historyAnzahl = verlauf.size();
                    }
                }
            } catch (Exception e) {
                logger.debug("Platzhalter-History fehlgeschlagen.", e);
            }

            int reportsGegen = 0;
            int reportsOffen = 0;
            try {
                if (reportService != null) {
                    reportsOffen = reportService.anzahlOffen();
                    for (var report : reportService.offene()) {
                        if (report != null && uuid.equals(report.zielUuid())) {
                            reportsGegen++;
                        }
                    }
                }
            } catch (Exception e) {
                logger.debug("Platzhalter-Reports fehlgeschlagen.", e);
            }

            return new Protokoll.PlatzhalterAntwort(anfrage.requestId(), true,
                    muted, muteAblauf, banned, banAblauf, timeouted, timeoutAblauf,
                    frozen, freezeAblauf,
                    verstossAnzahl, verstossPunkte, warns, reportsGegen, reportsOffen,
                    muteGrund, muteVon, muteSeit, banGrund, banVon, banSeit,
                    timeoutGrund, timeoutVon, timeoutSeit, freezeGrund, freezeVon, freezeSeit,
                    notizen, historyAnzahl, warnsLetzterGrund, verstossStufe);
        } catch (Exception e) {
            logger.debug("Platzhalter-Anfrage fehlgeschlagen.", e);
            return Protokoll.PlatzhalterAntwort.fehler(anfrage.requestId());
        }
    }

    private static String text(String wert) {
        return wert == null ? "" : wert;
    }

    private int punkteFuer(de.lightrixx.punish.model.VerstossRecord verstoss) {
        if (verstoss == null || verstoss.stufe() == null) {
            return 0;
        }
        try {
            return Math.max(0, config.verstossStufenPunkte(verstoss.stufe()));
        } catch (Exception e) {
            return verstoss.stufe().standardPunkte();
        }
    }

    private boolean istVerfallen(de.lightrixx.punish.model.VerstossRecord verstoss, long jetzt) {
        if (verstoss == null || verstoss.stufe() == null) {
            return false;
        }
        long dauer;
        try {
            dauer = config.verstossStufenDauerMillis(verstoss.stufe());
        } catch (Exception e) {
            return false;
        }
        if (dauer <= 0) {
            return false;
        }
        try {
            return Math.addExact(verstoss.erstelltAm(), dauer) <= jetzt;
        } catch (ArithmeticException e) {
            return false;
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
            logger.debug("Platzhalter-Ziel Cache-Lookup fehlgeschlagen.", e);
        }
        try {
            Optional<CachedPlayer> extern = playerCache.findeExternOffline(name);
            if (extern.isPresent()) {
                return extern;
            }
        } catch (Exception e) {
            logger.debug("Platzhalter-Ziel Extern-Lookup fehlgeschlagen.", e);
        }
        return Optional.empty();
    }
}
