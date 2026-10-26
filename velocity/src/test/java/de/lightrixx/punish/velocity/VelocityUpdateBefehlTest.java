package de.lightrixx.punish.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.storage.PlayerCache;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VelocityUpdateBefehlTest {

    private static VelocityUpdateBefehl baueBefehl() {
        return new VelocityUpdateBefehl(Mockito.mock(MessageService.class),
                Mockito.mock(VelocityKonfiguration.class),
                Mockito.mock(ProtectionService.class),
                Mockito.mock(PlayerCache.class),
                Mockito.mock(AuftragsPlaner.class),
                Mockito.mock(SpielerVerzeichnis.class),
                () -> "4.0-RC+Test");
    }

    private static UpdateVerteilung baueVerteilung() {
        return new UpdateVerteilung(Mockito.mock(ProxyServer.class),
                LoggerFactory.getLogger("VelocityUpdateBefehlTest"),
                (server, paket) -> { });
    }

    @Test
    void setterLehntNullAb() {
        VelocityUpdateBefehl befehl = baueBefehl();
        NullPointerException fehler = assertThrows(NullPointerException.class,
                () -> befehl.setUpdateVerteilung(null));
        assertTrue(String.valueOf(fehler.getMessage()).contains("Update-Verteilung"),
                "Meldung muss die Update-Verteilung benennen: " + fehler.getMessage());
    }

    @Test
    void setterAkzeptiertVerteilung() {
        VelocityUpdateBefehl befehl = baueBefehl();
        assertDoesNotThrow(() -> befehl.setUpdateVerteilung(baueVerteilung()));
    }

    private static Path projektWurzel() {
        Path start = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath();
        for (Path kandidat = start; kandidat != null; kandidat = kandidat.getParent()) {
            Path pom = kandidat.resolve("pom.xml");
            if (!Files.isRegularFile(pom)) {
                continue;
            }
            String inhalt;
            try {
                inhalt = Files.readString(pom);
            } catch (Exception e) {
                continue;
            }
            if (inhalt.contains("<artifactId>punish-parent</artifactId>")
                    && inhalt.contains("<packaging>pom</packaging>")) {
                return kandidat;
            }
        }
        throw new IllegalStateException("Projektwurzel nicht gefunden, Start: " + start);
    }

    @Test
    void verteilungWirdVorRegisterErzeugt() throws Exception {
        String quelle = Files.readString(projektWurzel().resolve(
                Path.of("velocity", "src", "main", "java", "de", "lightrixx", "punish",
                        "velocity", "PunishVelocity.java")));
        int zuweisung = quelle.indexOf("this.updateVerteilung = updateVerteilung;");
        int register = quelle.indexOf("VelocityBefehle.register(");
        assertTrue(zuweisung > 0, "Zuweisung an das Update-Verteilungs-Feld fehlt.");
        assertTrue(register > 0, "Aufruf VelocityBefehle.register(...) fehlt.");
        assertTrue(zuweisung < register, "UpdateVerteilung muss vor dem Befehls-Register "
                + "erzeugt werden, sonst erhält der Update-Befehl null (toter Update-Push).");
    }
}
