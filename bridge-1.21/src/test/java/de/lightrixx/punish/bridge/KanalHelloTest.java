package de.lightrixx.punish.bridge;

import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KanalHelloTest {

    @Test
    @DisplayName("A-017: HELLO ohne Online-Spieler wirft nicht")
    void helloOhneTraegerWirftNicht() {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.isPrimaryThread()).thenReturn(true);
        when(server.getOnlinePlayers()).thenReturn(List.of());
        KanalClient client = new KanalClient(plugin, new MuteCache());
        assertDoesNotThrow(() -> client.sendeHello("lobby"));
    }
}
