package de.lightrixx.punish.velocity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SnakeYamlSpeicherV4Test {

    @TempDir
    File ordner;

    @Test
    @DisplayName("V4-12: drei Backup-Generationen überleben (kein Single-bak.1 mehr)")
    void dreiGenerationen() throws Exception {
        SnakeYamlSpeicher speicher = new SnakeYamlSpeicher();
        File datei = new File(ordner, "mutes.yml");
        for (int i = 1; i <= 4; i++) {
            Map<String, Object> daten = new LinkedHashMap<>();
            daten.put("stand", "v" + i);
            speicher.speichern(daten, datei);
        }
        assertTrue(new File(ordner, "mutes.yml.bak.1").exists(), ".bak.1 muss existieren");
        assertTrue(new File(ordner, "mutes.yml.bak.2").exists(), ".bak.2 muss existieren");
        assertTrue(new File(ordner, "mutes.yml.bak.3").exists(), ".bak.3 muss existieren");
        File bak1Datei = new File(ordner, "mutes.yml.bak.1");
        File bak2Datei = new File(ordner, "mutes.yml.bak.2");
        File bak3Datei = new File(ordner, "mutes.yml.bak.3");
        String bak3 = Files.readString(bak3Datei.toPath());
        String bak1 = Files.readString(bak1Datei.toPath());
        assertNotEquals(bak1, bak3, ".bak.1 und .bak.3 müssen verschiedene Generationen halten");
        assertEquals("v3", String.valueOf(speicher.laden(bak1Datei).get("stand")),
                ".bak.1 muss den Stand vor dem letzten Schreibvorgang halten (v3)");
        assertEquals("v2", String.valueOf(speicher.laden(bak2Datei).get("stand")),
                ".bak.2 muss die vorletzte Generation halten (v2)");
        assertEquals("v1", String.valueOf(speicher.laden(bak3Datei).get("stand")),
                ".bak.3 muss die älteste Generation halten (v1)");
    }

    @Test
    @DisplayName("A-016: wiederholtes Überschreiben bei bestehender Datei bleibt lesbar (kein offenes Handle aufs Ziel)")
    void ueberschreibenBeiBestehenderDatei() {
        SnakeYamlSpeicher speicher = new SnakeYamlSpeicher();
        File datei = new File(ordner, "bans.yml");
        for (int i = 1; i <= 3; i++) {
            Map<String, Object> daten = new LinkedHashMap<>();
            daten.put("stand", "v" + i);
            speicher.speichern(daten, datei);
            assertEquals("v" + i, String.valueOf(speicher.laden(datei).get("stand")),
                    "Stand v" + i + " muss nach dem Schreiben lesbar sein");
        }
        assertFalse(new File(ordner, "bans.yml.tmp").exists(), "kein .tmp-Rest");
    }

    @Test
    @DisplayName("V4-12: laden nach Rotation liefert aktuellen Stand")
    void ladenNachRotation() {
        SnakeYamlSpeicher speicher = new SnakeYamlSpeicher();
        File datei = new File(ordner, "bans.yml");
        Map<String, Object> daten = new LinkedHashMap<>();
        daten.put("a", "1");
        speicher.speichern(daten, datei);
        Map<String, Object> daten2 = new LinkedHashMap<>();
        daten2.put("a", "2");
        speicher.speichern(daten2, datei);
        Map<String, Object> geladen = speicher.laden(datei);
        assertEquals("2", String.valueOf(geladen.get("a")));
    }
}
