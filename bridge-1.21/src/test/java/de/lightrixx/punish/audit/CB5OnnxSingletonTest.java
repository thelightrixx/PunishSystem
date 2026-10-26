package de.lightrixx.punish.audit;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import de.lightrixx.punish.bridge.chatfilter.OnnxToxicityService;
import de.lightrixx.punish.chatfilter.BertWordPieceTokenizer;
import de.lightrixx.punish.chatfilter.ChatfilterAusfall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CB5OnnxSingletonTest {

    private static boolean nativenVerfuegbar() {
        try (var optionen = new OrtSession.SessionOptions()) {
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Test
    @DisplayName("B5: Fehlgeschlagene Session-Erzeugung schließt die geteilte Umgebung nicht")
    void ladefehlerLaesstUmgebungOffen() throws Exception {
        assumeTrue(nativenVerfuegbar(), "Keine ONNX-Natives in dieser Umgebung.");
        File modell = Files.createTempFile("punish-modell", ".onnx").toFile();
        Files.write(modell.toPath(), new byte[]{1, 2, 3, 4});
        var tokenizer = BertWordPieceTokenizer.ausZeilen(List.of("[UNK]", "[CLS]", "[SEP]", "a", "b"), 128);
        try (var statisch = org.mockito.Mockito.mockStatic(OrtEnvironment.class)) {
            OrtEnvironment umgebung = mock(OrtEnvironment.class);
            statisch.when(OrtEnvironment::getEnvironment).thenReturn(umgebung);
            when(umgebung.createSession(anyString(), any(OrtSession.SessionOptions.class)))
                    .thenThrow(new OrtException("kaputt"));

            assertThrows(ChatfilterAusfall.class,
                    () -> OnnxToxicityService.laden(modell, tokenizer, 500));

            verify(umgebung, never()).close();
        } finally {
            modell.delete();
        }
    }

    @Test
    @DisplayName("C-001: close() schließt nur die Sitzung, nie die geteilte Umgebung (Reload-sicher)")
    void closeLaesstGeteilteUmgebungOffen() throws Exception {
        assumeTrue(nativenVerfuegbar(), "Keine ONNX-Natives in dieser Umgebung.");
        var umgebung = mock(OrtEnvironment.class);
        var sitzung = mock(OrtSession.class);
        var konst = OnnxToxicityService.class.getDeclaredConstructor(
                BertWordPieceTokenizer.class, int.class, OrtEnvironment.class, OrtSession.class);
        konst.setAccessible(true);
        var tokenizer = BertWordPieceTokenizer.ausZeilen(List.of("[UNK]", "[CLS]", "[SEP]", "a", "b"), 128);
        OnnxToxicityService dienst =
                konst.newInstance(tokenizer, 500, umgebung, sitzung);
        dienst.close();
        verify(sitzung).close();
        verify(umgebung, never()).close();
    }
}
