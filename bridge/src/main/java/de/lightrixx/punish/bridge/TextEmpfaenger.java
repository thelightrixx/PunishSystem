package de.lightrixx.punish.bridge;

import de.lightrixx.punish.bridge.chatfilter.ChatfilterKonfiguration;
import de.lightrixx.punish.net.Protokoll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Empfängt Textdateien vom Proxy (Text-Sync, Proxy = Master).
 * YML-Dateien landen im Cache-Ordner config-sync/ und werden beim Laden
 * über die lokalen Dateien gelegt; wortliste.txt ersetzt die lokale Liste.
 * Erst bei passendem Paket-Hash (TEXT_FERTIG) wird übernommen + neu geladen.
 */
public final class TextEmpfaenger {

    private static final Logger LOG = LoggerFactory.getLogger(TextEmpfaenger.class);

    public static final String SYNC_ORDNER = "config-sync";

    public interface Rueckmeldung {
        void quittung(String paketHash, boolean erfolg);

        void neuLaden();
    }

    private static final class Transfer {
        final String dateiname;
        final long groesse;
        final String sha256;
        final int anzahl;
        final java.io.ByteArrayOutputStream ausgabe;
        final MessageDigest digest;
        long geschrieben;
        int erwartet;

        Transfer(String dateiname, long groesse, String sha256,
                 int anzahl, java.io.ByteArrayOutputStream ausgabe, MessageDigest digest) {
            this.dateiname = dateiname;
            this.groesse = groesse;
            this.sha256 = sha256;
            this.anzahl = anzahl;
            this.ausgabe = ausgabe;
            this.digest = digest;
        }
    }

    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final ChatfilterKonfiguration konfiguration;
    private final Rueckmeldung rueckmeldung;
    private final Map<String, Transfer> aktiv = new LinkedHashMap<>();

    public TextEmpfaenger(org.bukkit.plugin.java.JavaPlugin plugin,
                          ChatfilterKonfiguration konfiguration, Rueckmeldung rueckmeldung) {
        this.plugin = Objects.requireNonNull(plugin, "Das Plugin darf nicht null sein.");
        this.konfiguration = Objects.requireNonNull(konfiguration, "Die Konfiguration darf nicht null sein.");
        this.rueckmeldung = Objects.requireNonNull(rueckmeldung, "Die Rückmeldung darf nicht null sein.");
    }

    private File cacheOrdner() {
        File ordner = new File(plugin.getDataFolder(), SYNC_ORDNER);
        if (!ordner.exists()) {
            ordner.mkdirs();
        }
        return ordner;
    }

    public synchronized void empfangeMeta(Protokoll.TextMeta meta) {
        Objects.requireNonNull(meta, "Die Meta darf nicht null sein.");
        abbrechenStill(meta.dateiname());
        if (!Protokoll.TEXT_DATEINAMEN.contains(meta.dateiname())) {
            LOG.warn("Text-Transfer abgewiesen (unerwartete Datei '{}').", meta.dateiname());
            meldeQuittung("", false);
            return;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            var ausgabe = new java.io.ByteArrayOutputStream((int) Math.min(meta.groesse(), 262144L));
            aktiv.put(meta.dateiname(), new Transfer(meta.dateiname(), meta.groesse(),
                    meta.sha256(), meta.anzahl(), ausgabe, digest));
            LOG.info("Text-Transfer gestartet ('{}', {} Bytes in {} Blöcken).",
                    meta.dateiname(), meta.groesse(), meta.anzahl());
        } catch (Exception e) {
            LOG.warn("Text-Transfer konnte nicht gestartet werden.", e);
            abbrechenStill(meta.dateiname());
            meldeQuittung("", false);
        }
    }

    public synchronized void empfangeBlock(Protokoll.TextBlock block) {
        Objects.requireNonNull(block, "Der Block darf nicht null sein.");
        Transfer laufend = aktiv.get(block.dateiname());
        if (laufend == null) {
            LOG.debug("Text-Block ohne Transfer verworfen.");
            return;
        }
        if (block.index() != laufend.erwartet) {
            LOG.warn("Text-Transfer abgebrochen ('{}', Block {} statt {} erwartet).",
                    laufend.dateiname, block.index(), laufend.erwartet);
            abbrechenStill(laufend.dateiname);
            meldeQuittung("", false);
            return;
        }
        if (laufend.geschrieben + block.daten().length > laufend.groesse) {
            LOG.warn("Text-Transfer abgebrochen ('{}', zu viele Bytes).", laufend.dateiname);
            abbrechenStill(laufend.dateiname);
            meldeQuittung("", false);
            return;
        }
        try {
            laufend.ausgabe.write(block.daten());
            laufend.digest.update(block.daten());
            laufend.geschrieben += block.daten().length;
            laufend.erwartet++;
        } catch (Exception e) {
            LOG.warn("Text-Block konnte nicht geschrieben werden.", e);
            abbrechenStill(laufend.dateiname);
            meldeQuittung("", false);
        }
    }

