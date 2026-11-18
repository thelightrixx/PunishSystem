package de.lightrixx.punish.velocity;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AT05-1: belegt die Verdrahtung der IP-Aufbewahrungsfrist an Start und Reload.
 * Die Prüfung liest den Quelltext, weil die Verdrahtung in den großen
 * Start-/Reload-Klassen liegt und ein Verhaltenstest den kompletten Proxy
 * aufbauen müsste.
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

    @Test
    void persistenzWaechterLaeuftImFuenfMinutenTakt() throws Exception {
        var proxy = org.mockito.Mockito.mock(com.velocitypowered.api.proxy.ProxyServer.class);
        var scheduler = org.mockito.Mockito.mock(com.velocitypowered.api.scheduler.Scheduler.class);
        var builder = org.mockito.Mockito.mock(com.velocitypowered.api.scheduler.Scheduler.TaskBuilder.class,
                org.mockito.Mockito.RETURNS_SELF);
        org.mockito.Mockito.when(proxy.getScheduler()).thenReturn(scheduler);
        org.mockito.Mockito.when(scheduler.buildTask(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(Runnable.class))).thenReturn(builder);
        org.mockito.Mockito.when(builder.schedule()).thenReturn(
                org.mockito.Mockito.mock(com.velocitypowered.api.scheduler.ScheduledTask.class));
        var plugin = new PunishVelocity(proxy, org.mockito.Mockito.mock(org.slf4j.Logger.class), Path.of("."));
        var feld = PunishVelocity.class.getDeclaredField("planer");
        feld.setAccessible(true);
        feld.set(plugin, new VelocityAuftragsPlaner(proxy, plugin));
        var starten = PunishVelocity.class.getDeclaredMethod("startePersistenzWaechter", VelocityVerzeichnis.class);
        starten.setAccessible(true);
        starten.invoke(plugin, org.mockito.Mockito.mock(VelocityVerzeichnis.class));
        org.mockito.Mockito.verify(builder).delay(300_000L, java.util.concurrent.TimeUnit.MILLISECONDS);
        org.mockito.Mockito.verify(builder).repeat(300_000L, java.util.concurrent.TimeUnit.MILLISECONDS);
    }
}
