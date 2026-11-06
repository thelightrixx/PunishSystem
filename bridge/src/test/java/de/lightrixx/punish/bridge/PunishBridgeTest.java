package de.lightrixx.punish.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PunishBridgeTest {

    @Test
    void befehlsUeberschreibungLiestSchalterUndProxySync(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path ordner) throws Exception {
        var plugin = mock(org.bukkit.plugin.java.JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(ordner.toFile());
        when(plugin.getResource(anyString())).thenAnswer(aufruf ->
                getClass().getResourceAsStream("/" + aufruf.getArgument(0, String.class)));
        var datei = java.nio.file.Files.createDirectories(ordner.resolve("config")).resolve("general.yml");
        java.nio.file.Files.writeString(datei, "prefix: '<red>Punish'\n");
        var config = new BridgeKonfiguration(plugin);
        config.laden();
        assertTrue(config.befehleUeberschreiben());

        java.nio.file.Files.writeString(datei, "befehle:\n  ueberschreiben: false\n");
        config.laden();
        assertFalse(config.befehleUeberschreiben());

        java.nio.file.Files.writeString(datei, "befehle:\n  ueberschreiben: true\n");
        config.laden();
        assertTrue(config.befehleUeberschreiben());

        var syncDatei = java.nio.file.Files.createDirectories(ordner.resolve(TextEmpfaenger.SYNC_ORDNER))
                .resolve("general.yml");
        java.nio.file.Files.writeString(syncDatei, "befehle:\n  ueberschreiben: false\n");
        config.laden();
        assertFalse(config.befehleUeberschreiben());
    }

    @Test
    void rebuildNoetig_ohneDienstImmer() {
        assertTrue(PunishBridge.rebuildNoetig("", "", false));
        assertTrue(PunishBridge.rebuildNoetig("a", "a", false));
    }

    @Test
    void rebuildNoetig_gleicherStandKeinNeubau() {
        assertFalse(PunishBridge.rebuildNoetig("abc", "abc", true));
    }

    @Test
    void rebuildNoetig_geaenderterStandNeuBau() {
        assertTrue(PunishBridge.rebuildNoetig("abc", "def", true));
        assertTrue(PunishBridge.rebuildNoetig("", "def", true));
        assertTrue(PunishBridge.rebuildNoetig(null, "def", true));
        assertTrue(PunishBridge.rebuildNoetig("abc", null, true));
    }
}
