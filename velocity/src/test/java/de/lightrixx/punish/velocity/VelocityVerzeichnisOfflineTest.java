package de.lightrixx.punish.velocity;

import de.lightrixx.punish.core.OfflineVerzeichnis;
import de.lightrixx.punish.model.CachedPlayer;
import de.lightrixx.punish.storage.PlayerCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VelocityVerzeichnisOfflineTest {

    @Test
    @DisplayName("Offline-UUID aus PlayerCache wird als gespielt erkannt")
    void offlineUuidAusCache() {
        var proxy = mock(com.velocitypowered.api.proxy.ProxyServer.class);
        UUID uuid = UUID.randomUUID();
        when(proxy.getPlayer(uuid)).thenReturn(Optional.empty());
        var cache = mock(PlayerCache.class);
        var gecacht = new CachedPlayer(uuid, "TeamMitglied", "unbekannt", System.currentTimeMillis());
        when(cache.finden(uuid)).thenReturn(Optional.of(gecacht));

        var verzeichnis = new VelocityVerzeichnis(proxy);
        assertTrue(verzeichnis.findeNachUuid(uuid).isEmpty(), "ohne Cache muss offline leer sein");

        verzeichnis.mitPlayerCache(cache);
        Optional<OfflineVerzeichnis.ExternSpieler> gefunden = verzeichnis.findeNachUuid(uuid);
        assertTrue(gefunden.isPresent(), "gecachter Spieler muss gefunden werden");
        assertTrue(gefunden.get().hatGespielt(), "gecachter Spieler gilt als gespielt");
        assertFalse(gefunden.get().online(), "Cache-Treffer ist nicht online");
    }

    @Test
    @DisplayName("Offline-Name aus PlayerCache wird als gespielt erkannt")
    void offlineNameAusCache() {
        var proxy = mock(com.velocitypowered.api.proxy.ProxyServer.class);
        when(proxy.getPlayer("TeamMitglied")).thenReturn(Optional.empty());
        var cache = mock(PlayerCache.class);
        UUID uuid = UUID.randomUUID();
        var gecacht = new CachedPlayer(uuid, "TeamMitglied", "unbekannt", System.currentTimeMillis());
        when(cache.findenNachName("TeamMitglied")).thenReturn(Optional.of(gecacht));

        var verzeichnis = new VelocityVerzeichnis(proxy);
        verzeichnis.mitPlayerCache(cache);
        Optional<OfflineVerzeichnis.ExternSpieler> gefunden = verzeichnis.findeNachName("TeamMitglied");
        assertTrue(gefunden.isPresent(), "gecachter Name muss gefunden werden");
        assertEquals(uuid, gefunden.get().uuid());
        assertTrue(gefunden.get().hatGespielt());
    }

    @Test
    @DisplayName("Unbekannter Spieler bleibt leer")
    void unbekanntBleibtLeer() {
        var proxy = mock(com.velocitypowered.api.proxy.ProxyServer.class);
        UUID uuid = UUID.randomUUID();
        when(proxy.getPlayer(uuid)).thenReturn(Optional.empty());
        var cache = mock(PlayerCache.class);
        when(cache.finden(uuid)).thenReturn(Optional.empty());

        var verzeichnis = new VelocityVerzeichnis(proxy);
        verzeichnis.mitPlayerCache(cache);
        assertTrue(verzeichnis.findeNachUuid(uuid).isEmpty(), "nie gesehen bleibt leer");
    }
    @Test
    void aktuellerServerWirdFuerScopeWirkungAufgeloest() {
        var proxy = mock(com.velocitypowered.api.proxy.ProxyServer.class);
        var player = mock(com.velocitypowered.api.proxy.Player.class);
        var verbindung = mock(com.velocitypowered.api.proxy.ServerConnection.class);
        var info = mock(com.velocitypowered.api.proxy.server.ServerInfo.class);
        UUID uuid = UUID.randomUUID();
        when(proxy.getPlayer(uuid)).thenReturn(Optional.of(player));
        when(player.getCurrentServer()).thenReturn(Optional.of(verbindung));
        when(verbindung.getServerInfo()).thenReturn(info);
        when(info.getName()).thenReturn("survival");
        var verzeichnis = new VelocityVerzeichnis(proxy);
        assertEquals("survival", verzeichnis.serverName(uuid));
        when(player.getCurrentServer()).thenReturn(Optional.empty());
        assertEquals("unbekannt", verzeichnis.serverName(uuid));
    }
}
