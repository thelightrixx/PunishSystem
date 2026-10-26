package de.lightrixx.punish.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.storage.PlayerCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatfilterSyncRechtTest {

    private ChatfilterBefehl befehl;
    private BefehlsSender sender;

    @BeforeEach
    void aufsetzen() {
        befehl = new ChatfilterBefehl(mock(MessageService.class),
                mock(VelocityKonfiguration.class), mock(ProtectionService.class),
                mock(PlayerCache.class), mock(AuftragsPlaner.class),
                mock(SpielerVerzeichnis.class), mock(ProxyServer.class));
        sender = mock(BefehlsSender.class);
    }

    @Test
    void syncBrauchtNeuesRecht() {
        when(sender.hatRecht("punish.chatfilter")).thenReturn(true);
        when(sender.hatRecht("punish.chatfilter.sync")).thenReturn(false);
        assertFalse(befehl.hatRecht(sender, new String[]{"sync"}),
                "/chatfilter sync braucht punish.chatfilter.sync (B-009)");
    }

    @Test
    void syncMitNeuemRechtErlaubt() {
        when(sender.hatRecht("punish.chatfilter.sync")).thenReturn(true);
        assertTrue(befehl.hatRecht(sender, new String[]{"sync"}));
    }

    @Test
    void andereUnterbefehleBleibenBeimAltenRecht() {
        when(sender.hatRecht("punish.chatfilter")).thenReturn(true);
        when(sender.hatRecht("punish.chatfilter.sync")).thenReturn(false);
        assertTrue(befehl.hatRecht(sender, new String[]{"on"}));
        assertTrue(befehl.hatRecht(sender, new String[]{"add"}));
        assertTrue(befehl.hatRecht(sender, new String[0]));
    }

    @Test
    void sichtbarMitEntwederRecht() {
        when(sender.hatRecht("punish.chatfilter")).thenReturn(false);
        when(sender.hatRecht("punish.chatfilter.sync")).thenReturn(true);
        assertTrue(befehl.sichtbarFuer(sender));
    }
}
