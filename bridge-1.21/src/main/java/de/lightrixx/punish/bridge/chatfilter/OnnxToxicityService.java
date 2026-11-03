package de.lightrixx.punish.bridge.chatfilter;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import de.lightrixx.punish.chatfilter.BertWordPieceTokenizer;
import de.lightrixx.punish.chatfilter.ChatfilterAusfall;
import de.lightrixx.punish.chatfilter.ToxizitaetsModell;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class OnnxToxicityService implements ToxizitaetsModell, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(OnnxToxicityService.class);

    private static final String EINGABE_IDS = "input_ids";
    private static final String EINGABE_MASKE = "attention_mask";
    private static final String EINGABE_TYPEN = "token_type_ids";
    private static final String AUSGABE = "logits";

    private final BertWordPieceTokenizer tokenizer;
    private final int maxZeichen;
    private final OrtEnvironment umgebung;
    private final OrtSession sitzung;
    private final Object sperre = new Object();

    private OnnxToxicityService(BertWordPieceTokenizer tokenizer, int maxZeichen,
                                OrtEnvironment umgebung, OrtSession sitzung) {
        this.tokenizer = tokenizer;
        this.maxZeichen = maxZeichen;
        this.umgebung = umgebung;
        this.sitzung = sitzung;
    }

    public static OnnxToxicityService laden(File modellDatei, BertWordPieceTokenizer tokenizer,
                                            int maxZeichen) {
        Objects.requireNonNull(modellDatei, "Die Modelldatei darf nicht null sein.");
        Objects.requireNonNull(tokenizer, "Der Tokenizer darf nicht null sein.");
        if (!modellDatei.isFile()) {
            throw new ChatfilterAusfall("Modelldatei fehlt: " + modellDatei.getAbsolutePath());
        }
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows")) {
            var runtime = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "msvcp140.dll");
            if (!java.nio.file.Files.isRegularFile(runtime) && System.getenv("SystemRoot") != null) {
                runtime = java.nio.file.Path.of(System.getenv("SystemRoot"), "System32", "msvcp140.dll");
            }
            if (java.nio.file.Files.isRegularFile(runtime)) {
                pruefeWindowsRuntime(runtime);
            }
        }
        long start = System.nanoTime();
        OrtEnvironment umgebung;
        try {
            umgebung = OrtEnvironment.getEnvironment();
        } catch (Exception e) {
            throw new ChatfilterAusfall("ONNX-Laufzeit konnte nicht gestartet werden.", e);
        }
        OrtSession sitzung;
        try (var optionen = new OrtSession.SessionOptions()) {
            sitzung = umgebung.createSession(modellDatei.getAbsolutePath(), optionen);
        } catch (Exception e) {
            throw new ChatfilterAusfall("Modell konnte nicht geladen werden: " + modellDatei.getName(), e);
        }
        LOG.info("KI-Modell geladen: {} ({} MB, {} ms).",
                modellDatei.getName(), modellDatei.length() / 1024L / 1024L,
                (System.nanoTime() - start) / 1_000_000L);
        return new OnnxToxicityService(tokenizer, Math.max(50, maxZeichen), umgebung, sitzung);
    }

    static void pruefeWindowsRuntime(java.nio.file.Path datei) {
        try {
            if (java.nio.file.Files.size(datei) > 16L * 1024L * 1024L) {
                throw new ChatfilterAusfall("C++-Laufzeitversion konnte nicht geprüft werden: " + datei);
            }
            var daten = java.nio.ByteBuffer.wrap(java.nio.file.Files.readAllBytes(datei))
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            // VS_FIXEDFILEINFO enthält die Dateiversion. Ältere MSVC-Mutex-Implementierungen
            // können ONNX nativ abstürzen lassen, bevor ein Java-catch greifen kann.
            for (int i = 0; i <= daten.limit() - 52; i++) {
                if (daten.getInt(i) == 0xFEEF04BD && daten.getInt(i + 4) == 0x00010000) {
                    int version = daten.getInt(i + 8);
                    int haupt = version >>> 16;
                    int neben = version & 0xFFFF;
                    if (haupt < 14 || (haupt == 14 && neben < 40)) {
                        throw new ChatfilterAusfall("ONNX benötigt Microsoft-C++-Laufzeit 14.40 oder neuer; "
                                + "gefunden " + haupt + "." + neben + " in " + datei
                                + ". Java-Laufzeit aktualisieren; Wortliste bleibt verfügbar.");
                    }
                    return;
                }
            }
            throw new ChatfilterAusfall("C++-Laufzeitversion konnte nicht geprüft werden: " + datei);
        } catch (java.io.IOException e) {
            throw new ChatfilterAusfall("C++-Laufzeit konnte nicht geprüft werden: " + datei, e);
        }
    }

    @Override
    public double bewerte(String text) {
        Objects.requireNonNull(text, "Der Text darf nicht null sein.");
        String gekuerzt = text.length() > maxZeichen ? text.substring(0, maxZeichen) : text;
        int[] ids = tokenizer.kodiere(gekuerzt);
        long[][] schablone = new long[1][ids.length];
        for (int i = 0; i < ids.length; i++) {
            schablone[0][i] = ids[i];
        }
        long[][] einsen = new long[1][ids.length];
        java.util.Arrays.fill(einsen[0], 1L);
        long[][] nullen = new long[1][ids.length];
        float[][] logits;
        synchronized (sperre) {
            try (OnnxTensor idsTensor = OnnxTensor.createTensor(umgebung, schablone);
                 OnnxTensor maskeTensor = OnnxTensor.createTensor(umgebung, einsen);
                 OnnxTensor typenTensor = OnnxTensor.createTensor(umgebung, nullen)) {
                Map<String, OnnxTensor> eingaben = new HashMap<>();
                eingaben.put(EINGABE_IDS, idsTensor);
                eingaben.put(EINGABE_MASKE, maskeTensor);
                eingaben.put(EINGABE_TYPEN, typenTensor);
                try (OrtSession.Result ergebnis = sitzung.run(eingaben)) {
                    Object wert = ergebnis.get(AUSGABE).get().getValue();
                    if (!(wert instanceof float[][] felder) || felder.length < 1 || felder[0].length < 2) {
                        throw new ChatfilterAusfall("Unerwartete Modellantwort.");
                    }
                    logits = felder;
                }
            } catch (OrtException e) {
                throw new ChatfilterAusfall("KI-Inferenz fehlgeschlagen.", e);
            } catch (ChatfilterAusfall e) {
                throw e;
            } catch (Exception e) {
                throw new ChatfilterAusfall("KI-Bewertung fehlgeschlagen.", e);
            }
        }
        return softmaxToxisch(logits);
    }

    private static double softmaxToxisch(float[][] logits) {
        double max = Math.max(logits[0][0], logits[0][1]);
        double sauber = Math.exp(logits[0][0] - max);
        double toxisch = Math.exp(logits[0][1] - max);
        return toxisch / (sauber + toxisch);
    }

    @Override
    public void close() {
        try {
            synchronized (sperre) {
                sitzung.close();
            }
        } catch (Exception e) {
            LOG.debug("ONNX-Sitzung konnte nicht geschlossen werden.", e);
        }
    }
}
