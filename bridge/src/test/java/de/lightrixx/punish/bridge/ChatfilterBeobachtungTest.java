package de.lightrixx.punish.bridge;

import de.lightrixx.punish.bridge.chatfilter.ChatfilterService;
import de.lightrixx.punish.chatfilter.FilterKanal;
import de.lightrixx.punish.chatfilter.FilterTreffer;
import de.lightrixx.punish.chatfilter.TrefferQuelle;
import de.lightrixx.punish.storage.PlayerCache;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatfilterBeobachtungTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void beobachtungMeldetChatTrefferOhneBlockade(boolean beobachten) throws Exception {
        var config = mock(BridgeKonfiguration.class);
        when(config.serverName()).thenReturn("lobby");
        when(config.praefix()).thenReturn("<gray>[Punish]");
        when(config.nachricht(anyString())).thenReturn("<red>Blockiert");
        when(config.muteBlockierteBefehle()).thenReturn(List.of());
        var filter = mock(ChatfilterService.class);
        when(filter.beobachten()).thenReturn(beobachten);
        var treffer = new FilterTreffer(FilterKanal.CHAT, TrefferQuelle.KI, .9, "b".repeat(64));
        when(filter.pruefeChat(any(), anyString())).thenReturn(Optional.of(treffer));
        var spieler = mock(Player.class);
        when(spieler.getUniqueId()).thenReturn(UUID.randomUUID());
        when(spieler.getName()).thenReturn("Spieler");
        var mutes = new MuteCache();
        mutes.ersetzeAlle(List.of());
        var ipMutes = new IpMuteCache();
        ipMutes.ersetzeAlle(List.of());
        var listener = new BridgeEnforcementListener(mutes, ipMutes, new BanCache(),
                new TimeoutCache(), new FreezeCache(), config, mock(KanalClient.class),
                MiniMessage.miniMessage(), mock(PlayerCache.class));
        listener.setChatfilter(filter);
        var methode = BridgeEnforcementListener.class.getDeclaredMethod("pruefeChat", Player.class,
                Supplier.class, boolean.class, Runnable.class);
        methode.setAccessible(true);
        AtomicBoolean abgebrochen = new AtomicBoolean();
        methode.invoke(listener, spieler, (Supplier<String>) () -> "Testnachricht", true,
                (Runnable) () -> abgebrochen.set(true));
        assertEquals(!beobachten, abgebrochen.get());
        verify(filter).melden(eq(spieler), eq("Testnachricht"), eq(treffer));
    }
}
