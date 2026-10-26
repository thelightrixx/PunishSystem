package de.lightrixx.punish.velocity;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AT05-1: belegt die Verdrahtung der IP-Aufbewahrungsfrist an Start und Reload.
 * Die Prüfung liest den Quelltext, weil die Verdrahtung in den großen
 * Start-/Reload-Klassen liegt und ein Verhaltenstest den kompletten Proxy
 * aufbauen müsste. Der Sweep selbst ist in
 * {@code common/.../audit/...FixPaketRegressionTest} verhaltensbasiert abgedeckt.
 */
class IpRetentionVerdrahtungTest {

    private static String quelle(String pfad) throws Exception {
        return Files.readString(Path.of("src/main/java/de/lightrixx/punish/velocity/" + pfad));
    }

    @Test
    void startSetztFristVorDemLadenDesCaches() throws Exception {
        String text = quelle("PunishVelocity.java");
        int frist = text.indexOf("playerCache.setIpRetentionTage(config.ipAufbewahrungTage());");
        int laden = text.indexOf("playerCache.laden();", Math.max(0, frist - 4000));
        assertTrue(frist > 0, "Startpfad muss die konfigurierte IP-Aufbewahrung setzen.");
        assertTrue(laden > 0, "Startpfad muss den PlayerCache laden.");
        assertTrue(frist < laden, "Die Frist muss vor dem Laden des Caches gesetzt werden, "
                + "sonst greift beim Start die Default-Frist von 90 Tagen.");
    }

    @Test
    void reloadSetztDieFristEbenfalls() throws Exception {
        String text = quelle("VelocityReloadBefehl.java");
        assertTrue(text.contains("playerCache.setIpRetentionTage(konfiguration.ipAufbewahrungTage());"),
                "Reload muss die Frist auf den laufenden PlayerCache anwenden.");
    }
}
