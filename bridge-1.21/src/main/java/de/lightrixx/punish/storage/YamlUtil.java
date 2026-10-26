package de.lightrixx.punish.storage;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

public final class YamlUtil {

    private static final Logger LOG = LoggerFactory.getLogger(YamlUtil.class);
    private static final long MAX_DATEIGROESSE = 64L * 1024L * 1024L;
    private static final int MAX_CORRUPT_BACKUPS = 20;

    public static int snapshotOrdnerFuerBoot(File ordner) {
        return DateiSicherung.snapshotOrdnerFuerBoot(ordner);
    }

    public record Ladung(FileConfiguration konfiguration, boolean dateiVorhanden, boolean fehler) {
    }

    public FileConfiguration laden(File datei) {
        return ladenMitStatus(datei).konfiguration();
    }

    public Ladung ladenMitStatus(File datei) {
        Objects.requireNonNull(datei, "Die zu ladende Datei darf nicht null sein.");
        sicherstelleVerzeichnis(datei);
        raeumeTemporaerAuf(datei);

        if (!datei.exists()) {
            FileConfiguration wiederhergestellt = versucheWiederherstellungAusBackup(datei);
            if (wiederhergestellt != null) {
                return new Ladung(wiederhergestellt, true, false);
            }
            return new Ladung(new YamlConfiguration(), false, false);
        }

        try {
            if (datei.length() > MAX_DATEIGROESSE) {
                LOG.error("Die Datei {} ist zu groß ({} Bytes, Limit {}). Backup wird erstellt, leer geladen.",
                        datei.getAbsolutePath(), datei.length(), MAX_DATEIGROESSE);
                backupErstellen(datei);
                return new Ladung(new YamlConfiguration(), true, true);
            }
        } catch (Exception e) {
            LOG.warn("Dateigröße für {} konnte nicht geprüft werden.", datei.getAbsolutePath(), e);
        }

        FileConfiguration geladen;
        try {
            geladen = YamlConfiguration.loadConfiguration(datei);
        } catch (Exception e) {
            LOG.error("Die Datei {} konnte nicht als YAML geladen werden. Ein Backup wird erstellt.",
                    datei.getAbsolutePath(), e);
            backupErstellen(datei);
            FileConfiguration wiederhergestellt = versucheWiederherstellungAusBackup(datei);
            if (wiederhergestellt != null) {
                return new Ladung(wiederhergestellt, true, true);
            }
            return new Ladung(new YamlConfiguration(), true, true);
        }
        try {
            if (datei.length() == 0) {
                FileConfiguration wiederhergestellt = versucheWiederherstellungAusBackup(datei);
                if (wiederhergestellt != null) {
                    return new Ladung(wiederhergestellt, true, false);
                }
            }
            if (datei.length() > 0 && geladen.getKeys(false).isEmpty() && hatSichtbarenInhalt(datei)) {
                LOG.error("Die Datei {} sieht korrupt aus ({} Bytes, 0 Keys geladen). Backup wird erstellt, "
                        + "leere Config wird geliefert (Fail-Closed greift in Repositories).",
                        datei.getAbsolutePath(), datei.length());
                backupErstellen(datei);
                FileConfiguration wiederhergestellt = versucheWiederherstellungAusBackup(datei);
                if (wiederhergestellt != null) {
                    return new Ladung(wiederhergestellt, true, true);
                }
                return new Ladung(new YamlConfiguration(), true, true);
            }
        } catch (Exception e) {
            LOG.debug("Korruptions-Heuristik für {} übersprungen.", datei.getAbsolutePath(), e);
        }
        return new Ladung(geladen, true, false);
    }

