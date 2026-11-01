package de.lightrixx.punish.velocity;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VelocitySetupBefehlTest {

    @Test
    void secretIstLangUndEindeutig() {
        String erstes = VelocitySetupBefehl.generiereSecret();
        String zweites = VelocitySetupBefehl.generiereSecret();

        assertTrue(erstes.length() >= 40, "Secret zu kurz: " + erstes);
        assertNotEquals(erstes, zweites, "Zwei Secrets dürfen nicht identisch sein.");
    }

    @Test
    void bridgeDateiEnthaeltFertigenBlock() throws Exception {
        Path ordner = Files.createTempDirectory("punish-setup");
        Path datei = VelocitySetupBefehl.schreibeBridgeDatei(ordner, "survival", "geheim");

        String inhalt = Files.readString(datei);
        assertTrue(datei.getFileName().toString().equals("bridge-survival.yml"), inhalt);
        assertTrue(inhalt.contains("server-name: \"survival\""), inhalt);
        assertTrue(inhalt.contains("tunnel-secret: \"geheim\""), inhalt);
        assertTrue(inhalt.contains("plugins/PunishSystemBridge/config/general.yml"), inhalt);
    }
}
