package de.lightrixx.punish.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.GeplanteAufgabe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class VelocityAuftragsPlaner implements AuftragsPlaner {

    private static final Logger LOG = LoggerFactory.getLogger(VelocityAuftragsPlaner.class);
    private static final long TICK_MILLIS = 50L;

    private final ProxyServer proxy;
    private final Object plugin;
    private final Set<ScheduledTask> aktiveTasks =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final AtomicBoolean geschlossen = new AtomicBoolean(false);

    public VelocityAuftragsPlaner(ProxyServer proxy, Object plugin) {
        this.proxy = Objects.requireNonNull(proxy, "Der Proxy darf nicht null sein.");
        this.plugin = Objects.requireNonNull(plugin, "Das Plugin darf nicht null sein.");
    }

    private GeplanteAufgabe registrieren(ScheduledTask task) {
        if (geschlossen.get()) {
            try {
                task.cancel();
            } catch (Exception e) {
                LOG.debug("Task-Abbruch nach Shutdown fehlgeschlagen.", e);
            }
            return new VelocityAufgabe(task);
        }
        aktiveTasks.add(task);
        return new VelocityAufgabe(task);
    }

    private void pruefeOffen() {
        if (geschlossen.get()) {
            throw new IllegalStateException("AuftragsPlaner ist bereits geschlossen (Shutdown).");
        }
    }

    private Runnable einhuellen(Runnable original,
                                java.util.concurrent.atomic.AtomicReference<ScheduledTask> halter,
                                AtomicBoolean erledigt) {
        return () -> {
            try {
                original.run();
            } catch (Throwable ex) {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                LOG.error("Unerwarteter Fehler in Scheduler-Task", ex);
            } finally {
                erledigt.set(true);
                ScheduledTask task = halter.get();
                if (task != null) {
                    aktiveTasks.remove(task);
                }
            }
        };
    }

    private GeplanteAufgabe nachregistrieren(ScheduledTask task, AtomicBoolean erledigt) {
        GeplanteAufgabe geplant = registrieren(task);
        if (erledigt.get()) {
            aktiveTasks.remove(task);
        }
        return geplant;
    }

    @Override
    public GeplanteAufgabe aufgabe(Runnable auftrag) {
        pruefeOffen();
        java.util.concurrent.atomic.AtomicReference<ScheduledTask> halter =
                new java.util.concurrent.atomic.AtomicReference<>();
        AtomicBoolean erledigt = new AtomicBoolean(false);
        ScheduledTask task = proxy.getScheduler()
                .buildTask(plugin, einhuellen(auftrag, halter, erledigt)).schedule();
        halter.set(task);
        return nachregistrieren(task, erledigt);
    }

    @Override
    public GeplanteAufgabe aufgabeAsync(Runnable auftrag) {
        // Velocity hat keinen Main-Thread: Scheduler-Tasks laufen bereits asynchron.
        return aufgabe(auftrag);
    }

    @Override
    public GeplanteAufgabe spaeter(Runnable auftrag, long verzoegerungTicks) {
        pruefeOffen();
        java.util.concurrent.atomic.AtomicReference<ScheduledTask> halter =
                new java.util.concurrent.atomic.AtomicReference<>();
        AtomicBoolean erledigt = new AtomicBoolean(false);
        ScheduledTask task = proxy.getScheduler().buildTask(plugin, einhuellen(auftrag, halter, erledigt))
                .delay(Math.max(0L, verzoegerungTicks) * TICK_MILLIS, TimeUnit.MILLISECONDS)
                .schedule();
        halter.set(task);
        return nachregistrieren(task, erledigt);
    }

    @Override
    public GeplanteAufgabe spaeterAsync(Runnable auftrag, long verzoegerungTicks) {
        // Velocity hat keinen Main-Thread: Scheduler-Tasks laufen bereits asynchron.
        return spaeter(auftrag, verzoegerungTicks);
    }

    @Override
    public GeplanteAufgabe intervallAsync(Runnable auftrag, long startTicks, long periodeTicks) {
        pruefeOffen();
        ScheduledTask task = proxy.getScheduler().buildTask(plugin, () -> {
            try {
                auftrag.run();
            } catch (Throwable ex) {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                LOG.error("Unerwarteter Fehler in Scheduler-Timer-Task", ex);
            }
        })
                .delay(Math.max(0L, startTicks) * TICK_MILLIS, TimeUnit.MILLISECONDS)
                .repeat(Math.max(1L, periodeTicks) * TICK_MILLIS, TimeUnit.MILLISECONDS)
                .schedule();
        return registrieren(task);
    }

    @Override
    public void alleAbbrechen() {
        geschlossen.set(true);
        for (ScheduledTask task : java.util.List.copyOf(aktiveTasks)) {
            aktiveTasks.remove(task);
            try {
                task.cancel();
            } catch (Exception e) {
                LOG.debug("Task-Abbruch beim AlleAbbrechen fehlgeschlagen.", e);
            }
        }
        LOG.debug("Alle Velocity-Tasks terminiert");
    }

    @Override
    public boolean istGeschlossen() {
        return geschlossen.get();
    }

    private static final class VelocityAufgabe implements GeplanteAufgabe {
        private final ScheduledTask task;

        private VelocityAufgabe(ScheduledTask task) {
            this.task = task;
        }

        @Override
        public void abbrechen() {
            if (task == null) {
                return;
            }
            try {
                task.cancel();
            } catch (Exception e) {
                LOG.debug("Task-Abbruch fehlgeschlagen.", e);
            }
        }
    }
}
