package de.lightrixx.punish.bridge;

import de.lightrixx.punish.net.Protokoll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

public final class JarEmpfaenger {

    private static final Logger LOG = LoggerFactory.getLogger(JarEmpfaenger.class);

    private static final java.util.regex.Pattern VARIANTE_MUSTER =
            java.util.regex.Pattern.compile("[a-z0-9.-]{1,32}");
    private static final java.util.regex.Pattern VERSION_MUSTER =
            java.util.regex.Pattern.compile("[A-Za-z0-9._+~-]{1,64}");

    public interface Rueckmeldung {
        void quittung(String variante, String version, boolean erfolg);

        void standMelden();
    }

    private final File updateOrdner;
    private final Rueckmeldung rueckmeldung;
    private Transfer aktiv;

    private static final class Transfer {
        final String variante;
        final String version;
        final long groesse;
        final String sha256;
        final int anzahl;
        final File teilDatei;
        final OutputStream ausgabe;
        final MessageDigest digest;
        long geschrieben;
        int erwartet;

        Transfer(String variante, String version, long groesse, String sha256, int anzahl,
                 File teilDatei, OutputStream ausgabe, MessageDigest digest) {
            this.variante = variante;
            this.version = version;
            this.groesse = groesse;
            this.sha256 = sha256;
            this.anzahl = anzahl;
            this.teilDatei = teilDatei;
            this.ausgabe = ausgabe;
            this.digest = digest;
        }
    }

    public JarEmpfaenger(File updateOrdner, Rueckmeldung rueckmeldung) {
        this.updateOrdner = Objects.requireNonNull(updateOrdner, "Der Update-Ordner darf nicht null sein.");
        this.rueckmeldung = Objects.requireNonNull(rueckmeldung, "Die Rückmeldung darf nicht null sein.");
    }

