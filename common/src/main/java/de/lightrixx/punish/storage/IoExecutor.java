package de.lightrixx.punish.storage;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class IoExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(IoExecutor.class);

    private final ExecutorService executor;
    private final java.util.concurrent.ConcurrentHashMap<String, Runnable> geplanteNeueste =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean pumpeAktiv = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicInteger ausstehend = new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicInteger fehlerZaehler = new java.util.concurrent.atomic.AtomicInteger(0);
    private volatile long letzterFehlerZeit = 0L;
    private volatile String letzterFehlerText = "";
    private static final long GESUND_FENSTER_MILLIS = 30L * 60L * 1000L;
    public static final int UEBERLAST_SCHWELLE = 2000;

    public IoExecutor() {
        this.executor = Executors.newSingleThreadExecutor(new IoThreadFactory());
    }

    public int anzahlAusstehend() {
        return ausstehend.get() + geplanteNeueste.size();
    }

    public int anzahlFehler() {
        return fehlerZaehler.get();
    }

    public long letzterFehlerZeit() {
        return letzterFehlerZeit;
    }

    public String letzterFehlerText() {
        return letzterFehlerText;
    }

    public boolean istUeberlastet() {
        return anzahlAusstehend() >= UEBERLAST_SCHWELLE;
    }

    public boolean istGesund() {
        if (istUeberlastet()) {
            return false;
        }
        if (fehlerZaehler.get() == 0) {
            return true;
        }
        return System.currentTimeMillis() - letzterFehlerZeit > GESUND_FENSTER_MILLIS;
    }

    /**
     * Schreibauftrag mit Coalescing: Pro Schluessel (z. B. Zieldatei) bleibt nur
     * der NEUESTE Auftrag liegen – aeltere Auftraege fuer denselben Schluessel
     * werden verworfen, nicht gestapelt. Verhindert Queue/OOM-Wachstum bei
     * Join-Stuermen und Massenaktionen mit Vollserialisierung je Datei.
     */
    public void ausfuehrenMitSchluessel(String schluessel, Runnable aufgabe) {
        Objects.requireNonNull(schluessel, "Der Schluessel darf nicht null sein.");
        Objects.requireNonNull(aufgabe, "Die I/O-Aufgabe darf nicht null sein.");
        if (executor.isShutdown()) {
            fuehreAusFallback(aufgabe);
            return;
        }
        Runnable vorherige = geplanteNeueste.put(schluessel, aufgabe);
        int wartend = anzahlAusstehend();
        if (wartend > 1000 && wartend % 500 == 0) {
            LOG.warn("I/O-Queue wächst ({} ausstehend) – Disk langsam oder Massenaktion?", wartend);
        }
        if (vorherige == null && pumpeAktiv.compareAndSet(false, true)) {
            try {
                executor.execute(this::pumpe);
            } catch (RejectedExecutionException e) {
                pumpeAktiv.set(false);
                pumpeSynchron();
            }
        }
    }

    private void pumpe() {
        try {
            while (!geplanteNeueste.isEmpty()) {
                entleereGeplante();
            }
        } finally {
            pumpeAktiv.set(false);
            if (!geplanteNeueste.isEmpty() && pumpeAktiv.compareAndSet(false, true)) {
                try {
                    executor.execute(this::pumpe);
                    return;
                } catch (RejectedExecutionException e) {
                    pumpeAktiv.set(false);
                }
            }
            if (!geplanteNeueste.isEmpty()) {
                pumpeSynchron();
            }
        }
    }

    private void pumpeSynchron() {
        try {
            while (!geplanteNeueste.isEmpty()) {
                entleereGeplante();
            }
        } catch (Throwable t) {
            LOG.error("Beim synchronen Abarbeiten geplanter I/O-Aufgaben ist ein Fehler aufgetreten.", t);
        }
    }

    private void entleereGeplante() {
        for (var eintrag : java.util.List.copyOf(geplanteNeueste.entrySet())) {
            if (geplanteNeueste.remove(eintrag.getKey(), eintrag.getValue())) {
                try {
                    eintrag.getValue().run();
                } catch (Throwable t) {
                    fehlerZaehler.incrementAndGet();
                    letzterFehlerZeit = System.currentTimeMillis();
                    letzterFehlerText = String.valueOf(t.getMessage());
                    LOG.error("Beim Ausführen einer asynchronen I/O-Aufgabe ist ein Fehler aufgetreten.", t);
                }
            }
        }
    }

    private void fuehreAusFallback(Runnable aufgabe) {
        try {
            aufgabe.run();
        } catch (Throwable t) {
            LOG.error("Beim Ausführen einer I/O-Aufgabe im Shutdown-Fallback ist ein Fehler aufgetreten.", t);
        }
    }

    public void ausfuehren(Runnable aufgabe) {
        Objects.requireNonNull(aufgabe, "Die I/O-Aufgabe darf nicht null sein.");
        if (executor.isShutdown()) {

            try {
                aufgabe.run();
            } catch (Throwable t) {
                LOG.error("Beim Ausführen einer I/O-Aufgabe im Shutdown-Fallback ist ein Fehler aufgetreten.", t);
            }
            return;
        }
        try {
            int wartend = ausstehend.incrementAndGet();
            if (wartend > 1000 && wartend % 500 == 0) {
                LOG.warn("I/O-Queue wächst ({} ausstehend) – Disk langsam oder Massenaktion?", wartend);
            }
            executor.execute(() -> {
                try {
                    aufgabe.run();
                } catch (Throwable t) {
                    fehlerZaehler.incrementAndGet();
                    letzterFehlerZeit = System.currentTimeMillis();
                    letzterFehlerText = String.valueOf(t.getMessage());
                    LOG.error("Beim Ausführen einer asynchronen I/O-Aufgabe ist ein Fehler aufgetreten.", t);
                } finally {
                    ausstehend.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException e) {
            ausstehend.decrementAndGet();
            LOG.warn("I/O-Aufgabe nach Shutdown – führe synchron aus statt zu verwerfen.", e);
            try {
                aufgabe.run();
            } catch (Throwable t) {
                LOG.error("Beim Ausführen einer I/O-Aufgabe im Rejected-Fallback ist ein Fehler aufgetreten.", t);
            }
        }
    }

    public boolean warteAufIdle(long wartezeit, TimeUnit einheit) {
        Objects.requireNonNull(einheit, "Die Zeiteinheit darf nicht null sein.");
        if (executor.isShutdown()) {
            return true;
        }

        if (Thread.currentThread().getName().startsWith("PunishSystem-IO-")) {
            return true;
        }
        java.util.concurrent.Future<?> barriere;
        try {
            barriere = executor.submit(() -> {
            });
        } catch (RejectedExecutionException e) {
            return true;
        }
        try {
            barriere.get(wartezeit, einheit);
            return geplanteNeueste.isEmpty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean herunterfahren(long wartezeit, TimeUnit einheit) {
        Objects.requireNonNull(einheit, "Die Zeiteinheit darf nicht null sein.");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(wartezeit, einheit)) {
                LOG.warn("Der I/O-Executor wurde nicht innerhalb der Wartezeit fertig ({} ausstehend); "
                                + "erzwinge Abbruch mit kurzer Grace statt doppelter Voll-Wartezeit.",
                        ausstehend.get());
                java.util.List<Runnable> rest = executor.shutdownNow();
                for (Runnable aufgabe : rest) {
                    try {
                        aufgabe.run();
                    } catch (Throwable t) {
                        LOG.error("Beim Nachholen einer queued I/O-Aufgabe ist ein Fehler aufgetreten.", t);
                    }
                }

                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    LOG.error("Der I/O-Executor konnte auch nach erzwungenem Abbruch nicht beendet werden.");
                    return false;
                }
                return true;
            }
            return true;
        } catch (InterruptedException e) {
            LOG.warn("Das Warten auf das Beenden des I/O-Executors wurde unterbrochen.");
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static final class IoThreadFactory implements ThreadFactory {
        private final AtomicInteger zaehler = new AtomicInteger(1);

        @Override
        public Thread newThread(@NotNull Runnable aufgabe) {
            Thread thread = new Thread(aufgabe, "PunishSystem-IO-" + zaehler.getAndIncrement());
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((t, e) ->
                    LOG.error("Unbehandelter Fehler im IO-Thread {}", t.getName(), e));
            return thread;
        }
    }
}
