package de.lightrixx.punish.storage;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression fuer S2-12: Doppelkodierte Umlaute in Quellfiles.
 * Wenn UTF-8-Bytes als Latin-1/CP1252 gelesen und erneut als UTF-8 gespeichert werden,
 * entstehen die Fehlsequenzen der Familien C3.xx ("Ãö"), C2.xx ("Â..") und E2.. ("âeu").
 * Die drei Lead-Zeichen U+00C3, U+00C2 und U+00E2 kommen im Projektquellcode sonst nicht vor.
 */
class MojibakeRegressionTest {

    @Test
    void quellcodeEnthaeltKeineDoppelkodierteUmlautSequenzen() throws IOException {
        Path basis = Path.of("src", "main", "java");
        assertTrue(Files.isDirectory(basis), "Test braucht common-Modulverzeichnis als Arbeitsverzeichnis.");
        List<Path> javaDateien = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(basis)) {
            walk.filter(datei -> datei.toString().endsWith(".java")).sorted().forEach(javaDateien::add);
        }
        assertFalse(javaDateien.isEmpty(), "Keine Quelldateien gefunden.");

        List<String> verstandeneTreffer = new ArrayList<>();
        for (Path datei : javaDateien) {
            String text = new String(Files.readAllBytes(datei), StandardCharsets.UTF_8);
            if (enthaeltMojibake(text)) {
                verstandeneTreffer.add(datei.toString());
            }
        }
        assertTrue(verstandeneTreffer.isEmpty(), () -> "Doppelkodierte Umlaute in: " + verstandeneTreffer);
    }

    private static boolean enthaeltMojibake(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == 0xC3 || c == 0xC2 || c == 0xE2) {
                return true;
            }
        }
        return false;
    }
}
