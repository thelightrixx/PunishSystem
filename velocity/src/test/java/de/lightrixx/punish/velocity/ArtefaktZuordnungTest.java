package de.lightrixx.punish.velocity;

import de.lightrixx.punish.update.HangarUpdateService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArtefaktZuordnungTest {

    private static HangarUpdateService.Artefakt artefakt(String plattform, String dateiname) {
        return new HangarUpdateService.Artefakt(plattform, dateiname,
                "https://hangar.papermc.io/x/" + dateiname, "a".repeat(64));
    }

    @Test
    void velocityNachPlattform() {
        var zuordnung = new VelocityUpdateBefehl.ArtefaktZuordnung();
        assertEquals("velocity", zuordnung.nimm(artefakt("VELOCITY", "irgendein-name.jar")));
    }

    @Test
    void bridgeVarianteWirdExplizitUndUnabhaengigVonDerReihenfolgeZugeordnet() {
        var zuordnung = new VelocityUpdateBefehl.ArtefaktZuordnung();
        assertEquals("bridge-1.21", zuordnung.nimm(artefakt("BRIDGE-1.21", "bridge121.jar")));
        assertEquals("bridge", zuordnung.nimm(artefakt("BRIDGE", "bridge.jar")));
        assertThrows(IllegalStateException.class, () -> zuordnung.nimm(artefakt("PAPER", "unbekannt.jar")));
    }

    @Test
    void unbekanntBrichtAb() {
        var zuordnung = new VelocityUpdateBefehl.ArtefaktZuordnung();
        assertThrows(IllegalStateException.class,
                () -> zuordnung.nimm(artefakt("WATERFALL", "x.jar")));
        assertThrows(IllegalStateException.class,
                () -> zuordnung.nimm(artefakt("", "x.jar")));
        assertThrows(IllegalStateException.class, () -> zuordnung.nimm(null));
    }
}
