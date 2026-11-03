package de.lightrixx.punish.bridge.chatfilter;

import de.lightrixx.punish.chatfilter.BertWordPieceTokenizer;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class OnnxToxicityServiceTest {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"14,29,false", "14,39,false", "14,40,true", "14,51,true", "15,0,true"})
    void windowsRuntimeWirdVorDemNativenStartGeprueft(int haupt, int neben, boolean erlaubt,
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path ordner) throws Exception {
        var datei = ordner.resolve("msvcp140.dll");
        var daten = java.nio.ByteBuffer.allocate(80).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        daten.putInt(8, 0xFEEF04BD);
        daten.putInt(12, 0x00010000);
        daten.putInt(16, (haupt << 16) | neben);
        Files.write(datei, daten.array());
        if (erlaubt) {
            assertDoesNotThrow(() -> OnnxToxicityService.pruefeWindowsRuntime(datei));
        } else {
            var fehler = assertThrows(de.lightrixx.punish.chatfilter.ChatfilterAusfall.class,
                    () -> OnnxToxicityService.pruefeWindowsRuntime(datei));
            assertTrue(fehler.getMessage().contains("14.40"));
        }
    }

    @Test
    void unlesbareRuntimeWirdNichtNativGestartet(@org.junit.jupiter.api.io.TempDir java.nio.file.Path ordner)
            throws Exception {
        var datei = ordner.resolve("msvcp140.dll");
        Files.writeString(datei, "keine Versionsressource");
        assertThrows(de.lightrixx.punish.chatfilter.ChatfilterAusfall.class,
                () -> OnnxToxicityService.pruefeWindowsRuntime(datei));
    }

    private static File modellDatei() {
        String pfad = System.getenv("PUNISH_CHATFILTER_MODELL");
        assumeTrue(pfad != null && !pfad.isBlank(), "Kein Modellpfad gesetzt (PUNISH_CHATFILTER_MODELL).");
        File datei = new File(pfad);
        assumeTrue(datei.isFile(), "Modelldatei fehlt.");
        return datei;
    }

    private static File vocabDatei() {
        String pfad = System.getenv("PUNISH_CHATFILTER_VOCAB");
        assumeTrue(pfad != null && !pfad.isBlank(), "Kein Vocab-Pfad gesetzt (PUNISH_CHATFILTER_VOCAB).");
        File datei = new File(pfad);
        assumeTrue(datei.isFile(), "Vocab-Datei fehlt.");
        return datei;
    }

    @Test
    void echteInferenz_trenntToxischUndSauber() throws Exception {
        List<String> vocab = Files.readAllLines(vocabDatei().toPath(), StandardCharsets.UTF_8);
        var tokenizer = BertWordPieceTokenizer.ausZeilen(vocab, 128);
        try (var dienst = OnnxToxicityService.laden(modellDatei(), tokenizer, 500)) {
            long start = System.nanoTime();
            double toxisch = dienst.bewerte("Verpiss dich vom Server, du Hurensohn");
            double sauber = dienst.bewerte("Hey, willkommen auf dem Server!");
            long millis = (System.nanoTime() - start) / 1_000_000L / 2;
            assertTrue(toxisch >= 0.7d, "Toxisch erwartet, war " + toxisch);
            assertTrue(sauber <= 0.3d, "Sauber erwartet, war " + sauber);
            assertTrue(millis < 150L, "Zu langsam: " + millis + "ms");
        }
    }
}
