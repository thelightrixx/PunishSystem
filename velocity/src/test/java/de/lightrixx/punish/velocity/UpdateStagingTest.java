package de.lightrixx.punish.velocity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void startHookInstalliertMitSicherungUndIstWiederholbar(@TempDir Path ordner) throws Exception {
        Path jar = ordner.resolve("PunishSystem v4.1.1-(velocity).jar");
        byte[] original = "laufende-version".getBytes(StandardCharsets.UTF_8);
        byte[] update = "neue-version".getBytes(StandardCharsets.UTF_8);
        Files.write(jar, original);

        Path bereit = VelocityUpdateBefehl.bereiteInstallationVor(jar, update, "v4.2.0");
        assertArrayEquals(original, Files.readAllBytes(jar));
        Path updates = bereit.getParent();
        assertEquals(0, startHook(updates));
        assertArrayEquals(update, Files.readAllBytes(jar));
        assertArrayEquals(original, Files.readAllBytes(updates.resolve("previous.jar.bak")));
        assertFalse(Files.exists(bereit));
        assertFalse(Files.exists(updates.resolve("install.txt")));
        assertEquals(0, startHook(updates));
        assertArrayEquals(original, Files.readAllBytes(updates.resolve("previous.jar.bak")));
        try (var dateien = Files.list(ordner)) {
            assertEquals(1, dateien.filter(p -> p.getFileName().toString().endsWith(".jar")).count());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"hash", "datei-fehlt", "pfad", "sicherung"})
    void startHookBrichtOhneAustauschAb(String fehler, @TempDir Path ordner) throws Exception {
        Path jar = ordner.resolve("PunishSystemVelocity.jar");
        byte[] original = "laufende-version".getBytes(StandardCharsets.UTF_8);
        Files.write(jar, original);
        Path bereit = VelocityUpdateBefehl.bereiteInstallationVor(jar, new byte[]{1, 2, 3}, "v4.2.0");
        Path updates = bereit.getParent();
        Path auftrag = updates.resolve("install.txt");
        Files.writeString(ordner.resolve("starte-testproxy.sh"), "touch gestartet\n");
        switch (fehler) {
            case "hash" -> Files.write(bereit, new byte[]{9});
            case "datei-fehlt" -> Files.delete(bereit);
            case "pfad" -> Files.writeString(auftrag, Files.readString(auftrag)
                    .replace("PunishSystemVelocity.jar\n", "../fremdes-plugin.jar\n"));
            case "sicherung" -> Files.createDirectory(updates.resolve("previous.jar.bak"));
            default -> throw new AssertionError(fehler);
        }

        assertEquals(1, startHook(updates, "sh", "starte-testproxy.sh"));
        assertArrayEquals(original, Files.readAllBytes(jar));
        assertTrue(Files.exists(auftrag), "Fehlgeschlagene Updates müssen vorgemerkt bleiben.");
        assertFalse(Files.exists(ordner.resolve("gestartet")), "Java darf nach einem Update-Fehler nicht starten.");
    }

    @Test
    void startHookErkenntBereitsErsetzteJar(@TempDir Path ordner) throws Exception {
        Path jar = ordner.resolve("PunishSystemVelocity.jar");
        Files.write(jar, new byte[]{0});
        byte[] update = new byte[]{1, 2, 3};
        Path bereit = VelocityUpdateBefehl.bereiteInstallationVor(jar, update, "v4.2.0");
        Files.move(bereit, jar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        assertEquals(0, startHook(bereit.getParent()));
        assertArrayEquals(update, Files.readAllBytes(jar));
        assertFalse(Files.exists(bereit.getParent().resolve("install.txt")));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void startHookStartetProxyImUrspruenglichenOrdner(boolean mitUpdate, @TempDir Path ordner) throws Exception {
        Path jar = ordner.resolve("PunishSystemVelocity.jar");
        Files.writeString(jar, "original");
        if (mitUpdate) {
            VelocityUpdateBefehl.bereiteInstallationVor(jar, "neu".getBytes(StandardCharsets.UTF_8), "v4.2.0");
        } else {
            VelocityUpdateBefehl.richteStartHookEin(ordner);
        }

        Files.writeString(ordner.resolve("starte-testproxy.sh"),
                "test \"$PUNISH_UPDATE_START_HOOK\" = 1 && test \"$(cat PunishSystemVelocity.jar)\" = "
                        + (mitUpdate ? "neu" : "original")
                        + " && test \"$1\" = 'Argument mit Leerzeichen' && touch gestartet\n");
        assertEquals(0, startHook(ordner.resolve("update-pending"), "sh", "starte-testproxy.sh",
                "Argument mit Leerzeichen"));
        assertTrue(Files.isRegularFile(ordner.resolve("gestartet")));
    }

    private static int startHook(Path updates, String... befehl) throws Exception {
        Path shell = System.getProperty("os.name").startsWith("Windows")
                ? Path.of(System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files"), "Git", "bin", "bash.exe")
                : Path.of("/bin/sh");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(shell), "POSIX-Shell benötigt");
        var argumente = new java.util.ArrayList<>(java.util.List.of(shell.toString(),
                updates.getFileName() + "/apply-update.sh"));
        argumente.addAll(java.util.List.of(befehl));
        var prozess = new ProcessBuilder(argumente)
                .directory(updates.getParent().toFile()).redirectErrorStream(true).start();
        try {
            assertTrue(prozess.waitFor(30, java.util.concurrent.TimeUnit.SECONDS), "Start-Hook hängt.");
            String ausgabe = new String(prozess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(prozess.exitValue() == 0 || prozess.exitValue() == 1,
                    "Shell konnte den Start-Hook nicht ausführen: " + ausgabe);
            return prozess.exitValue();
        } finally {
            prozess.destroyForcibly();
        }
    }
}
