package de.lightrixx.punish.velocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.storage.MuteRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BrueckenKanalAbweisungTest {

    private static BrueckenKanal kanal(ProxyServer proxy, MessageService nachrichten) {
        BrueckenKanal kanal = new BrueckenKanal(mock(PunishVelocity.class), proxy,
                mock(org.slf4j.Logger.class), mock(MuteRepository.class), null);
        kanal.setMessageService(nachrichten);
        return kanal;
    }

    @Test
    void allowlistAbweisungMeldetSpieler() {
        ProxyServer proxy = mock(ProxyServer.class);
        MessageService nachrichten = mock(MessageService.class);
        Player spieler = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        when(proxy.getPlayer(uuid)).thenReturn(Optional.of(spieler));
        when(spieler.hasPermission(any())).thenReturn(false);

        kanal(proxy, nachrichten).fuehreAus(uuid, "luckperms user x permission set a true");

        verify(nachrichten).senden(any(BefehlsSender.class),
                eq(MessageKeys.KEIN_RECHT));
    }

    @Test
    void konsoleOhneSpielerBleibtStill() {
        ProxyServer proxy = mock(ProxyServer.class);
        MessageService nachrichten = mock(MessageService.class);
        UUID uuid = UUID.randomUUID();
        when(proxy.getPlayer(uuid)).thenReturn(Optional.empty());

        kanal(proxy, nachrichten).fuehreAus(uuid, "stop");

        verify(nachrichten, never()).senden(any(BefehlsSender.class), anyString());
    }
}
