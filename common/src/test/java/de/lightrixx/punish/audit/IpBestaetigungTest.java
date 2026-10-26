package de.lightrixx.punish.audit;

import de.lightrixx.punish.command.BanIpCommand;
import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.OnlineSpieler;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.CachedPlayer;
import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.service.IpBanService;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.storage.PlayerCache;
import de.lightrixx.punish.util.TimeParser;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IpBestaetigungTest {

    private BefehlsSender sender() {
        BefehlsSender sender = mock(BefehlsSender.class);
        when(sender.hatRecht(anyString())).thenReturn(true);
        when(sender.istSpieler()).thenReturn(false);
        when(sender.name()).thenReturn("Konsole");
        return sender;
    }

    private Konfiguration config() {
        Konfiguration config = mock(Konfiguration.class);
        when(config.standardScope()).thenReturn(ServerScope.global());
        when(config.bekannteServer()).thenReturn(List.of("lobby"));
        when(config.banMaxMinuten()).thenReturn(525600L);
        when(config.banMinMinuten()).thenReturn(1L);
        when(config.standardGrund(anyString())).thenReturn("Regelverstoß");
        when(config.serverName()).thenReturn("lobby");
        when(config.ipGeteiltWarnAb()).thenReturn(4);
        when(config.ipGeteiltBlockAb()).thenReturn(11);
        when(config.ipGeteiltBestaetigungAb()).thenReturn(2);
        return config;
    }

    private SpielerVerzeichnis verzeichnisMitGeteilterIp() {
        SpielerVerzeichnis verzeichnis = mock(SpielerVerzeichnis.class);
        when(verzeichnis.onlineSpieler()).thenReturn(List.of(
                new OnlineSpieler(UUID.randomUUID(), "SpielerA", "10.0.0.5", false),
                new OnlineSpieler(UUID.randomUUID(), "SpielerB", "10.0.0.5", false)));
        return verzeichnis;
    }

    private BanIpCommand befehl(MessageService messages, Konfiguration config,
                                SpielerVerzeichnis verzeichnis, IpBanService ipBanService) {
        PlayerCache cache = mock(PlayerCache.class);
        when(cache.mitKanonischerIp(anyString())).thenReturn(List.of());
        return new BanIpCommand(messages, config, mock(ProtectionService.class), cache,
                ipBanService, new TimeParser(),
                mock(de.lightrixx.punish.core.AuftragsPlaner.class), verzeichnis);
    }

    @Test
    void geteilteIpOhneConfirmFordertBestaetigung() {
        MessageService messages = mock(MessageService.class);
        when(messages.komponente(eq(MessageKeys.BESTAETIGUNG_NOETIG), any(Map.class)))
                .thenReturn(Component.text("bestaetigen"));
        IpBanService ipBanService = mock(IpBanService.class);
        BanIpCommand befehl = befehl(messages, config(), verzeichnisMitGeteilterIp(), ipBanService);

        befehl.ausfuehren(sender(), new String[]{"10.0.0.5", "7d", "Werbung"});

        verify(messages).sendeKomponente(any(), any());
        verify(ipBanService, never()).ipBannenTemporaer(anyString(), any(long.class),
                anyString(), anyString(), any(), anyString(), any(boolean.class));
    }

    @Test
    void geteilteIpMitConfirmWirdGebannt() {
        MessageService messages = mock(MessageService.class);
        IpBanService ipBanService = mock(IpBanService.class);
        when(ipBanService.ipBannenTemporaer(anyString(), any(long.class), anyString(),
                anyString(), any(), anyString(), any(boolean.class))).thenReturn(true);
        BanIpCommand befehl = befehl(messages, config(), verzeichnisMitGeteilterIp(), ipBanService);

        befehl.ausfuehren(sender(), new String[]{"10.0.0.5", "confirm", "7d", "Werbung"});

        verify(ipBanService).ipBannenTemporaer(eq("10.0.0.5"), eq(7L * 86400000L),
                eq("Werbung"), eq("Konsole"), eq(ServerScope.global()), eq("lobby"), eq(false));
    }

    @Test
    void einzelneIpBrauchtKeineBestaetigung() {
        MessageService messages = mock(MessageService.class);
        IpBanService ipBanService = mock(IpBanService.class);
        when(ipBanService.ipBannenTemporaer(anyString(), any(long.class), anyString(),
                anyString(), any(), anyString(), any(boolean.class))).thenReturn(true);
        SpielerVerzeichnis verzeichnis = mock(SpielerVerzeichnis.class);
        when(verzeichnis.onlineSpieler()).thenReturn(List.of(
                new OnlineSpieler(UUID.randomUUID(), "Solo", "10.0.0.9", false)));
        BanIpCommand befehl = befehl(messages, config(), verzeichnis, ipBanService);

        befehl.ausfuehren(sender(), new String[]{"10.0.0.9", "7d", "Werbung"});

        verify(ipBanService).ipBannenTemporaer(eq("10.0.0.9"), any(long.class),
                anyString(), anyString(), any(), anyString(), any(boolean.class));
        verify(messages, never()).sendeKomponente(any(), any());
    }
}
