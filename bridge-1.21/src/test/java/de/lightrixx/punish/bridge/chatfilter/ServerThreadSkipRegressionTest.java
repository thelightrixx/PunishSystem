package de.lightrixx.punish.bridge.chatfilter;

import de.lightrixx.punish.chatfilter.ChatfilterAusfall;
import de.lightrixx.punish.chatfilter.FilterKanal;
import de.lightrixx.punish.chatfilter.ToxizitaetsModell;
import de.lightrixx.punish.chatfilter.WortlistenFilter;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class ServerThreadSkipRegressionTest {

    @AfterEach
    void zuruecksetzen() {
        ChatfilterService.serverThreadPruefer = ChatfilterService::istServerThread;
    }

    private ChatfilterService serviceMitKiTreffer() {
        ChatfilterKonfiguration config = Mockito.mock(ChatfilterKonfiguration.class);
        when(config.aktiv()).thenReturn(true);
        when(config.modusFuer(any())).thenReturn(ChatfilterKonfiguration.Modus.KI);
        when(config.schwelle()).thenReturn(0.7d);
        when(config.kiTimeoutMillis()).thenReturn(150L);
        when(config.maxZeichen()).thenReturn(500);
        when(config.bypassPermission()).thenReturn("punish.chatfilter.bypass");
        when(config.befehlAusnahmen()).thenReturn(Set.of());
        when(config.klartextUbertragen()).thenReturn(true);
        ToxizitaetsModell modell = text -> 0.95d;
        return new ChatfilterService(config, new WortlistenFilter(List.of("hurensohn")), modell,
                (a, b, c, d) -> {
                });
    }

    @Test
    void serverThreadOhneCacheBrichtOhneBlockierenAb() {
        ChatfilterService dienst = serviceMitKiTreffer();
        try {
            ChatfilterService.serverThreadPruefer = () -> true;
            Player spieler = Mockito.mock(Player.class);
            when(spieler.hasPermission(anyString())).thenReturn(false);
            assertThrows(ChatfilterAusfall.class, () -> dienst.pruefeBlockierend(
                    spieler, FilterKanal.BUCH, "voellig einzigartiger Text ohne Treffer"));
        } finally {
            dienst.close();
        }
    }

    @Test
    void serverThreadMitCacheLiefertTrefferSofort() {
        ChatfilterService dienst = serviceMitKiTreffer();
        try {
            Player spieler = Mockito.mock(Player.class);
            when(spieler.hasPermission(anyString())).thenReturn(false);
            String text = "gecachter toxischer Text";
            assertTrue(dienst.pruefeBlockierend(spieler, FilterKanal.BUCH, text).isPresent());
            ChatfilterService.serverThreadPruefer = () -> true;
            assertTrue(dienst.pruefeBlockierend(spieler, FilterKanal.BUCH, text).isPresent());
        } finally {
            dienst.close();
        }
    }

    @Test
    void standardErkennungOhneBukkitServerBleibtKonservativ() {
        assertFalse(ChatfilterService.istServerThread());
    }
}
