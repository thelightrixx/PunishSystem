package de.lightrixx.punish.chatfilter;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BertWordPieceTokenizerTest {

    /** Referenz-Vokabular (30000 Zeilen) nur noch ausserhalb des Repos:
     *  Pfad per PUNISH_CHATFILTER_VOCAB (siehe scripts/chatfilter-modell-laden.ps1). */
    private static BertWordPieceTokenizer tokenizer;
    private static boolean echtesVokabular;

    @BeforeAll
    static void ladeVokabular() throws Exception {
        List<String> zeilen = ladeEchtesVokabular();
        echtesVokabular = zeilen != null;
        if (zeilen == null) {
            zeilen = List.of("[PAD]", "[UNK]", "[CLS]", "[SEP]", "hallo", "welt", "##e");
        }
        tokenizer = BertWordPieceTokenizer.ausZeilen(zeilen, 128);
    }

    private static List<String> ladeEchtesVokabular() throws IOException {
        String pfad = System.getenv("PUNISH_CHATFILTER_VOCAB");
        if (pfad == null || pfad.isBlank()) {
            return null;
        }
        Path datei = Path.of(pfad);
        if (!Files.isRegularFile(datei)) {
            return null;
        }
        List<String> zeilen = new ArrayList<>();
        try (var reader = Files.newBufferedReader(datei, StandardCharsets.UTF_8)) {
            String zeile;
            while ((zeile = reader.readLine()) != null) {
                zeilen.add(zeile.strip());
            }
        }
        assertEquals(30000, zeilen.size(), "Referenz-Vokabular muss 30000 Zeilen haben.");
        return zeilen;
    }

    @Test
    void paritaet_mitReferenzTokenizer_eins() {
        org.junit.jupiter.api.Assumptions.assumeTrue(echtesVokabular,
                "Kein Referenz-Vokabular (PUNISH_CHATFILTER_VOCAB) – Paritaetstest uebersprungen.");
        assertArrayEquals(new int[]{3, 3112, 23267, 181, 39, 4547, 5168, 6, 17034, 11643,
                        26918, 16092, 7528, 30, 16332, 145, 4},
                tokenizer.kodiere("Du bist so ein dummer Idiot, halt endlich die Fresse"));
    }

    @Test
    void paritaet_mitReferenzTokenizer_zwei() {
        org.junit.jupiter.api.Assumptions.assumeTrue(echtesVokabular,
                "Kein Referenz-Vokabular (PUNISH_CHATFILTER_VOCAB) – Paritaetstest uebersprungen.");
        assertArrayEquals(new int[]{3, 23466, 689, 15894, 275, 23092, 26918, 4547, 15538, 542, 405, 4},
                tokenizer.kodiere("Verpiss dich vom Server, du Hurensohn"));
    }

    @Test
    void leererText_nurSonderzeichen() {
        int[] leer = tokenizer.kodiere("");
        assertEquals(2, leer.length);
        assertArrayEquals(leer, tokenizer.kodiere("   "));
    }

    @Test
    void langtext_wirdGekuerzt() {
        String text = "Hallo Welt ".repeat(200);
        assertEquals(128, tokenizer.kodiere(text).length);
    }

    @Test
    void unbekanntesZeichen_ergibtUnk() {
        int[] mitEmoji = tokenizer.kodiere("Hallo \uD83D\uDE00 Welt");
        int[] ohneEmoji = tokenizer.kodiere("Hallo Welt");
        assertNotEquals(ohneEmoji.length, mitEmoji.length);

        BertWordPieceTokenizer klein = BertWordPieceTokenizer.ausZeilen(
                List.of("[PAD]", "[UNK]", "[CLS]", "[SEP]", "hallo", "welt"), 32);
        assertArrayEquals(new int[]{2, 4, 1, 5, 3}, klein.kodiere("hallo \uD83D\uDE00 welt"),
                "Das Emoji muss als [UNK] (ID 1) kodiert werden.");
        assertArrayEquals(new int[]{2, 4, 5, 3}, klein.kodiere("hallo welt"),
                "Ohne unbekanntes Zeichen darf kein [UNK] entstehen.");
    }
}
