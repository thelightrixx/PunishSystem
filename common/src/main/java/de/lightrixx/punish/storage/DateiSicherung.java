package de.lightrixx.punish.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

public final class DateiSicherung {

    private static final Logger LOG = LoggerFactory.getLogger(DateiSicherung.class);

    private DateiSicherung() {
    }

    public static boolean aktualisiereKonfiguration(Path ziel, String vorher, String nachher) throws IOException {
        java.util.Objects.requireNonNull(ziel, "Die Konfigurationsdatei darf nicht null sein.");
        java.util.Objects.requireNonNull(vorher, "Der bisherige Inhalt darf nicht null sein.");
        java.util.Objects.requireNonNull(nachher, "Der neue Inhalt darf nicht null sein.");
        if (vorher.equals(nachher)) {
            return false;
        }
        if (!Files.readString(ziel, java.nio.charset.StandardCharsets.UTF_8).equals(vorher)) {
            throw new IOException("Konfiguration wurde während des Abgleichs geändert: " + ziel.getFileName());
        }
        Path temporaer = Files.createTempFile(ziel.toAbsolutePath().getParent(), ziel.getFileName() + ".", ".tmp");
        try {
            Files.writeString(temporaer, nachher, java.nio.charset.StandardCharsets.UTF_8);
            try (var kanal = java.nio.channels.FileChannel.open(temporaer, java.nio.file.StandardOpenOption.WRITE)) {
                kanal.force(true);
            }
            Files.copy(ziel, ziel.resolveSibling(ziel.getFileName() + ".vor-config-update.bak"),
                    StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temporaer, ziel, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                LOG.warn("Atomarer Config-Austausch für {} nicht unterstützt, verwende Dateiersatz.", ziel.getFileName());
                Files.move(temporaer, ziel, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } finally {
            Files.deleteIfExists(temporaer);
        }
    }

    public static int snapshotOrdnerFuerBoot(File ordner) {
        if (ordner == null || !ordner.isDirectory()) {
            return 0;
        }
        int anzahl = sichereYmlDateien(ordner);
        File config = new File(ordner, "config");
        if (config.isDirectory()) {
            anzahl += sichereYmlDateien(config);
        }
        File history = new File(ordner, "history");
        if (history.isDirectory()) {
            anzahl += sichereYmlDateien(history);
            File archiv = new File(history, "archive");
            if (archiv.isDirectory()) {
                anzahl += sichereYmlDateien(archiv);
            }
        }
        return anzahl;
    }

    private static int sichereYmlDateien(File ordner) {
        File[] dateien = ordner.listFiles((dir, name) ->
                name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (dateien == null) {
            return 0;
        }
        int anzahl = 0;
        for (File datei : dateien) {
            if (!datei.isFile() || datei.length() == 0) {
                continue;
            }
            try {
                Path ziel = datei.toPath();
                Path neu = ziel.resolveSibling(datei.getName() + ".pre-boot.bak");
                Path alt = ziel.resolveSibling(datei.getName() + ".pre-boot.bak.1");
                if (Files.exists(neu)) {
                    Files.move(neu, alt, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.copy(ziel, neu, StandardCopyOption.REPLACE_EXISTING);
                anzahl++;
            } catch (IOException e) {
                LOG.warn("Boot-Backup für {} fehlgeschlagen.", datei.getName(), e);
            }
        }
        return anzahl;
    }
}
