package de.lightrixx.punish.bridge;

import de.lightrixx.punish.bridge.chatfilter.ChatfilterKonfiguration;
import de.lightrixx.punish.bridge.chatfilter.ChatfilterService;
import de.lightrixx.punish.chatfilter.FilterKanal;
import de.lightrixx.punish.chatfilter.WortlistenFilter;
import de.lightrixx.punish.net.Protokoll;
import de.lightrixx.punish.storage.PlayerCache;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("deprecation")
class VentureChatRegressionTest {

    private Player spieler;
    private MuteCache mute;
    private BridgeKonfiguration config;
    private BridgeEnforcementListener listener;

    @BeforeEach
    void vorbereiten() {
        spieler = mock(Player.class);
        when(spieler.getUniqueId()).thenReturn(UUID.randomUUID());
        when(spieler.getName()).thenReturn("Spieler");
        mute = new MuteCache();
        mute.ersetzeAlle(List.of());
        config = mock(BridgeKonfiguration.class);
        when(config.serverName()).thenReturn("lobby");
        when(config.nachricht(anyString())).thenReturn("<red>Blockiert");
        when(config.praefix()).thenReturn("<gray>[Punish]");
        listener = new BridgeEnforcementListener(mute, config, mock(KanalClient.class),
                MiniMessage.miniMessage(), mock(PlayerCache.class));
        listener.setVentureChatAktiv(true);
    }

    private ChatfilterService wortfilter(AtomicInteger meldungen) {
        var config = mock(ChatfilterKonfiguration.class);
        when(config.aktiv()).thenReturn(true);
        when(config.modusFuer(any())).thenReturn(ChatfilterKonfiguration.Modus.STUFE1);
        when(config.bypassPermission()).thenReturn("punish.chatfilter.bypass");
        return new ChatfilterService(config, new WortlistenFilter(List.of("hurensohn")), null,
                (uuid, name, text, treffer) -> meldungen.incrementAndGet());
    }

    @Test
    void sperrtVorVentureChatsHighestHandlerUndMeldetGenauEinmal() throws Exception {
        var annotation = BridgeEnforcementListener.class.getMethod("beiVentureChat", AsyncPlayerChatEvent.class)
                .getAnnotation(EventHandler.class);
        assertTrue(annotation.priority().ordinal() < EventPriority.HIGHEST.ordinal());
        assertTrue(annotation.ignoreCancelled());
        var meldungen = new AtomicInteger();
        try (var filter = wortfilter(meldungen)) {
            listener.setChatfilter(filter);
            var event = new AsyncPlayerChatEvent(true, spieler, "Du Hurensohn", new HashSet<>());
            listener.beiVentureChat(event);
            boolean vonVentureChatVersendet = !event.isCancelled();
            assertFalse(vonVentureChatVersendet);
            assertEquals(1, meldungen.get());
            var paper = mock(AsyncChatEvent.class);
            listener.beiChat(paper);
            verifyNoInteractions(paper);
            assertEquals(1, meldungen.get());
        }
    }

    @Test
    void fehlerhafteHinweismeldungHebtDieSperreNichtAuf() {
        try (var filter = wortfilter(new AtomicInteger())) {
            listener.setChatfilter(filter);
            when(config.nachricht(anyString())).thenThrow(new IllegalStateException("Nachricht fehlt"));
            var event = new AsyncPlayerChatEvent(true, spieler, "Hurensohn", new HashSet<>());
            assertThrows(IllegalStateException.class, () -> listener.beiVentureChat(event));
            assertTrue(event.isCancelled());
        }
    }

    @Test
    void saubereNachrichtBleibtFuerVentureChatZustellbar() {
        var meldungen = new AtomicInteger();
        try (var filter = wortfilter(meldungen)) {
            listener.setChatfilter(filter);
            var event = new AsyncPlayerChatEvent(true, spieler, "Hallo zusammen", new HashSet<>());
            listener.beiVentureChat(event);
            assertFalse(event.isCancelled());
            assertEquals(0, meldungen.get());
        }
    }

    @Test
    void bypassBleibtAuchBeiVentureChatWirksam() {
        when(spieler.hasPermission("punish.chatfilter.bypass")).thenReturn(true);
        try (var filter = wortfilter(new AtomicInteger())) {
            listener.setChatfilter(filter);
            var event = new AsyncPlayerChatEvent(true, spieler, "Hurensohn", new HashSet<>());
            listener.beiVentureChat(event);
            assertFalse(event.isCancelled());
        }
    }

    @Test
    void muteWirdVorVentureChatErzwungen() {
        mute.ersetzeAlle(List.of(new Protokoll.MuteEintrag(spieler.getUniqueId(), "Spieler", "Spam", "Mod",
                1L, Long.MAX_VALUE, "global")));
        var event = new AsyncPlayerChatEvent(true, spieler, "Hallo", new HashSet<>());
        listener.beiVentureChat(event);
        assertTrue(event.isCancelled());
    }

    @Test
    void synchronerLegacyChatWartetNichtAufDieKi() {
        var filter = mock(ChatfilterService.class);
        when(filter.pruefeStufe1(spieler, FilterKanal.CHAT, "Hallo")).thenReturn(Optional.empty());
        listener.setChatfilter(filter);
        var event = new AsyncPlayerChatEvent(false, spieler, "Hallo", new HashSet<>());
        listener.beiVentureChat(event);
        assertFalse(event.isCancelled());
        verify(filter).pruefeStufe1(spieler, FilterKanal.CHAT, "Hallo");
        verify(filter).pruefeKiNachgelagert(spieler.getUniqueId(), "Spieler", FilterKanal.CHAT, "Hallo");
        verify(filter, never()).pruefeChat(any(), anyString());
    }

    @Test
    void ohneVentureChatBleibtDerPaperPfadAktiv() {
        var plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("VentureChat");
        var deaktiviert = mock(PluginDisableEvent.class);
        when(deaktiviert.getPlugin()).thenReturn(plugin);
        listener.beiChatPluginDeaktiviert(deaktiviert);
        try (var filter = wortfilter(new AtomicInteger())) {
            listener.setChatfilter(filter);
            var legacy = new AsyncPlayerChatEvent(true, spieler, "Hurensohn", new HashSet<>());
            listener.beiVentureChat(legacy);
            assertFalse(legacy.isCancelled());
            var paper = mock(AsyncChatEvent.class);
            when(paper.getPlayer()).thenReturn(spieler);
            when(paper.message()).thenReturn(Component.text("Hurensohn"));
            when(paper.isAsynchronous()).thenReturn(true);
            listener.beiChat(paper);
            verify(paper).setCancelled(true);
        }
    }
}
