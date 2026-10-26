package de.lightrixx.punish.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DateiSicherungTest {

    @TempDir
    File temp;

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
