package de.lightrixx.punish.audit;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import de.lightrixx.punish.storage.MuteRepository;
import de.lightrixx.punish.velocity.BrueckenKanal;
import de.lightrixx.punish.velocity.PunishVelocity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CB4FuehreAusLeerWarnTest {

    @Test
    @DisplayName("B4: leere Bridge-Zeile wird mit Warnung abgewiesen und nicht ausgeführt")
    void leereZeileWarntUndBrichtAb() {
        ProxyServer proxy = mock(ProxyServer.class);
        CommandManager befehle = mock(CommandManager.class);
        when(proxy.getCommandManager()).thenReturn(befehle);
        org.slf4j.Logger logger = mock(org.slf4j.Logger.class);
        BrueckenKanal kanal = new BrueckenKanal(mock(PunishVelocity.class), proxy, logger,
                mock(MuteRepository.class), null);
        RegisteredServer quelle = mock(RegisteredServer.class);
        ServerInfo info = mock(ServerInfo.class);
        when(info.getName()).thenReturn("lobby");
        when(quelle.getServerInfo()).thenReturn(info);
        Player spieler = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        when(proxy.getPlayer(uuid)).thenReturn(Optional.of(spieler));

        kanal.fuehreAus(uuid, "   ", quelle);

        verify(befehle, never()).executeAsync(any(), anyString());
        verify(logger).warn(anyString());
    }
}
