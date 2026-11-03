package de.lightrixx.punish.bridge;

import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KanalSequenzResetTest {

    @Test
    @DisplayName("HELLO setzt die Sendesequenz nicht zurück (keine Replay-Kollision mit parallelen Sendern)")
    void helloSetztSendeSequenzNichtZurueck() throws Exception {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.isPrimaryThread()).thenReturn(true);
        var spieler = mock(org.bukkit.entity.Player.class);
        org.mockito.Mockito.doReturn(List.of(spieler)).when(server).getOnlinePlayers();

        KanalClient client = new KanalClient(plugin, new MuteCache());
        client.setTunnelSecret("0123456789abcdef0123456789abcdef");

        client.sendeHello("lobby");
        long erste = sendeSequenz(client);
        client.sendeHello("lobby");
        long zweite = sendeSequenz(client);

        assertEquals(erste + 4, zweite,
                "HELLO darf den Zähler nicht auf 0 zurücksetzen, sonst vergeben HELLO und "
                        + "ein paralleler Sender dieselbe Sequenz und der Proxy verwirft ein Paket.");
    }

    private static long sendeSequenz(KanalClient client) throws Exception {
        var feld = KanalClient.class.getDeclaredField("sendeSeq");
        feld.setAccessible(true);
        return ((AtomicLong) feld.get(client)).get();
    }
}
