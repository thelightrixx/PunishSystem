package de.lightrixx.punish.velocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrefferBindungTest {

    private static RegisteredServer server(String name) {
        RegisteredServer server = mock(RegisteredServer.class);
        ServerInfo info = mock(ServerInfo.class);
        when(server.getServerInfo()).thenReturn(info);
        when(info.getName()).thenReturn(name);
        return server;
    }

    private static Player spielerAuf(UUID uuid, RegisteredServer aktuell, ProxyServer proxy) {
        Player spieler = mock(Player.class);
        when(proxy.getPlayer(uuid)).thenReturn(Optional.of(spieler));
        if (aktuell == null) {
            when(spieler.getCurrentServer()).thenReturn(Optional.empty());
        } else {
            ServerConnection verbindung = mock(ServerConnection.class);
            when(verbindung.getServer()).thenReturn(aktuell);
            when(spieler.getCurrentServer()).thenReturn(Optional.of(verbindung));
        }
        return spieler;
    }

    @Test
    void zielAufMeldendemServerAkzeptiert() {
        ProxyServer proxy = mock(ProxyServer.class);
        RegisteredServer lobby = server("lobby");
        UUID uuid = UUID.randomUUID();
        spielerAuf(uuid, lobby, proxy);
        assertTrue(StrafListener.trefferGebunden(proxy, lobby, uuid));
    }

    @Test
    void zielAufAnderemServerVerworfen() {
        ProxyServer proxy = mock(ProxyServer.class);
        RegisteredServer lobby = server("lobby");
        RegisteredServer survival = server("survival");
        UUID uuid = UUID.randomUUID();
        spielerAuf(uuid, survival, proxy);
        assertFalse(StrafListener.trefferGebunden(proxy, lobby, uuid),
                "Treffer für Ziel auf fremdem Server wird verworfen (B-005)");
    }

    @Test
    void offlineZielVerworfen() {
        ProxyServer proxy = mock(ProxyServer.class);
        RegisteredServer lobby = server("lobby");
        UUID uuid = UUID.randomUUID();
        when(proxy.getPlayer(uuid)).thenReturn(Optional.empty());
        assertFalse(StrafListener.trefferGebunden(proxy, lobby, uuid));
    }

    @Test
    void nullEingabenVerworfen() {
        ProxyServer proxy = mock(ProxyServer.class);
        assertFalse(StrafListener.trefferGebunden(null, server("lobby"), UUID.randomUUID()));
        assertFalse(StrafListener.trefferGebunden(proxy, null, UUID.randomUUID()));
        assertFalse(StrafListener.trefferGebunden(proxy, server("lobby"), null));
    }
}
