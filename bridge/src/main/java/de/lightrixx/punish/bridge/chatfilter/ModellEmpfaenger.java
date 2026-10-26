package de.lightrixx.punish.bridge.chatfilter;

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
import java.util.Set;

public final class ModellEmpfaenger {

    private static final Logger LOG = LoggerFactory.getLogger(ModellEmpfaenger.class);

    public interface Rueckmeldung {
        void quittung(String dateiname, boolean erfolg);

        void neuLaden();
    }

    private final ChatfilterKonfiguration konfiguration;
    private final Rueckmeldung rueckmeldung;
    private Transfer aktiv;

    private static final class Transfer {
        final String dateiname;
        final long groesse;
        final String sha256;
        final int anzahl;
        final File teilDatei;
        final OutputStream ausgabe;
        final MessageDigest digest;
        long geschrieben;
        int erwartet;

        Transfer(String dateiname, long groesse, String sha256,
                 int anzahl, File teilDatei, OutputStream ausgabe, MessageDigest digest) {
            this.dateiname = dateiname;
            this.groesse = groesse;
            this.sha256 = sha256;
            this.anzahl = anzahl;
            this.teilDatei = teilDatei;
            this.ausgabe = ausgabe;
            this.digest = digest;
        }
    }

    public ModellEmpfaenger(ChatfilterKonfiguration konfiguration, Rueckmeldung rueckmeldung) {
        this.konfiguration = Objects.requireNonNull(konfiguration, "Die Konfiguration darf nicht null sein.");
        this.rueckmeldung = Objects.requireNonNull(rueckmeldung, "Die Rückmeldung darf nicht null sein.");
    }

    private Set<String> erlaubteNamen() {
        try {
            return Set.of(konfiguration.modellDateiname(), konfiguration.vocabDateiname());
        } catch (Exception e) {
            LOG.debug("Erlaubte Modell-Namen konnten nicht gelesen werden.", e);
            return Set.of("modell_int8.onnx", "vocab.txt");
        }
    }

