package de.lightrixx.punish.bridge;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

class TunnelNormalisierungTest {

    @Test
    void aliasWirdKanonischUebertragen() {
        KanalClient kanal = Mockito.mock(KanalClient.class);
        BridgeBefehlWeiterleitung weiterleitung =
                new BridgeBefehlWeiterleitung(kanal, "mute", "punish.mute", true);
        Player spieler = Mockito.mock(Player.class);
        UUID uuid = UUID.randomUUID();
        Mockito.when(spieler.getUniqueId()).thenReturn(uuid);
        Mockito.when(spieler.hasPermission(Mockito.anyString())).thenReturn(true);
        weiterleitung.fuehreAus(spieler, new String[]{"Ziel", "10m", "Spam"});
        verify(kanal).sendeBefehl(eq(uuid), eq("mute Ziel 10m Spam"));
    }

    @Test
    void konsoleSendetOhneUuid() {
        KanalClient kanal = Mockito.mock(KanalClient.class);
        BridgeBefehlWeiterleitung weiterleitung =
                new BridgeBefehlWeiterleitung(kanal, "kick", "punish.kick", false);
        CommandSender konsole = Mockito.mock(CommandSender.class);
        Mockito.when(konsole.hasPermission(Mockito.anyString())).thenReturn(true);
        weiterleitung.fuehreAus(konsole, new String[]{"Ziel", "Grund"});
        verify(kanal).sendeBefehl(eq(null), eq("kick Ziel Grund"));
    }
}