    public synchronized void empfangeFertig(Protokoll.TextFertig fertig) {
        Objects.requireNonNull(fertig, "Das Fertig darf nicht null sein.");
        if (aktiv.isEmpty()) {
            LOG.debug("Text-Fertig ohne Transfer verworfen.");
            return;
        }
        Map<String, byte[]> runde = new LinkedHashMap<>();
        try {
            for (var eintrag : aktiv.entrySet()) {
                Transfer laufend = eintrag.getValue();
                laufend.ausgabe.close();
                if (laufend.erwartet != laufend.anzahl || laufend.geschrieben != laufend.groesse) {
                    LOG.warn("Text-Transfer unvollständig ('{}'), Runde verworfen.", laufend.dateiname);
                    aktiv.clear();
                    meldeQuittung("", false);
                    return;
                }
                String hash = HexFormat.of().formatHex(laufend.digest.digest());
                if (!hash.equalsIgnoreCase(laufend.sha256)) {
                    LOG.warn("Text-Transfer Hash-Fehler ('{}'), Runde verworfen.", laufend.dateiname);
                    aktiv.clear();
                    meldeQuittung("", false);
                    return;
                }
                runde.put(laufend.dateiname, laufend.ausgabe.toByteArray());
            }
        } catch (Exception e) {
            LOG.warn("Text-Transfer konnte nicht abgeschlossen werden.", e);
            aktiv.clear();
            meldeQuittung("", false);
            return;
        }
        String rundeHash = Protokoll.textPaketHash(runde);
        if (!rundeHash.equalsIgnoreCase(fertig.paketHash())) {
            LOG.warn("Text-Paket unvollständig (Hash weicht ab), Runde verworfen.");
            aktiv.clear();
            meldeQuittung("", false);
            return;
        }
        try {
            uebernehmen(runde);
        } catch (Exception e) {
            LOG.warn("Text-Dateien konnten nicht übernommen werden.", e);
            aktiv.clear();
            meldeQuittung(fertig.paketHash(), false);
            return;
        }
        aktiv.clear();
        meldeQuittung(fertig.paketHash(), true);
        try {
            rueckmeldung.neuLaden();
        } catch (Exception e) {
            LOG.debug("Text-Reload nach Sync fehlgeschlagen.", e);
        }
    }

    private void uebernehmen(Map<String, byte[]> runde) throws Exception {
        File cache = cacheOrdner();
        for (var eintrag : runde.entrySet()) {
            String name = eintrag.getKey();
            byte[] daten = eintrag.getValue();
            File ziel;
            if ("wortliste.txt".equals(name)) {
                ziel = konfiguration.dateiAufloesen(konfiguration.wortlisteDateiname());
            } else {
                ziel = new File(cache, name);
            }
            File eltern = ziel.getParentFile();
            if (eltern != null && !eltern.exists()) {
                eltern.mkdirs();
            }
            File tmp = new File(eltern, name + ".part");
            Files.write(tmp.toPath(), daten);
            try {
                Files.move(tmp.toPath(), ziel.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                Files.move(tmp.toPath(), ziel.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            LOG.info("Text-Datei übernommen ('{}', {} Bytes).", name, daten.length);
        }
    }

    public synchronized void abbrechen() {
        aktiv.clear();
    }

    private void abbrechenStill(String dateiname) {
        try {
            Transfer laufend = aktiv.remove(dateiname);
            if (laufend != null) {
                laufend.ausgabe.close();
            }
        } catch (Exception e) {
            LOG.debug("Text-Transfer konnte nicht abgebrochen werden.", e);
        }
    }

    private void meldeQuittung(String paketHash, boolean erfolg) {
        try {
            rueckmeldung.quittung(paketHash, erfolg);
        } catch (Exception e) {
            LOG.debug("Text-Quittung fehlgeschlagen.", e);
        }
    }
}