    public synchronized void empfangeMeta(Protokoll.ModellMeta meta) {
        Objects.requireNonNull(meta, "Die Meta darf nicht null sein.");
        abbrechen();
        if (!erlaubteNamen().contains(meta.dateiname())) {
            LOG.warn("Modell-Transfer abgewiesen (unerwartete Datei '{}').", meta.dateiname());
            meldeQuittung(meta.dateiname(), false);
            return;
        }
        try {
            File ziel = konfiguration.dateiAufloesen(meta.dateiname());
            File eltern = ziel.getParentFile();
            if (eltern != null) {
                eltern.mkdirs();
            }
            File teil = new File(eltern, meta.dateiname() + ".part");
            try {
                Files.deleteIfExists(teil.toPath());
            } catch (Exception e) {
                LOG.debug("Alte Part-Datei konnte nicht gelöscht werden.", e);
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            OutputStream ausgabe = Files.newOutputStream(teil.toPath());
            aktiv = new Transfer(meta.dateiname(), meta.groesse(), meta.sha256(),
                    meta.anzahl(), teil, ausgabe, digest);
            LOG.info("Modell-Transfer gestartet ('{}', {} Bytes in {} Blöcken).",
                    meta.dateiname(), meta.groesse(), meta.anzahl());
        } catch (Exception e) {
            LOG.warn("Modell-Transfer konnte nicht gestartet werden.", e);
            abbrechen();
            meldeQuittung(meta.dateiname(), false);
        }
    }

    public synchronized void empfangeBlock(Protokoll.ModellBlock block) {
        Objects.requireNonNull(block, "Der Block darf nicht null sein.");
        Transfer laufend = aktiv;
        if (laufend == null || !laufend.dateiname.equals(block.dateiname())) {
            LOG.debug("Modell-Block ohne Transfer verworfen.");
            return;
        }
        if (block.index() != laufend.erwartet) {
            LOG.warn("Modell-Transfer abgebrochen ('{}', Block {} statt {} erwartet).",
                    laufend.dateiname, block.index(), laufend.erwartet);
            abbrechen();
            meldeQuittung(laufend.dateiname, false);
            return;
        }
        if (laufend.geschrieben + block.daten().length > laufend.groesse) {
            LOG.warn("Modell-Transfer abgebrochen ('{}', zu viele Bytes).", laufend.dateiname);
            abbrechen();
            meldeQuittung(laufend.dateiname, false);
            return;
        }
        try {
            laufend.ausgabe.write(block.daten());
            laufend.digest.update(block.daten());
            laufend.geschrieben += block.daten().length;
            laufend.erwartet++;
        } catch (Exception e) {
            LOG.warn("Modell-Block konnte nicht geschrieben werden.", e);
            abbrechen();
            meldeQuittung(laufend.dateiname, false);
        }
    }

    public synchronized void empfangeFertig(Protokoll.ModellFertig fertig) {
        Objects.requireNonNull(fertig, "Das Fertig darf nicht null sein.");
        Transfer laufend = aktiv;
        if (laufend == null || !laufend.dateiname.equals(fertig.dateiname())) {
            LOG.debug("Modell-Fertig ohne Transfer verworfen.");
            return;
        }
        String name = laufend.dateiname;
        try {
            laufend.ausgabe.close();
        } catch (Exception e) {
            LOG.debug("Part-Datei konnte nicht geschlossen werden.", e);
        }
        aktiv = null;
        try {
            if (laufend.erwartet != laufend.anzahl || laufend.geschrieben != laufend.groesse
                    || laufend.groesse != fertig.groesse()
                    || !laufend.sha256.equalsIgnoreCase(fertig.sha256())) {
                LOG.warn("Modell-Transfer unvollständig ('{}'), verworfen.", name);
                loescheStill(laufend.teilDatei);
                meldeQuittung(name, false);
                return;
            }
            String hash = HexFormat.of().formatHex(laufend.digest.digest());
            if (!hash.equalsIgnoreCase(laufend.sha256)) {
                LOG.warn("Modell-Transfer Hash-Fehler ('{}'), verworfen.", name);
                loescheStill(laufend.teilDatei);
                meldeQuittung(name, false);
                return;
            }
            File ziel = konfiguration.dateiAufloesen(name);
            try {
                Files.move(laufend.teilDatei.toPath(), ziel.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException atomarNichtMoeglich) {
                LOG.debug("Atomarer Move nicht unterstützt, nutze regulären Move.", atomarNichtMoeglich);
                Files.move(laufend.teilDatei.toPath(), ziel.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            LOG.info("Modell-Datei übernommen ('{}', {} Bytes).", name, laufend.groesse);
            meldeQuittung(name, true);
            try {
                rueckmeldung.neuLaden();
            } catch (Exception e) {
                LOG.debug("Chatfilter-Reload nach Modell-Sync fehlgeschlagen.", e);
            }
        } catch (Exception e) {
            LOG.warn("Modell-Datei konnte nicht übernommen werden.", e);
            loescheStill(laufend.teilDatei);
            meldeQuittung(name, false);
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
            LOG.debug("Part-Datei konnte nicht geschlossen werden.", e);
        }
        loescheStill(laufend.teilDatei);
    }

    private void loescheStill(File datei) {
        try {
            if (datei != null) {
                Files.deleteIfExists(datei.toPath());
            }
        } catch (Exception e) {
            LOG.debug("Part-Datei konnte nicht gelöscht werden.", e);
        }
    }

    private void meldeQuittung(String dateiname, boolean erfolg) {
        try {
            rueckmeldung.quittung(dateiname, erfolg);
        } catch (Exception e) {
            LOG.debug("Modell-Quittung fehlgeschlagen.", e);
        }
    }
}
