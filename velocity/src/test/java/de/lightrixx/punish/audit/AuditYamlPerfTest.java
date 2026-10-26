package de.lightrixx.punish.audit;

import de.lightrixx.punish.velocity.SnakeYamlSpeicher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuditYamlPerfTest {

    @Test
    @DisplayName("Perf: SnakeYamlSpeicher speichern/laden mit 10 000 Einträgen")
    void yamlSpeicher10k(@TempDir File ordner, TestReporter reporter) throws Exception {
        SnakeYamlSpeicher speicher = new SnakeYamlSpeicher();
        Map<String, Object> daten = new HashMap<>();
        for (int i = 0; i < 10_000; i++) {
            daten.put("ban-" + i, Map.of(
                    "spieler", "Spieler" + i,
                    "uuid", "00000000-0000-0000-0000-" + String.format("%012d", i),
                    "grund", "Grund " + i,
                    "ausfuehrer", "Mod",
                    "erstellt_am", 1700000000000L + i,
                    "ablauf_am", 1700086400000L + i,
                    "scope", "global"));
        }
        File datei = new File(ordner, "ban-data.yml");

        long startSchreiben = System.nanoTime();
        speicher.speichern(daten, datei);
        long schreibMillis = (System.nanoTime() - startSchreiben) / 1_000_000L;

        long startLesen = System.nanoTime();
        Map<String, Object> geladen = speicher.laden(datei);
        long leseMillis = (System.nanoTime() - startLesen) / 1_000_000L;

        long groesseBytes = Files.size(datei.toPath());
        reporter.publishEntry("AUDIT-PERF", "YamlSpeicher.speichern@10k=" + schreibMillis + " ms");
        reporter.publishEntry("AUDIT-PERF", "YamlSpeicher.laden@10k=" + leseMillis + " ms");
        reporter.publishEntry("AUDIT-PERF", "YamlSpeicher.dateigroesse@10k=" + groesseBytes + " bytes");

        assertEquals(10_000, geladen.size(), "Alle Einträge müssen den Zyklus überleben");
    }
}
