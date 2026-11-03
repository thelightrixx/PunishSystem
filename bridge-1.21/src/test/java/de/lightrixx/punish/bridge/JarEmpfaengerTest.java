package de.lightrixx.punish.bridge;

import de.lightrixx.punish.net.Protokoll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JarEmpfaengerTest {

    @TempDir
    File ordner;

    private JarEmpfaenger empfaenger;
    private final List<Protokoll.UpdateQuittung> quittungen = new ArrayList<>();

    @BeforeEach
    void aufsetzen() {
        empfaenger = new JarEmpfaenger(ordner, new JarEmpfaenger.Rueckmeldung() {
            @Override
            public void quittung(String variante, String version, boolean erfolg) {
                quittungen.add(new Protokoll.UpdateQuittung(variante, version, erfolg));
            }

            @Override
            public void standMelden() {
            }
        });
    }

    @Test
    void empfangsWhitelistVerwirftPfadFremdeNamen() {
        assertTrue(JarEmpfaenger.istEmpfangsZulaessig("bridge", "4.0-RC+011126-1853"));
        assertTrue(JarEmpfaenger.istEmpfangsZulaessig("Bridge-1.21", "1.0.0_beides"));
        assertTrue(JarEmpfaenger.istEmpfangsZulaessig(null, null));
        assertFalse(JarEmpfaenger.istEmpfangsZulaessig("bridge", "../../boese"));
        assertFalse(JarEmpfaenger.istEmpfangsZulaessig("boese!", "1.0"));
        assertFalse(JarEmpfaenger.istEmpfangsZulaessig("bridge", "a".repeat(65)));
    }

    @Test
    void dateinameFuerErzeugtNurWhitelistZeichen() {
        String name = JarEmpfaenger.dateinameFuer("bridge", "4.0-RC+011126-1853");
        assertEquals("PunishSystem-4.0-RC+011126-1853-bridge.jar", name);
        assertTrue(name.matches("[A-Za-z0-9._+-]+\\.jar"));
    }

    @Test
    void kompletterUpdateTransferLegtDateiImOrdnerAb() throws Exception {
        byte[] inhalt = "update-bytes-0123456789".getBytes(StandardCharsets.UTF_8);
        String sha = sha256(inhalt);
        int chunk = 4096;
        int anzahl = 1;
        empfaenger.empfangeMeta(new Protokoll.UpdateMeta("bridge", "4.0-RC+011126-1853",
                inhalt.length, sha, chunk, anzahl));
        empfaenger.empfangeBlock(new Protokoll.UpdateBlock("bridge", 0, inhalt));
        empfaenger.empfangeFertig(new Protokoll.UpdateFertig("bridge", "4.0-RC+011126-1853", sha, inhalt.length));

        assertTrue(new File(ordner, "PunishSystem-4.0-RC+011126-1853-bridge.jar").isFile());
        assertEquals(1, quittungen.size());
        assertTrue(quittungen.get(0).erfolg());
    }

    @Test
    void transferLaesstKeinePartDateiAuseinanderHaengen() throws Exception {
        byte[] inhalt = new byte[]{1, 2, 3, 4};
        String sha = sha256(inhalt);
        Protokoll.UpdateMeta meta = new Protokoll.UpdateMeta("bridge", "4.0-T", inhalt.length,
                sha, 4096, 1);
        empfaenger.empfangeMeta(meta);
        empfaenger.empfangeBlock(new Protokoll.UpdateBlock("bridge", 0, inhalt));

        assertTrue(new File(ordner, "PunishSystem-4.0-T-bridge.jar.part").exists());

        empfaenger.empfangeFertig(new Protokoll.UpdateFertig("bridge", "4.0-T", sha, inhalt.length));
        assertTrue(new File(ordner, "PunishSystem-4.0-T-bridge.jar").isFile());
        assertFalse(new File(ordner, "PunishSystem-4.0-T-bridge.jar.part").exists());
        assertEquals(1, quittungen.size());
        assertTrue(quittungen.get(0).erfolg());
    }

    private static String sha256(byte[] daten) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(daten));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"hash", "luecke", "doppelt", "abbruch", "version", "ziel"})
    void ungueltigerTransferErhaeltBereitsBereitgestellteJar(String fehler) throws Exception {
        byte[] alt = "vorherige-gepruefte-version".getBytes(StandardCharsets.UTF_8);
        byte[] neu = new byte[5000];
        new java.util.Random(731L).nextBytes(neu);
        String version = "4.1.0";
        String hash = sha256(neu);
        var ziel = ordner.toPath().resolve(JarEmpfaenger.dateinameFuer("bridge", version));
        if ("ziel".equals(fehler)) {
            java.nio.file.Files.createDirectories(ziel);
            java.nio.file.Files.write(ziel.resolve("vorher"), alt);
        } else {
            java.nio.file.Files.write(ziel, alt);
        }
        empfaenger.empfangeMeta(new Protokoll.UpdateMeta("bridge", version, neu.length, hash, 4096, 2));
        byte[] erster = java.util.Arrays.copyOfRange(neu, 0, 4096);
        if ("hash".equals(fehler)) {
            erster[0] ^= 1;
        }
        empfaenger.empfangeBlock(new Protokoll.UpdateBlock("bridge", 0, erster));
        if ("abbruch".equals(fehler)) {
            empfaenger.abbrechen();
        } else {
            empfaenger.empfangeBlock(new Protokoll.UpdateBlock("bridge",
                    "luecke".equals(fehler) ? 2 : "doppelt".equals(fehler) ? 0 : 1,
                    java.util.Arrays.copyOfRange(neu, 4096, neu.length)));
            empfaenger.empfangeFertig(new Protokoll.UpdateFertig("bridge",
                    "version".equals(fehler) ? "alte-version" : version, hash, neu.length));
        }
        assertTrue(quittungen.stream().noneMatch(Protokoll.UpdateQuittung::erfolg));
        if (!"abbruch".equals(fehler)) {
            assertTrue(quittungen.stream().anyMatch(q -> !q.erfolg()));
        }
        assertArrayEquals(alt, java.nio.file.Files.readAllBytes("ziel".equals(fehler) ? ziel.resolve("vorher") : ziel));
        assertFalse(java.nio.file.Files.exists(ziel.resolveSibling(ziel.getFileName() + ".part")));
        if ("ziel".equals(fehler)) {
            java.nio.file.Files.delete(ziel.resolve("vorher"));
            java.nio.file.Files.delete(ziel);
        }
        empfaenger.empfangeMeta(new Protokoll.UpdateMeta("bridge", version, neu.length, hash, 4096, 2));
        empfaenger.empfangeBlock(new Protokoll.UpdateBlock("bridge", 0, java.util.Arrays.copyOfRange(neu, 0, 4096)));
        empfaenger.empfangeBlock(new Protokoll.UpdateBlock("bridge", 1, java.util.Arrays.copyOfRange(neu, 4096, neu.length)));
        empfaenger.empfangeFertig(new Protokoll.UpdateFertig("bridge", version, hash, neu.length));
        assertTrue(quittungen.getLast().erfolg());
        assertArrayEquals(neu, java.nio.file.Files.readAllBytes(ziel));
    }
}
