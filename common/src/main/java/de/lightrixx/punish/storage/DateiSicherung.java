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
