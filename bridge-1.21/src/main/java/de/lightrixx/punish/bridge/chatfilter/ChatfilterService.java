package de.lightrixx.punish.bridge.chatfilter;

import de.lightrixx.punish.bridge.chatfilter.ChatfilterKonfiguration.Modus;
import de.lightrixx.punish.chatfilter.ChatfilterAusfall;
import de.lightrixx.punish.chatfilter.FilterKanal;
import de.lightrixx.punish.chatfilter.FilterTreffer;
import de.lightrixx.punish.chatfilter.TextHash;
import de.lightrixx.punish.chatfilter.ToxizitaetsModell;
import de.lightrixx.punish.chatfilter.TrefferQuelle;
import de.lightrixx.punish.chatfilter.WortlistenFilter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ChatfilterService implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ChatfilterService.class);

    private static final int MAX_CACHE = 1000;
    private static final int MAX_AUSSTEHEND = 50;
    private static final long KI_PAUSE_MILLIS = 5L * 60L * 1000L;

    static volatile java.util.function.BooleanSupplier serverThreadPruefer =
            ChatfilterService::istServerThread;

    public interface TrefferMelder {
        void melde(UUID uuid, String name, String text, FilterTreffer treffer);
    }

    private final ChatfilterKonfiguration konfiguration;
    private volatile WortlistenFilter wortliste;
    private final ToxizitaetsModell modell;
    private final TrefferMelder melder;
    private final ExecutorService kiPool;
    private final AtomicInteger ausstehend = new AtomicInteger();
    private final AtomicInteger kiFehlerFolge = new AtomicInteger();
    private static final int KI_DEFEKT_SCHWELLE = 10;
    private volatile long kiDefektBis;
    private volatile Boolean filterAktivOverride;
    private volatile Boolean kiAktivOverride;
    private final Map<String, Double> bewertungsCache = Collections.synchronizedMap(
            new LinkedHashMap<>(MAX_CACHE + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Double> eldest) {
                    return size() > MAX_CACHE;
                }
            });

    public ChatfilterService(ChatfilterKonfiguration konfiguration, WortlistenFilter wortliste,
                             ToxizitaetsModell modell, TrefferMelder melder) {
        this.konfiguration = Objects.requireNonNull(konfiguration, "Die Konfiguration darf nicht null sein.");
        this.wortliste = wortliste;
        this.modell = modell;
        this.melder = Objects.requireNonNull(melder, "Der Melder darf nicht null sein.");
        this.kiPool = new java.util.concurrent.ThreadPoolExecutor(1, 1,
                0L, TimeUnit.MILLISECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(MAX_AUSSTEHEND),
                runnable -> {
                    Thread thread = new Thread(runnable, "punish-chatfilter-ki");
                    thread.setDaemon(true);
                    return thread;
                },
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    }

    public void setFilterAktiv(Boolean aktiv) {
        this.filterAktivOverride = aktiv;
    }

    public void setKiAktiv(Boolean aktiv) {
        this.kiAktivOverride = aktiv;
    }

    public Boolean filterAktivOverride() {
        return filterAktivOverride;
    }

    public Boolean kiAktivOverride() {
        return kiAktivOverride;
    }

    public void uebernimmSchalterVon(ChatfilterService alt) {
        if (alt == null) {
            return;
        }
        this.filterAktivOverride = alt.filterAktivOverride;
        this.kiAktivOverride = alt.kiAktivOverride;
    }

    public void aktualisiereWortliste(WortlistenFilter neu) {
        this.wortliste = neu;
    }

    public int kiFehlerFolge() {
        return kiFehlerFolge.get();
    }

    public boolean kiDefekt() {
        return kiFehlerFolge.get() >= KI_DEFEKT_SCHWELLE
                && System.currentTimeMillis() < kiDefektBis;
    }

    static boolean istServerThread() {
        try {
            return Bukkit.isPrimaryThread();
        } catch (Throwable t) {
            return false;
        }
    }

    private void zaehleKiFehler(String kontext, ChatfilterAusfall e) {
        int anzahl;
        try {
            anzahl = kiFehlerFolge.incrementAndGet();
        } catch (Exception ex) {
            anzahl = KI_DEFEKT_SCHWELLE;
        }
        if (anzahl >= KI_DEFEKT_SCHWELLE) {
            kiDefektBis = System.currentTimeMillis() + KI_PAUSE_MILLIS;
            LOG.warn("KI-Prüfung fällt wiederholt aus ({} Fehler in Folge, nur Wortliste aktiv) – "
                    + "Modelldatei und Logs prüfen; neuer Versuch in {} Minuten.",
                    anzahl, KI_PAUSE_MILLIS / 60_000L);
        } else {
            LOG.debug("KI-Prüfung ({}) übersprungen (Fail-Open).", kontext, e);
        }
    }

    private void kiErfolg() {
        try {
            kiFehlerFolge.set(0);
            kiDefektBis = 0L;
        } catch (Exception e) {
            LOG.trace("KI-Fehlerzähler konnte nicht zurückgesetzt werden.", e);
        }
    }

    public boolean umgeht(Player spieler) {
        Objects.requireNonNull(spieler, "Der Spieler darf nicht null sein.");
        try {
            return spieler.hasPermission(konfiguration.bypassPermission());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean filterEffektivAktiv() {
        if (Boolean.FALSE.equals(filterAktivOverride)) {
            return false;
        }
        if (Boolean.TRUE.equals(filterAktivOverride)) {
            return true;
        }
        return konfiguration.aktiv();
    }

    private Modus modusEffektiv(FilterKanal kanal) {
        if (!filterEffektivAktiv()) {
            return Modus.AUS;
        }
        Modus basis = konfiguration.modusFuer(kanal);
        if (basis == Modus.KI && (Boolean.FALSE.equals(kiAktivOverride) || kiDefekt())) {
            return Modus.STUFE1;
        }
        if (basis == Modus.STUFE1 && Boolean.TRUE.equals(kiAktivOverride) && modell != null) {
            return Modus.KI;
        }
        return basis;
    }

    @SuppressWarnings("null")
    public Optional<FilterTreffer> pruefeChat(Player spieler, String text) {
        Objects.requireNonNull(spieler, "Der Spieler darf nicht null sein.");
        if (!darfPruefen(spieler, FilterKanal.CHAT, text)) {
            return Optional.empty();
        }
        Optional<FilterTreffer> stufe1 = trefferStufe1(FilterKanal.CHAT, text);
        if (stufe1.isPresent()) {
            return stufe1;
        }
        if (modusEffektiv(FilterKanal.CHAT) != Modus.KI || modell == null) {
            return Optional.empty();
        }
        String hash = TextHash.sha256Hex(text);
        Double gecacht = bewertungsCache.get(hash);
        double bewertung;
        if (gecacht != null) {
            bewertung = gecacht;
        } else {
            try {
                bewertung = bewerteMitTimeout(text);
            } catch (ChatfilterAusfall e) {
                zaehleKiFehler("Chat", e);
                return Optional.empty();
            }
            bewertungsCache.put(hash, bewertung);
            kiErfolg();
        }
        if (bewertung >= konfiguration.schwelle()) {
            return Optional.of(new FilterTreffer(FilterKanal.CHAT, TrefferQuelle.KI, bewertung, hash));
        }
        return Optional.empty();
    }

    public Optional<FilterTreffer> pruefeStufe1(Player spieler, FilterKanal kanal, String text) {
        Objects.requireNonNull(spieler, "Der Spieler darf nicht null sein.");
        Objects.requireNonNull(kanal, "Der Kanal darf nicht null sein.");
        if (!darfPruefen(spieler, kanal, text)) {
            return Optional.empty();
        }
        return trefferStufe1(kanal, text);
    }

    public boolean istFilterAusnahme(String befehl) {
        if (befehl == null || befehl.isBlank()) {
            return false;
        }
        try {
            java.util.Set<String> ausnahmen = konfiguration.befehlAusnahmen();
            if (ausnahmen == null || ausnahmen.isEmpty()) {
                return false;
            }
            String normiert = befehl.trim().toLowerCase(java.util.Locale.ROOT);
            if (!normiert.startsWith("/")) {
                normiert = "/" + normiert;
            }
            return ausnahmen.contains(normiert);
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("null")
    public Optional<FilterTreffer> pruefeBlockierend(Player spieler, FilterKanal kanal, String text) {
        Objects.requireNonNull(spieler, "Der Spieler darf nicht null sein.");
        Objects.requireNonNull(kanal, "Der Kanal darf nicht null sein.");
        if (!darfPruefen(spieler, kanal, text)) {
            return Optional.empty();
        }
        Optional<FilterTreffer> stufe1 = trefferStufe1(kanal, text);
        if (stufe1.isPresent()) {
            return stufe1;
        }
        if (modusEffektiv(kanal) != Modus.KI || modell == null) {
            return Optional.empty();
        }
        String hash = TextHash.sha256Hex(text);
        Double gecacht = bewertungsCache.get(hash);
        double bewertung;
        if (gecacht != null) {
            bewertung = gecacht;
        } else {
            if (serverThreadPruefer.getAsBoolean()) {
                throw new ChatfilterAusfall("KI-Prüfung im Server-Thread ohne Cache übersprungen "
                        + "(Fail-Open, nachgelagerte Meldung durch den Aufrufer).");
            }
            try {
                bewertung = bewerteMitTimeout(text);
            } catch (ChatfilterAusfall e) {
                zaehleKiFehler(kanal.name(), e);
                throw e;
            }
            bewertungsCache.put(hash, bewertung);
            kiErfolg();
        }
        if (bewertung >= konfiguration.schwelle()) {
            return Optional.of(new FilterTreffer(kanal, TrefferQuelle.KI, bewertung, hash));
        }
        return Optional.empty();
    }

    public void pruefeKiNachgelagert(UUID uuid, String name, FilterKanal kanal, String text) {
        Objects.requireNonNull(uuid, "Die UUID darf nicht null sein.");
        Objects.requireNonNull(kanal, "Der Kanal darf nicht null sein.");
        if (text == null || text.isBlank() || modell == null) {
            return;
        }
        if (modusEffektiv(kanal) != Modus.KI) {
            return;
        }
        if (ausstehend.incrementAndGet() > MAX_AUSSTEHEND) {
            ausstehend.decrementAndGet();
            LOG.debug("KI-Queue voll, nachgelagerte Prüfung verworfen (Fail-Open).");
            return;
        }
        String kopie = text;
        try {
            kiPool.submit(() -> {
            try {
                String hash = TextHash.sha256Hex(kopie);
                Double gecacht = bewertungsCache.get(hash);
                double bewertung;
                if (gecacht != null) {
                    bewertung = gecacht;
                } else {
                    try {
                        bewertung = modell.bewerte(kopie);
                    } catch (ChatfilterAusfall e) {
                        zaehleKiFehler("nachgelagert", e);
                        return;
                    }
                    bewertungsCache.put(hash, bewertung);
                    kiErfolg();
                }
                if (bewertung >= konfiguration.schwelle()) {
                    try {
                        melder.melde(uuid, name, kopie,
                                new FilterTreffer(kanal, TrefferQuelle.KI, bewertung, hash));
                    } catch (Exception e) {
                        LOG.debug("Treffer-Meldung fehlgeschlagen.", e);
                    }
                }
                } finally {
                    ausstehend.decrementAndGet();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            ausstehend.decrementAndGet();
            LOG.debug("KI-Queue voll, nachgelagerte Prüfung verworfen (Fail-Open).", e);
        }
    }

    public void melden(Player spieler, String text, FilterTreffer treffer) {
        Objects.requireNonNull(spieler, "Der Spieler darf nicht null sein.");
        Objects.requireNonNull(treffer, "Der Treffer darf nicht null sein.");
        try {
            melder.melde(spieler.getUniqueId(), spieler.getName(), text == null ? "" : text, treffer);
        } catch (Exception e) {
            LOG.debug("Treffer-Meldung fehlgeschlagen.", e);
        }
    }

    private boolean darfPruefen(Player spieler, FilterKanal kanal, String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (modusEffektiv(kanal) == Modus.AUS) {
            return false;
        }
        return !umgeht(spieler);
    }

    @SuppressWarnings("null")
    private Optional<FilterTreffer> trefferStufe1(FilterKanal kanal, String text) {
        WortlistenFilter liste = wortliste;
        if (liste == null) {
            return Optional.empty();
        }
        try {
            return liste.ersterTreffer(text)
                    .map(eintrag -> new FilterTreffer(kanal, TrefferQuelle.WORTLISTE, 1.0d,
                            TextHash.sha256Hex(text)));
        } catch (Exception e) {
            LOG.debug("Wortlisten-Prüfung fehlgeschlagen (Fail-Open).", e);
            return Optional.empty();
        }
    }

    private double bewerteMitTimeout(String text) {
        if (ausstehend.incrementAndGet() > MAX_AUSSTEHEND) {
            ausstehend.decrementAndGet();
            throw new ChatfilterAusfall("KI überlastet (Fail-Open).");
        }
        Future<Double> zukunft;
        try {
            zukunft = kiPool.submit(() -> modell.bewerte(text));
        } catch (Exception e) {
            ausstehend.decrementAndGet();
            throw new ChatfilterAusfall("KI-Pool nicht verfügbar (Fail-Open).", e);
        }
        try {
            Double bewertung = zukunft.get(konfiguration.kiTimeoutMillis(), TimeUnit.MILLISECONDS);
            if (bewertung == null || Double.isNaN(bewertung)) {
                throw new ChatfilterAusfall("KI lieferte keine Bewertung.");
            }
            return Math.min(1.0d, Math.max(0.0d, bewertung));
        } catch (ChatfilterAusfall e) {
            zukunft.cancel(true);
            throw e;
        } catch (Exception e) {
            zukunft.cancel(true);
            throw new ChatfilterAusfall("KI-Timeout nach " + konfiguration.kiTimeoutMillis() + "ms.", e);
        } finally {
            ausstehend.decrementAndGet();
        }
    }

    @Override
    public void close() {
        boolean beendet = false;
        try {
            kiPool.shutdownNow();
            beendet = kiPool.awaitTermination(5L, TimeUnit.SECONDS);
            if (!beendet) {
                LOG.warn("KI-Pool wurde nicht leer; Modell wird nicht geschlossen, "
                        + "damit Reload/Shutdown nicht blockieren.");
            }
        } catch (Exception e) {
            LOG.debug("KI-Pool konnte nicht beendet werden.", e);
        }
        if (!beendet) {
            return;
        }
        if (modell instanceof AutoCloseable schliessbar) {
            try {
                schliessbar.close();
            } catch (Exception e) {
                LOG.debug("KI-Modell konnte nicht geschlossen werden.", e);
            }
        }
    }
}
