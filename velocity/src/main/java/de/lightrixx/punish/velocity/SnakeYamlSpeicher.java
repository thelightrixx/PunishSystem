package de.lightrixx.punish.velocity;

import de.lightrixx.punish.storage.YamlSpeicher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class SnakeYamlSpeicher implements YamlSpeicher {

    private static final Logger LOG = LoggerFactory.getLogger(SnakeYamlSpeicher.class);
    private static final int CODE_POINT_LIMIT = 32 * 1024 * 1024;
    private static final long MAX_DATEIGROESSE = 64L * 1024L * 1024L;
    private static final int MAX_CORRUPT_BACKUPS = 20;

    private static LoaderOptions loaderOptionen() {
        LoaderOptions optionen = new LoaderOptions();
        optionen.setAllowDuplicateKeys(false);
        optionen.setMaxAliasesForCollections(50);
        optionen.setCodePointLimit(CODE_POINT_LIMIT);
        return optionen;
    }

    @Override
    public Map<String, Object> laden(File datei) {
        return ladenMitStatus(datei).daten();
    }

    @Override
    public LadeStatus ladenMitStatus(File datei) {
        Objects.requireNonNull(datei, "Die Datei darf nicht null sein.");
        sicherstelleVerzeichnis(datei);
        raeumeTemporaerAuf(datei);
        if (!datei.exists()) {
            Map<String, Object> wiederhergestellt = versucheWiederherstellungAusBackup(datei);
            if (wiederhergestellt != null) {
                return new LadeStatus(wiederhergestellt, true, false);
            }
            return new LadeStatus(Map.of(), false, false);
        }
        try {
            if (datei.length() > MAX_DATEIGROESSE) {
                LOG.error("Die Datei {} ist zu groß ({} Bytes, Limit {}). Backup wird erstellt, leer geladen.",
                        datei.getAbsolutePath(), datei.length(), MAX_DATEIGROESSE);
                backupErstellen(datei);
                return new LadeStatus(Map.of(), true, true);
            }
        } catch (Exception e) {
            LOG.warn("Dateigröße für {} konnte nicht geprüft werden.", datei.getAbsolutePath(), e);
        }
        String inhalt;
        // Kein FileLock im Lesepfad: Der Schreiber sperrt <datei>.lock und ersetzt atomar;
        // ein Shared-Lock auf der Zieldatei würde nur JVM-interne Überlappungsfehler erzeugen.
        try {
            inhalt = Files.readString(datei.toPath(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.error("Die Datei {} konnte nicht gelesen werden.", datei.getAbsolutePath(), e);
            return new LadeStatus(Map.of(), true, true);
        }
        if (inhalt.isBlank() || inhalt.trim().equals("{}")) {
            if (datei.length() == 0) {
                Map<String, Object> wiederhergestellt = versucheWiederherstellungAusBackup(datei);
                if (wiederhergestellt != null) {
                    return new LadeStatus(wiederhergestellt, true, false);
                }
            }
            return new LadeStatus(Map.of(), true, false);
        }
        Object geladen;
        try {
            geladen = new Yaml(new SafeConstructor(loaderOptionen())).load(inhalt);
        } catch (Exception e) {
            LOG.error("Die Datei {} konnte nicht als YAML geladen werden. Ein Backup wird erstellt.",
                    datei.getAbsolutePath(), e);
            backupErstellen(datei);
            Map<String, Object> wiederhergestellt = versucheWiederherstellungAusBackup(datei);
            if (wiederhergestellt != null) {
                return new LadeStatus(wiederhergestellt, true, true);
            }
            return new LadeStatus(Map.of(), true, true);
        }
        if (!(geladen instanceof Map)) {
            LOG.error("Die Datei {} enthält kein Mapping ({}). Backup wird erstellt.",
                    datei.getAbsolutePath(), geladen == null ? "leer" : geladen.getClass().getSimpleName());
            backupErstellen(datei);
            Map<String, Object> wiederhergestellt = versucheWiederherstellungAusBackup(datei);
            if (wiederhergestellt != null) {
                return new LadeStatus(wiederhergestellt, true, true);
            }
            return new LadeStatus(Map.of(), true, true);
        }
        Map<String, Object> ergebnis = new LinkedHashMap<>();
        for (Map.Entry<?, ?> eintrag : ((Map<?, ?>) geladen).entrySet()) {
            if (eintrag.getKey() != null) {
                ergebnis.put(String.valueOf(eintrag.getKey()), eintrag.getValue());
            }
        }
        if (ergebnis.isEmpty() && hatSichtbarenInhalt(inhalt)) {
            LOG.error("Die Datei {} sieht korrupt aus (0 Keys geladen). Backup wird erstellt.",
                    datei.getAbsolutePath());
            backupErstellen(datei);
            Map<String, Object> wiederhergestellt = versucheWiederherstellungAusBackup(datei);
            if (wiederhergestellt != null) {
                return new LadeStatus(wiederhergestellt, true, true);
            }
            return new LadeStatus(Map.of(), true, true);
        }
        return new LadeStatus(ergebnis, true, false);
    }

    @Override
    public void speichern(Map<String, Object> daten, File datei) {
        Objects.requireNonNull(daten, "Die Daten dürfen nicht null sein.");
        Objects.requireNonNull(datei, "Die Datei darf nicht null sein.");
        sicherstelleVerzeichnis(datei);
        rotiereBackup(datei);
        String text;
        try {
            text = new Yaml(new SafeConstructor(loaderOptionen())).dump(daten);
        } catch (Exception e) {
            LOG.error("Die Daten für {} konnten nicht serialisiert werden.", datei.getAbsolutePath(), e);
            throw new IllegalStateException("YAML-Serialisierung fehlgeschlagen: "
                    + datei.getAbsolutePath(), e);
        }
        Path ziel = datei.toPath();
        Path eltern = ziel.getParent();
        if (eltern != null) {
            try {
                Files.createDirectories(eltern);
            } catch (Exception e) {
                LOG.warn("Verzeichnis konnte nicht angelegt werden: {}", eltern, e);
            }
        }
        Path temporaer = null;
        try {
            temporaer = Files.createTempFile(eltern == null ? Path.of(".") : eltern, datei.getName() + ".", ".tmp");
            Files.writeString(temporaer, text, StandardCharsets.UTF_8);
            forceDatei(temporaer);
            Path sperrDatei = ziel.resolveSibling(ziel.getFileName().toString() + ".lock");
            try (java.nio.channels.FileChannel lockKanal = java.nio.channels.FileChannel.open(sperrDatei,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
                    java.nio.channels.FileLock sperre = lockKanal.tryLock()) {
                if (sperre == null) {
                    LOG.warn("Exklusiv-Lock für {} belegt, schreibe ohne Lock (Race-Risiko).", ziel);
                }
                try {
                    Files.move(temporaer, ziel, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                    LOG.warn("ATOMIC_MOVE nicht unterstützt für {}, fallback auf REPLACE_EXISTING (NFS/Risiko).", ziel);
                    Files.move(temporaer, ziel, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (java.nio.file.AccessDeniedException lockFehler) {
                LOG.warn("Exklusiv-Lock für {} nicht möglich ({}), schreibe ohne Lock (Race-Risiko).",
                        ziel, lockFehler);
                try {
                    Files.move(temporaer, ziel, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                    LOG.warn("ATOMIC_MOVE nicht unterstützt für {}, fallback auf REPLACE_EXISTING (NFS/Risiko).", ziel);
                    Files.move(temporaer, ziel, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception lockFehler) {
                LOG.warn("Exklusiv-Lock für {} nicht möglich, schreibe ohne Lock (Race-Risiko).", ziel, lockFehler);
                try {
                    Files.move(temporaer, ziel, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                    LOG.warn("ATOMIC_MOVE nicht unterstützt für {}, fallback auf REPLACE_EXISTING (NFS/Risiko).", ziel);
                    Files.move(temporaer, ziel, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            forceDatei(ziel);
            syncVerzeichnis(eltern);
        } catch (IOException e) {
            LOG.error("Die Datei {} konnte nicht gespeichert werden.", datei.getAbsolutePath(), e);
            if (temporaer != null) {
                try {
                    Files.deleteIfExists(temporaer);
                } catch (IOException loeschfehler) {
                    LOG.warn("Temporärdatei konnte nicht gelöscht werden: {}", temporaer, loeschfehler);
                }
            }
            throw new IllegalStateException("YAML-Datei konnte nicht gespeichert werden: "
                    + datei.getAbsolutePath(), e);
        } catch (Exception e) {
            LOG.error("Die Datei {} konnte nicht gespeichert werden (unerwarteter Fehler).", datei.getAbsolutePath(), e);
            if (temporaer != null) {
                try {
                    Files.deleteIfExists(temporaer);
                } catch (IOException loeschfehler) {
                    LOG.warn("Temporärdatei konnte nicht gelöscht werden: {}", temporaer, loeschfehler);
                }
            }
            throw new IllegalStateException("YAML-Datei konnte nicht gespeichert werden: "
                    + datei.getAbsolutePath(), e);
        }
    }

    private static void forceDatei(Path pfad) {
        try (java.nio.channels.FileChannel kanal = java.nio.channels.FileChannel.open(pfad,
                java.nio.file.StandardOpenOption.READ)) {
            kanal.force(true);
        } catch (Exception e) {
            LOG.debug("fsync für {} fehlgeschlagen.", pfad, e);
        }
    }

    private static void syncVerzeichnis(Path verzeichnis) {
        if (verzeichnis == null) {
            return;
        }
        try (java.nio.channels.FileChannel kanal = java.nio.channels.FileChannel.open(verzeichnis,
                java.nio.file.StandardOpenOption.READ)) {
            kanal.force(true);
        } catch (Exception e) {
            LOG.debug("Verzeichnis-sync für {} fehlgeschlagen.", verzeichnis, e);
        }
    }

    private static void raeumeTemporaerAuf(File datei) {
        try {
            Path eltern = datei.toPath().getParent();
            if (eltern == null || !Files.isDirectory(eltern)) {
                return;
            }
            long grenze = System.currentTimeMillis() - 300000L;
            try (java.util.stream.Stream<Path> stream = Files.list(eltern)) {
                stream.filter(p -> {
                    String name = p.getFileName().toString();
                    return name.startsWith(datei.getName() + ".") && name.endsWith(".tmp");
                }).forEach(p -> {
                    try {
                        if (Files.getLastModifiedTime(p).toMillis() > grenze) {
                            return;
                        }
                        Files.deleteIfExists(p);
                        LOG.warn("Verwaiste Temporärdatei {} gelöscht.", p);
                    } catch (Exception e) {
                        LOG.debug("Verwaiste Temporärdatei {} konnte nicht gelöscht werden.", p, e);
                    }
                });
            }
            Path legacy = datei.toPath().resolveSibling(datei.getName() + ".tmp");
            if (Files.exists(legacy)) {
                try {
                    if (Files.getLastModifiedTime(legacy).toMillis() <= grenze) {
                        Files.deleteIfExists(legacy);
                        LOG.warn("Verwaiste Legacy-Temporärdatei {} gelöscht.", legacy);
                    }
                } catch (Exception e) {
                    LOG.debug("Legacy-Temporärdatei konnte nicht gelöscht werden.", legacy, e);
                }
            }
        } catch (Exception e) {
            LOG.debug("Temporär-Cleanup für {} übersprungen.", datei, e);
        }
    }

    private Map<String, Object> versucheWiederherstellungAusBackup(File datei) {
        for (int stufe = 1; stufe <= 3; stufe++) {
            try {
                Path bak = datei.toPath().resolveSibling(datei.getName() + ".bak." + stufe);
                if (!Files.exists(bak) || Files.size(bak) == 0) {
                    continue;
                }
                String inhalt = Files.readString(bak, StandardCharsets.UTF_8);
                if (inhalt.isBlank()) {
                    continue;
                }
                Object geladen = new Yaml(new SafeConstructor(loaderOptionen())).load(inhalt);
                if (!(geladen instanceof Map<?, ?> karte) || karte.isEmpty()) {
                    continue;
                }
                Map<String, Object> ergebnis = new LinkedHashMap<>();
                for (Map.Entry<?, ?> eintrag : karte.entrySet()) {
                    if (eintrag.getKey() != null) {
                        ergebnis.put(String.valueOf(eintrag.getKey()), eintrag.getValue());
                    }
                }
                if (ergebnis.isEmpty()) {
                    continue;
                }
                Files.copy(bak, datei.toPath(), StandardCopyOption.REPLACE_EXISTING);
                forceDatei(datei.toPath());
                LOG.error("Die Datei {} war leer/korrupt und wurde automatisch aus {} wiederhergestellt ({} Einträge).",
                        datei.getAbsolutePath(), bak.getFileName(), ergebnis.size());
                return ergebnis;
            } catch (Exception e) {
                LOG.debug("Auto-Restore für {} aus Stufe {} fehlgeschlagen.", datei, stufe, e);
            }
        }
        return null;
    }

    private void backupErstellen(File datei) {
        try {
            Path backupPfad = datei.toPath().resolveSibling(
                    datei.getName() + ".corrupt-" + System.currentTimeMillis()
                            + "-" + System.nanoTime() + "-"
                            + java.util.UUID.randomUUID().toString().substring(0, 8));
            Files.copy(datei.toPath(), backupPfad, StandardCopyOption.REPLACE_EXISTING);
            LOG.error("Backup der korrupten Datei unter {} erstellt.", backupPfad);
            rotiereCorruptBackups(datei);
        } catch (IOException e) {
            LOG.error("Backup der korrupten Datei {} konnte nicht erstellt werden.",
                    datei.getAbsolutePath(), e);
        }
    }

    private void rotiereCorruptBackups(File datei) {
        try {
            Path eltern = datei.toPath().getParent();
            if (eltern == null) {
                return;
            }
            java.util.List<Path> corrupt;
            try (java.util.stream.Stream<Path> stream = Files.list(eltern)) {
                corrupt = stream.filter(p -> p.getFileName().toString()
                        .startsWith(datei.getName() + ".corrupt-"))
                        .sorted((a, b) -> {
                            try {
                                return Long.compare(Files.getLastModifiedTime(b).toMillis(),
                                        Files.getLastModifiedTime(a).toMillis());
                            } catch (Exception e) {
                                return 0;
                            }
                        }).toList();
            }
            if (corrupt.size() > MAX_CORRUPT_BACKUPS) {
                for (int i = MAX_CORRUPT_BACKUPS; i < corrupt.size(); i++) {
                    try {
                        Files.deleteIfExists(corrupt.get(i));
                    } catch (Exception e) {
                        LOG.warn("Altes .corrupt-Backup {} konnte nicht gelöscht werden.", corrupt.get(i), e);
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Corrupt-Rotation für {} fehlgeschlagen.", datei, e);
        }
    }

    private void rotiereBackup(File datei) {
        try {
            Path eltern = datei.toPath().getParent();
            if (eltern == null || !datei.exists() || datei.length() == 0) {
                return;
            }
            Path bak3 = eltern.resolve(datei.getName() + ".bak.3");
            Path bak2 = eltern.resolve(datei.getName() + ".bak.2");
            Path bak1 = eltern.resolve(datei.getName() + ".bak.1");
            try {
                Files.deleteIfExists(bak3);
            } catch (Exception e) {
                LOG.warn("Altes .bak.3 für {} konnte nicht gelöscht werden.", datei.getName(), e);
            }
            try {
                if (Files.exists(bak2)) {
                    Files.move(bak2, bak3, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                LOG.warn("Rotation .bak.2 -> .bak.3 für {} übersprungen.", datei.getName(), e);
            }
            try {
                if (Files.exists(bak1)) {
                    Files.move(bak1, bak2, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                LOG.warn("Rotation .bak.1 -> .bak.2 für {} übersprungen.", datei.getName(), e);
            }
            try {
                Files.copy(datei.toPath(), bak1, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                LOG.warn("Backup-Kopie für {} fehlgeschlagen.", datei.getName(), e);
            }
        } catch (Exception e) {
            LOG.warn("Backup-Rotation für {} übersprungen.", datei, e);
        }
    }

    private static boolean hatSichtbarenInhalt(String inhalt) {
        String getrimmt = inhalt.trim();
        if (getrimmt.isEmpty() || getrimmt.equals("{}") || getrimmt.equals("[]")
                || getrimmt.equals("null") || getrimmt.equals("~")) {
            return false;
        }
        for (String zeile : inhalt.split("\n")) {
            String z = zeile.trim();
            if (z.isEmpty() || z.startsWith("#")) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static void sicherstelleVerzeichnis(File datei) {
        File eltern = datei.getParentFile();
        if (eltern != null && !eltern.exists() && !eltern.mkdirs() && !eltern.exists()) {
            LOG.warn("Das Verzeichnis {} konnte nicht angelegt werden.", eltern.getAbsolutePath());
        }
    }
}