    public synchronized void empfangeMeta(Protokoll.UpdateMeta meta) {
        Objects.requireNonNull(meta, "Die Meta darf nicht null sein.");
        abbrechen();
        if (!istEmpfangsZulaessig(meta.variante(), meta.version())) {
            LOG.warn("Update-Meta mit unzulässigem Dateinamen abgewiesen (Variante '{}', Version '{}').",
                    meta.variante(), meta.version());
            meldeQuittung(meta.variante(), meta.version(), false);
            return;
        }
        try {
            if (!updateOrdner.exists()) {
                updateOrdner.mkdirs();
            }
            File teil = new File(updateOrdner, dateinameFuer(meta.variante(), meta.version()) + ".part");
            try {
                Files.deleteIfExists(teil.toPath());
            } catch (Exception e) {
                LOG.debug("Alte Update-Part-Datei konnte nicht gelöscht werden.", e);
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            OutputStream ausgabe = Files.newOutputStream(teil.toPath());
            aktiv = new Transfer(meta.variante(), meta.version(), meta.groesse(), meta.sha256(),
                    meta.anzahl(), teil, ausgabe, digest);
            LOG.info("Update-Transfer gestartet (Variante '{}', Version '{}', {} Bytes in {} Blöcken).",
                    meta.variante(), meta.version(), meta.groesse(), meta.anzahl());
        } catch (Exception e) {
            LOG.warn("Update-Transfer konnte nicht gestartet werden.", e);
            abbrechen();
            meldeQuittung(meta.variante(), meta.version(), false);
        }
    }

    public synchronized void empfangeBlock(Protokoll.UpdateBlock block) {
        Objects.requireNonNull(block, "Der Block darf nicht null sein.");
        Transfer laufend = aktiv;
        if (laufend == null || !laufend.variante.equals(block.variante())) {
            LOG.debug("Update-Block ohne Transfer verworfen.");
            return;
        }
        if (block.index() != laufend.erwartet) {
            LOG.warn("Update-Transfer abgebrochen (Block {} statt {} erwartet).",
                    block.index(), laufend.erwartet);
            abbrechen();
            meldeQuittung(laufend.variante, laufend.version, false);
            return;
        }
        if (laufend.geschrieben + block.daten().length > laufend.groesse) {
            LOG.warn("Update-Transfer abgebrochen (zu viele Bytes).");
            abbrechen();
            meldeQuittung(laufend.variante, laufend.version, false);
            return;
        }
        try {
            laufend.ausgabe.write(block.daten());
            laufend.digest.update(block.daten());
            laufend.geschrieben += block.daten().length;
            laufend.erwartet++;
        } catch (Exception e) {
            LOG.warn("Update-Block konnte nicht geschrieben werden.", e);
            abbrechen();
            meldeQuittung(laufend.variante, laufend.version, false);
        }
    }

    public synchronized void empfangeFertig(Protokoll.UpdateFertig fertig) {
        Objects.requireNonNull(fertig, "Das Fertig darf nicht null sein.");
        Transfer laufend = aktiv;
        if (laufend == null || !laufend.variante.equals(fertig.variante())) {
            LOG.debug("Update-Fertig ohne Transfer verworfen.");
            return;
        }
        String variante = laufend.variante;
        String version = laufend.version;
        try {
            laufend.ausgabe.close();
        } catch (Exception e) {
            LOG.debug("Update-Part-Datei konnte nicht geschlossen werden.", e);
        }
        aktiv = null;
        try {
            if (laufend.erwartet != laufend.anzahl || laufend.geschrieben != laufend.groesse
                    || laufend.groesse != fertig.groesse()
                    || !laufend.sha256.equalsIgnoreCase(fertig.sha256())
                    || !version.equals(fertig.version())) {
                LOG.warn("Update-Transfer unvollständig, verworfen.");
                loescheStill(laufend.teilDatei);
                meldeQuittung(variante, version, false);
                return;
            }
            String hash = HexFormat.of().formatHex(laufend.digest.digest());
            if (!hash.equalsIgnoreCase(laufend.sha256)) {
                LOG.warn("Update-Transfer Hash-Fehler, verworfen.");
                loescheStill(laufend.teilDatei);
                meldeQuittung(variante, version, false);
                return;
            }
            File ziel = new File(updateOrdner, dateinameFuer(variante, version));
            try {
                Files.move(laufend.teilDatei.toPath(), ziel.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                Files.move(laufend.teilDatei.toPath(), ziel.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            LOG.info("Update-Datei übernommen ('{}', {} Bytes). Bitte Server neustarten.", ziel.getName(), laufend.groesse);
            meldeQuittung(variante, version, true);
            try {
                rueckmeldung.standMelden();
            } catch (Exception e) {
                LOG.debug("Update-Stand-Meldung fehlgeschlagen.", e);
            }
        } catch (Exception e) {
            LOG.warn("Update-Datei konnte nicht übernommen werden.", e);
            loescheStill(laufend.teilDatei);
            meldeQuittung(variante, version, false);
        }
    }

    public synchronized void abbrechen() {
        Transfer laufend = aktiv;
        aktiv = null;
        if (laufend == null) {
            return;
        }
        try {
            laufend.ausgabe.close();
        } catch (Exception e) {
            LOG.debug("Update-Part-Datei konnte nicht geschlossen werden.", e);
        }
        loescheStill(laufend.teilDatei);
    }

    static boolean istEmpfangsZulaessig(String variante, String version) {
        String v = variante == null || variante.isBlank()
                ? "bridge" : variante.trim().toLowerCase(java.util.Locale.ROOT);
        String ver = version == null || version.isBlank() ? "unbekannt" : version.trim();
        return VARIANTE_MUSTER.matcher(v).matches() && VERSION_MUSTER.matcher(ver).matches();
    }

    public static String dateinameFuer(String variante, String version) {
        String v = variante == null || variante.isBlank() ? "bridge" : variante.trim().toLowerCase(java.util.Locale.ROOT);
        String ver = version == null || version.isBlank() ? "unbekannt" : version.trim();
        return "PunishSystem-" + ver + "-" + v + ".jar";
    }

    private void loescheStill(File datei) {
        try {
            if (datei != null) {
                Files.deleteIfExists(datei.toPath());
            }
        } catch (Exception e) {
            LOG.debug("Update-Part-Datei konnte nicht gelöscht werden.", e);
        }
    }

    private void meldeQuittung(String variante, String version, boolean erfolg) {
        try {
            rueckmeldung.quittung(variante, version, erfolg);
        } catch (Exception e) {
            LOG.debug("Update-Quittung fehlgeschlagen.", e);
        }
    }
}
