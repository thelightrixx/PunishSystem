package de.lightrixx.punish.storage;

import de.lightrixx.punish.model.HistoryType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

public final class LogWriter {

    private static final Logger LOG = LoggerFactory.getLogger(LogWriter.class);
    private static final String DATEI_NAME = "punish_log.txt";

    private static final long MAX_GROESSE_BYTES = 10L * 1024L * 1024L;
    private static final int MAX_ROTATIONEN = 5;
    private static final DateTimeFormatter ZEIT_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");

    private final File datei;
    private final IoExecutor ioExecutor;

    private long bytesSeitOeffnen = 0L;

    private BufferedWriter writer;

    public LogWriter(File pluginDatenOrdner, IoExecutor ioExecutor) {
        Objects.requireNonNull(pluginDatenOrdner, "Der Datenordner darf nicht null sein.");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "Der I/O-Executor darf nicht null sein.");
        this.datei = new File(pluginDatenOrdner, DATEI_NAME);
    }

    public void schreiben(HistoryType typ, String ausfuehrer, String ziel, String details) {
        schreiben(typ, ausfuehrer, ziel, details,
                de.lightrixx.punish.model.ServerScope.UNBEKANNT_WERT,
                de.lightrixx.punish.model.ServerScope.UNBEKANNT_WERT);
    }

    public void schreiben(HistoryType typ, String ausfuehrer, String ziel, String details,
                          String scope, String herkunft) {
        Objects.requireNonNull(typ, "Der History-Typ darf nicht null sein.");
        String zeile = formatiere(typ, ausfuehrer, ziel, details, scope, herkunft);
        ioExecutor.ausfuehren(() -> schreibeZeile(zeile));
    }

    public void schreiben(HistoryType typ, String ausfuehrer, String ziel, String details,
                          de.lightrixx.punish.model.ServerScope scope, String herkunft) {
        schreiben(typ, ausfuehrer, ziel, details,
                scope == null ? de.lightrixx.punish.model.ServerScope.UNBEKANNT_WERT : scope.speicherwert(),
                herkunft);
    }

    public void schliessen() {
        ioExecutor.ausfuehren(this::schliesseWriter);
    }

    private synchronized void schreibeZeile(String zeile) {
        try {
            if (writer == null) {
                oeffne();
            }

            if (bytesSeitOeffnen >= MAX_GROESSE_BYTES) {
                rotiere();
                oeffne();
            }
            writer.write(zeile);
            writer.newLine();
            writer.flush();
            bytesSeitOeffnen += zeile.getBytes(StandardCharsets.UTF_8).length
                    + System.lineSeparator().getBytes(StandardCharsets.UTF_8).length;
        } catch (IOException e) {
            LOG.error("Beim Schreiben in die Logdatei ist ein Fehler aufgetreten.", e);
            schliesseWriter();
            throw new IllegalStateException("Audit-Log konnte nicht geschrieben werden: "
                    + datei.getAbsolutePath(), e);
        }
    }

    private void rotiere() {
        schliesseWriter();
        try {
            File eltern = datei.getParentFile();
            if (eltern == null) {
                return;
            }
            File aelteste = new File(eltern, "punish_log." + MAX_ROTATIONEN + ".txt");
            try {
                Files.deleteIfExists(aelteste.toPath());
            } catch (IOException e) {
                LOG.warn("Alte Log-Rotation konnte nicht gelöscht werden.", e);
            }
            for (int i = MAX_ROTATIONEN - 1; i >= 1; i--) {
                File quelle = new File(eltern, "punish_log." + i + ".txt");
                if (!quelle.exists()) {
                    continue;
                }
                File ziel = new File(eltern, "punish_log." + (i + 1) + ".txt");
                try {
                    Files.move(quelle.toPath(), ziel.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    LOG.warn("Log-Rotation {} -> {} fehlgeschlagen.", quelle.getName(), ziel.getName(), e);
                    break;
                }
            }
            try {
                Files.move(datei.toPath(), new File(eltern, "punish_log.1.txt").toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                LOG.warn("Log-Rotation der Hauptdatei fehlgeschlagen.", e);
            }
        } catch (Exception e) {
            LOG.warn("Log-Rotation fehlgeschlagen.", e);
        } finally {
            bytesSeitOeffnen = 0L;
        }
    }

    private synchronized void oeffne() throws IOException {
        File elternverzeichnis = datei.getParentFile();
        if (elternverzeichnis != null && !elternverzeichnis.exists() && !elternverzeichnis.mkdirs()
                && !elternverzeichnis.exists()) {
            LOG.warn("Das Verzeichnis für die Logdatei konnte nicht angelegt werden.");
        }
        Path pfad = datei.toPath();
        writer = Files.newBufferedWriter(pfad, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        try {
            bytesSeitOeffnen = Files.size(pfad);
        } catch (IOException ignored) {
            bytesSeitOeffnen = 0L;
        }
    }

    private synchronized void schliesseWriter() {
        if (writer == null) {
            return;
        }
        try {
            writer.flush();
            writer.close();
        } catch (IOException e) {
            LOG.warn("Beim Schließen der Logdatei ist ein Fehler aufgetreten.", e);
        } finally {
            writer = null;
        }
    }

    private String formatiere(HistoryType typ, String ausfuehrer, String ziel, String details,
                              String scope, String herkunft) {
        String zeitstempel;
        try {
            zeitstempel = java.time.ZonedDateTime.now(de.lightrixx.punish.util.ZeitFormat.anzeigeZone())
                    .format(ZEIT_FORMAT);
        } catch (Exception e) {
            zeitstempel = LocalDateTime.now().format(ZEIT_FORMAT);
        }
        return "[" + zeitstempel + "] [" + typ.name() + "] "
                + "[Scope:" + sanieren(normiereStempel(scope)) + "] "
                + "[Von:" + sanieren(normiereStempel(herkunft)) + "] "
                + "Ausführender: " + sanieren(ausfuehrer == null ? "Unbekannt" : ausfuehrer) + " | "
                + "Ziel: " + sanieren(ziel == null ? "Unbekannt" : ziel) + " | "
                + "Details: " + sanieren(details == null ? "-" : details);
    }

    private static String normiereStempel(String roh) {
        if (roh == null || roh.isBlank()) {
            return de.lightrixx.punish.model.ServerScope.UNBEKANNT_WERT;
        }
        return roh.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String sanieren(String wert) {
        if (wert == null) {
            return "-";
        }
        StringBuilder bereinigt = new StringBuilder(wert.length());
        for (int i = 0; i < wert.length();) {
            int codePoint = wert.codePointAt(i);
            if (codePoint == '\r' || codePoint == '\n') {
                bereinigt.append(' ');
            } else if (codePoint == 0x1B) {
                bereinigt.append(' ');
            } else if ((codePoint < 0x20 && codePoint != '\t') || codePoint == 0x7F) {
                bereinigt.append(' ');
            } else {
                bereinigt.appendCodePoint(codePoint);
            }
            i += Character.charCount(codePoint);
        }
        String einzeilig = bereinigt.toString();
        int maxPoints = 500;
        int points = einzeilig.codePointCount(0, einzeilig.length());
        if (points <= maxPoints) {
            return einzeilig;
        }
        int end = einzeilig.offsetByCodePoints(0, maxPoints);
        return einzeilig.substring(0, end) + "…";
    }
}
