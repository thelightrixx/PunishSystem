package de.lightrixx.punish.velocity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateStagingTest {

    @Test
    void stagedStattErsetzt(@TempDir Path ordner) throws Exception {
        Path jar = ordner.resolve("PunishSystemVelocity.jar");
        byte[] original = "original-jar".getBytes(StandardCharsets.UTF_8);
        Files.write(jar, original);
        byte[] update = "neue-version-bytes".getBytes(StandardCharsets.UTF_8);

        Path bereit = VelocityUpdateBefehl.legeUpdateBereit(ordner, update, "4.1");

        assertArrayEquals(original, Files.readAllBytes(jar),
                "Laufende JAR darf nicht überschrieben werden (B-010)");
        assertTrue(Files.isRegularFile(bereit), "Staged-Datei vorhanden");
        assertTrue(bereit.getParent().getFileName().toString().equals("update-pending"));
        assertArrayEquals(update, Files.readAllBytes(bereit));
        assertEquals(0, Files.list(ordner).filter(p -> p.getFileName().toString().endsWith(".bak")
                || p.getFileName().toString().endsWith(".new")).count(),
                "Kein .bak/.new neben der laufenden JAR");
    }

    @Test
    void versionSaniert() throws Exception {
        Path ziel = VelocityUpdateBefehl.legeUpdateBereit(
                Files.createTempDirectory("staging").toAbsolutePath(),
                new byte[]{1, 2, 3}, "../böse:version");
        assertTrue(ziel.getFileName().toString().startsWith("PunishSystemVelocity-"));
        assertTrue(!ziel.getFileName().toString().contains("/"));
    }
}
