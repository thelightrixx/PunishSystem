package de.lightrixx.punish.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DateiSicherungTest {

    @TempDir
    File temp;

    @Test
    void configAbgleichSichertOriginalUndSchreibtNurBeiAenderungen() throws Exception {
        var datei = Files.writeString(temp.toPath().resolve("general.yml"), "alt: true\n");
        assertTrue(DateiSicherung.aktualisiereKonfiguration(datei, "alt: true\n", "alt: true\nneu: 7\n"));
        assertEquals("alt: true\n", Files.readString(temp.toPath().resolve("general.yml.vor-config-update.bak")));
        assertFalse(DateiSicherung.aktualisiereKonfiguration(datei, "alt: true\nneu: 7\n", "alt: true\nneu: 7\n"));
        assertEquals("alt: true\n", Files.readString(temp.toPath().resolve("general.yml.vor-config-update.bak")));
    }

    @Test
    void configAbgleichUeberschreibtKeineZwischenzeitlicheAenderung() throws Exception {
        var datei = Files.writeString(temp.toPath().resolve("general.yml"), "angepasst: true\n");
        assertThrows(java.io.IOException.class,
                () -> DateiSicherung.aktualisiereKonfiguration(datei, "alt: true\n", "neu: true\n"));
        assertEquals("angepasst: true\n", Files.readString(datei));
    }

    @Test
    void configAbgleichBrichtBeiFehlgeschlagenerSicherungOhneDateiaenderungAb() throws Exception {
        var datei = Files.writeString(temp.toPath().resolve("general.yml"), "alt: true\n");
        var backup = Files.createDirectory(temp.toPath().resolve("general.yml.vor-config-update.bak"));
        Files.writeString(backup.resolve("belegt"), "beibehalten");
        assertThrows(java.io.IOException.class,
                () -> DateiSicherung.aktualisiereKonfiguration(datei, "alt: true\n", "neu: true\n"));
        assertEquals("alt: true\n", Files.readString(datei));
        try (var dateien = Files.list(temp.toPath())) {
            assertTrue(dateien.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void bootBackupSichertHistoryUndArchiv() throws Exception {
        File geschichte = new File(temp, "history");
        File archiv = new File(geschichte, "archive");
        assertTrue(archiv.mkdirs(), "Archivordner muss anlegbar sein");
        File shard = new File(geschichte, "123e4567-e89b-12d3-a456-426614174000.yml");
        File archivDatei = new File(archiv, "123e4567-e89b-12d3-a456-426614174000-1-1-abcd1234.yml");
        Files.writeString(shard.toPath(), "eintraege: []\n");
        Files.writeString(archivDatei.toPath(), "eintraege: []\n");

        int anzahl = DateiSicherung.snapshotOrdnerFuerBoot(temp);

        assertEquals(2, anzahl, "Shard und Archivdatei müssen gesichert werden");
        assertTrue(new File(geschichte, shard.getName() + ".pre-boot.bak").isFile());
        assertTrue(new File(archiv, archivDatei.getName() + ".pre-boot.bak").isFile());
    }
}