    public void speichern(FileConfiguration konfiguration, File datei) {
        Objects.requireNonNull(konfiguration, "Die zu speichernde Konfiguration darf nicht null sein.");
        Objects.requireNonNull(datei, "Die Zieldatei darf nicht null sein.");
        sicherstelleVerzeichnis(datei);

        rotiereBackup(datei);

        Path ziel = datei.toPath();
        Path eltern = ziel.getParent();
        Path temporaer = null;
        try {
            temporaer = Files.createTempFile(eltern == null ? Path.of(".") : eltern, datei.getName() + ".", ".tmp");
            konfiguration.save(temporaer.toFile());
            forceDatei(temporaer);
            try {
                Files.move(temporaer, ziel, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                LOG.warn("ATOMIC_MOVE nicht unterstützt für {}, fallback auf REPLACE_EXISTING (NFS/Risiko).", ziel);
                Files.move(temporaer, ziel, StandardCopyOption.REPLACE_EXISTING);
            }
            forceDatei(ziel);
            syncVerzeichnis(eltern);
        } catch (IOException e) {
            LOG.error("Die Datei {} konnte nicht gespeichert werden.", datei.getAbsolutePath(), e);
            if (temporaer != null) {
                try {
                    Files.deleteIfExists(temporaer);
                } catch (IOException loeschfehler) {
                    LOG.warn("Die temporäre Datei {} konnte nicht aufgeräumt werden.", temporaer, loeschfehler);
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
                    LOG.warn("Die temporäre Datei {} konnte nicht aufgeräumt werden.", temporaer, loeschfehler);
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

    private FileConfiguration versucheWiederherstellungAusBackup(File datei) {
        try {
            Path bak1 = datei.toPath().resolveSibling(datei.getName() + ".bak.1");
            if (!Files.exists(bak1) || Files.size(bak1) == 0) {
                return null;
            }
            FileConfiguration kandidat = YamlConfiguration.loadConfiguration(bak1.toFile());
            if (kandidat.getKeys(false).isEmpty()) {
                return null;
            }
            Files.copy(bak1, datei.toPath(), StandardCopyOption.REPLACE_EXISTING);
            forceDatei(datei.toPath());
            LOG.error("Die Datei {} war leer/korrupt und wurde automatisch aus {} wiederhergestellt.",
                    datei.getAbsolutePath(), bak1.getFileName());
            return kandidat;
        } catch (Exception e) {
            LOG.debug("Auto-Restore für {} fehlgeschlagen.", datei, e);
            return null;
        }
    }

    private void backupErstellen(File datei) {
        try {
            Path backupPfad = datei.toPath().resolveSibling(
                    datei.getName() + ".corrupt-" + System.currentTimeMillis()
                            + "-" + System.nanoTime() + "-"
                            + java.util.UUID.randomUUID().toString().substring(0, 8));
            Files.copy(datei.toPath(), backupPfad, StandardCopyOption.REPLACE_EXISTING);
            LOG.error("Backup der korrupten Datei wurde unter {} erstellt (Original bleibt für Fail-Closed liegen).",
                    backupPfad);
            rotiereCorruptBackups(datei);
        } catch (IOException ioException) {
            LOG.error("Backup der korrupten Datei {} konnte nicht erstellt werden.",
                    datei.getAbsolutePath(), ioException);
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
            String basis = datei.getName();
            Path bak3 = eltern.resolve(basis + ".bak.3");
            Path bak2 = eltern.resolve(basis + ".bak.2");
            Path bak1 = eltern.resolve(basis + ".bak.1");
            try {
                Files.deleteIfExists(bak3);
            } catch (Exception e) {
                LOG.warn("Altes .bak.3 für {} konnte nicht gelöscht werden.", basis, e);
            }
            try {
                if (Files.exists(bak2)) {
                    Files.move(bak2, bak3, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                LOG.warn("Rotation .bak.2 -> .bak.3 für {} übersprungen.", basis, e);
            }
            try {
                if (Files.exists(bak1)) {
                    Files.move(bak1, bak2, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                LOG.warn("Rotation .bak.1 -> .bak.2 für {} übersprungen.", basis, e);
            }
            try {
                Files.copy(datei.toPath(), bak1, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                LOG.warn("Backup-Kopie für {} fehlgeschlagen.", basis, e);
            }
        } catch (Exception e) {
            LOG.warn("Backup-Rotation für {} übersprungen.", datei, e);
        }
    }

    private boolean hatSichtbarenInhalt(File datei) {
        try {
            if (datei.length() > MAX_DATEIGROESSE) {
                return true;
            }
            if (datei.length() > 1024L * 1024L) {
                try (var leser = Files.newBufferedReader(datei.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
                    char[] puffer = new char[65536];
                    int gelesen = leser.read(puffer);
                    if (gelesen <= 0) {
                        return false;
                    }
                    String ausschnitt = new String(puffer, 0, gelesen).trim();
                    if (ausschnitt.isEmpty() || ausschnitt.equals("{}") || ausschnitt.equals("[]")
                            || ausschnitt.equals("null") || ausschnitt.equals("~")) {
                        return false;
                    }
                    return true;
                }
            }
            String inhalt = Files.readString(datei.toPath());
            String getrimmtGesamt = inhalt.trim();

            if (getrimmtGesamt.isEmpty() || getrimmtGesamt.equals("{}")
                    || getrimmtGesamt.equals("[]") || getrimmtGesamt.equals("null")
                    || getrimmtGesamt.equals("~")) {
                return false;
            }
            for (String zeile : inhalt.split("\n")) {
                String getrimmt = zeile.trim();
                if (getrimmt.isEmpty() || getrimmt.startsWith("#")) {
                    continue;
                }
                return true;
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    private void sicherstelleVerzeichnis(File datei) {
        File elternverzeichnis = datei.getParentFile();
        if (elternverzeichnis != null && !elternverzeichnis.exists() && !elternverzeichnis.mkdirs()
                && !elternverzeichnis.exists()) {
            LOG.warn("Das Verzeichnis {} konnte nicht angelegt werden.", elternverzeichnis.getAbsolutePath());
        }
    }
}
