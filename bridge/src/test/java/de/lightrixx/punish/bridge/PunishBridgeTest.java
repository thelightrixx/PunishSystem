package de.lightrixx.punish.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PunishBridgeTest {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"true,false,true", "false,true,true", "false,false,false"})
    void diagnoseErlaubtKonsoleUndPrueftSpielerrechte(boolean konsole, boolean recht, boolean erlaubt) {
        org.bukkit.command.CommandSender sender = konsole
                ? mock(org.bukkit.command.ConsoleCommandSender.class) : mock(org.bukkit.entity.Player.class);
        when(sender.hasPermission("punish.diagnostics")).thenReturn(recht);
        var config = mock(BridgeKonfiguration.class);
        when(config.praefix()).thenReturn("<gray>[Punish]");
        var diagnose = new BridgeDiagnosticsBefehl(config, null, null, () -> "4.0.0", () -> "4.0.0",
                () -> new BridgeDiagnosticsBefehl.ChatfilterInfo(false, "deaktiviert"));

        assertTrue(diagnose.onCommand(sender, mock(org.bukkit.command.Command.class),
                "punish-diagnostics", new String[0]));

        var nachrichten = org.mockito.ArgumentCaptor.forClass(net.kyori.adventure.text.Component.class);
        verify(sender, atLeastOnce()).sendMessage(nachrichten.capture());
        String text = nachrichten.getAllValues().stream()
                .map(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()::serialize)
                .collect(java.util.stream.Collectors.joining("\n"));
        assertEquals(erlaubt, text.contains("Diagnose (Bridge 4.0.0)"));
        assertEquals(!erlaubt, text.contains("Dazu hast du keine Berechtigung."));
    }

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
