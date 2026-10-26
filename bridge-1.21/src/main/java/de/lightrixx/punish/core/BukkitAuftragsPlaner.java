package de.lightrixx.punish.core;

import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class BukkitAuftragsPlaner implements AuftragsPlaner {

    private static final Logger LOG = LoggerFactory.getLogger(BukkitAuftragsPlaner.class);

    private final Plugin plugin;
    private final BukkitScheduler scheduler;
    private final Set<BukkitTask> aktiveTasks;
    private final java.util.concurrent.atomic.AtomicBoolean geschlossen = new java.util.concurrent.atomic.AtomicBoolean(false);

    public BukkitAuftragsPlaner(Plugin plugin, BukkitScheduler scheduler) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.aktiveTasks = Collections.newSetFromMap(new ConcurrentHashMap<>());
    }

    private GeplanteAufgabe registrieren(java.util.concurrent.atomic.AtomicReference<BukkitTask> ref, BukkitTask task) {
        ref.set(task);
        if (geschlossen.get()) {

            try {
                task.cancel();
            } catch (IllegalStateException e) {
                LOG.debug("Task war bereits beendet.", e);
            }
            return new BukkitAufgabe(task);
        }
        aktiveTasks.add(task);

        if (!scheduler.isCurrentlyRunning(task.getTaskId()) && !scheduler.isQueued(task.getTaskId())) {
            aktiveTasks.remove(task);
        }
        return new BukkitAufgabe(task);
    }

    private void pruefeOffen() {
        if (geschlossen.get()) {
            throw new IllegalStateException("AuftragsPlaner ist bereits geschlossen (onDisable).");
        }
    }

    @Override
    public GeplanteAufgabe aufgabe(Runnable auftrag) {
        pruefeOffen();
        java.util.concurrent.atomic.AtomicReference<BukkitTask> ref = new java.util.concurrent.atomic.AtomicReference<>();
        BukkitTask task = scheduler.runTask(plugin, wrap(auftrag, ref));
        return registrieren(ref, task);
    }

    @Override
    public GeplanteAufgabe aufgabeAsync(Runnable auftrag) {
        pruefeOffen();
        java.util.concurrent.atomic.AtomicReference<BukkitTask> ref = new java.util.concurrent.atomic.AtomicReference<>();
        BukkitTask task = scheduler.runTaskAsynchronously(plugin, wrap(auftrag, ref));
        return registrieren(ref, task);
    }

    @Override
    public GeplanteAufgabe spaeter(Runnable auftrag, long verzoegerungTicks) {
        pruefeOffen();
        java.util.concurrent.atomic.AtomicReference<BukkitTask> ref = new java.util.concurrent.atomic.AtomicReference<>();
        BukkitTask task = scheduler.runTaskLater(plugin, wrap(auftrag, ref), verzoegerungTicks);
        return registrieren(ref, task);
    }

    @Override
    public GeplanteAufgabe spaeterAsync(Runnable auftrag, long verzoegerungTicks) {
        pruefeOffen();
        java.util.concurrent.atomic.AtomicReference<BukkitTask> ref = new java.util.concurrent.atomic.AtomicReference<>();
        BukkitTask task = scheduler.runTaskLaterAsynchronously(plugin, wrap(auftrag, ref), verzoegerungTicks);
        return registrieren(ref, task);
    }

    @Override
    public GeplanteAufgabe intervallAsync(Runnable auftrag, long startTicks, long periodeTicks) {
        pruefeOffen();
        java.util.concurrent.atomic.AtomicReference<BukkitTask> ref = new java.util.concurrent.atomic.AtomicReference<>();
        BukkitTask task = scheduler.runTaskTimerAsynchronously(plugin, wrapTimer(auftrag, ref), startTicks, periodeTicks);
        return registrieren(ref, task);
    }

    private Runnable wrapTimer(Runnable original, java.util.concurrent.atomic.AtomicReference<BukkitTask> holder) {
        return () -> {
            try {
                original.run();
            } catch (Throwable ex) {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                LOG.error("Unerwarteter Fehler in Scheduler-Timer-Task", ex);
            }

            BukkitTask task = holder.get();
            if (task != null) {
                try {
                    if (!scheduler.isCurrentlyRunning(task.getTaskId()) && !scheduler.isQueued(task.getTaskId())) {
                        aktiveTasks.remove(task);
                    }
                } catch (IllegalStateException ignored) {
                    aktiveTasks.remove(task);
                }
            }
        };
    }

    private Runnable wrap(Runnable original, java.util.concurrent.atomic.AtomicReference<BukkitTask> holder) {
        return () -> {
            try {
                original.run();
            } catch (Throwable ex) {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                LOG.error("Unerwarteter Fehler in Scheduler-Task", ex);
            } finally {
                BukkitTask task = holder.get();
                if (task != null) {
                    aktiveTasks.remove(task);
                }
            }
        };
    }

    @Override
    public void alleAbbrechen() {

        geschlossen.set(true);
        if (aktiveTasks.isEmpty()) {
            return;
        }

        for (int runde = 0; runde < 10; runde++) {
            java.util.List<BukkitTask> snapshot = java.util.List.copyOf(aktiveTasks);
            if (snapshot.isEmpty()) {
                break;
            }
            int rundeAnzeige = runde + 1;
            LOG.info("Beende {} ausstehende Bukkit-Tasks... (Runde {})", snapshot.size(), rundeAnzeige);
            for (BukkitTask task : snapshot) {
                aktiveTasks.remove(task);
                try {
                    if (scheduler.isCurrentlyRunning(task.getTaskId()) || scheduler.isQueued(task.getTaskId())) {
                        task.cancel();
                    } else {
                        try {
                            task.cancel();
                        } catch (IllegalStateException ignored) {
                            LOG.debug("Task {} war bereits beendet", task.getTaskId());
                        }
                    }
                } catch (IllegalStateException ex) {
                    LOG.debug("Task {} war bereits beendet", task.getTaskId());
                }
            }
        }

        for (BukkitTask rest : java.util.List.copyOf(aktiveTasks)) {
            aktiveTasks.remove(rest);
            try {
                rest.cancel();
            } catch (IllegalStateException ignored) {
                LOG.debug("Task {} war bereits beendet", rest.getTaskId());
            }
        }
        LOG.debug("Alle Bukkit-Tasks terminiert");
    }

    public int anzahlAktiv() {
        return aktiveTasks.size();
    }

    @Override
    public boolean istGeschlossen() {
        return geschlossen.get();
    }

    private static final class BukkitAufgabe implements GeplanteAufgabe {
        private final BukkitTask task;

        private BukkitAufgabe(BukkitTask task) {
            this.task = task;
        }

        @Override
        public void abbrechen() {
            if (task == null) {
                return;
            }
            try {
                task.cancel();
            } catch (IllegalStateException e) {
                LOG.debug("Task war bereits beendet.", e);
            }
        }
    }
}
