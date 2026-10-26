package de.lightrixx.punish.command;

import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.service.BanService;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.storage.IoExecutor;
import de.lightrixx.punish.storage.PlayerCache;
import de.lightrixx.punish.util.TimeParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MassenRueckstauTest {

    @Test
    void massenbefehlBrichtBeiIoRueckstauAb() {
        MessageService messages = mock(MessageService.class);
        Konfiguration config = mock(Konfiguration.class);
        when(config.bestaetigungBanAll()).thenReturn(true);
        when(config.standardScope()).thenReturn(de.lightrixx.punish.model.ServerScope.global());
        BanService banService = mock(BanService.class);
        IoExecutor io = mock(IoExecutor.class);
        when(io.istUeberlastet()).thenReturn(true);
        when(io.anzahlAusstehend()).thenReturn(2500);

        BanAllCommand befehl = new BanAllCommand(messages, config, mock(ProtectionService.class),
                mock(PlayerCache.class), banService, new TimeParser(), mock(AuftragsPlaner.class),
                mock(SpielerVerzeichnis.class));
        befehl.setIoExecutor(io);

        BefehlsSender sender = mock(BefehlsSender.class);
        when(sender.hatRecht(anyString())).thenReturn(true);

        befehl.ausfuehren(sender, new String[]{"7d", "Grund", "confirm"});

        verify(messages).senden(eq(sender), eq(MessageKeys.MASSEN_IO_RUECKSTAU), any(Map.class));
        verifyNoInteractions(banService);
    }

    @Test
    void ohneRueckstauLaeuftDerBefehlWeiter() {
        MessageService messages = mock(MessageService.class);
        Konfiguration config = mock(Konfiguration.class);
        when(config.bestaetigungBanAll()).thenReturn(false);
        when(config.standardScope()).thenReturn(de.lightrixx.punish.model.ServerScope.global());
        when(config.banMaxMinuten()).thenReturn(525600L);
        when(config.banMinMinuten()).thenReturn(1L);
        when(config.standardGrund(anyString())).thenReturn("Regelverstoß");
        when(config.bekannteServer()).thenReturn(java.util.List.of("lobby"));
        BanService banService = mock(BanService.class);
        when(banService.banAlle(anyLong(), anyBoolean(), anyString(), anyString(), any(), any(), any(),
                anyBoolean())).thenReturn(new de.lightrixx.punish.service.MassenErgebnis(0, 0));
        IoExecutor io = mock(IoExecutor.class);
        when(io.istUeberlastet()).thenReturn(false);

        SpielerVerzeichnis verzeichnis = mock(SpielerVerzeichnis.class);
        when(verzeichnis.onlineSpieler()).thenReturn(java.util.List.of());
        BanAllCommand befehl = new BanAllCommand(messages, config, mock(ProtectionService.class),
                mock(PlayerCache.class), banService, new TimeParser(), mock(AuftragsPlaner.class),
                verzeichnis);
        befehl.setIoExecutor(io);

        BefehlsSender sender = mock(BefehlsSender.class);
        when(sender.hatRecht(anyString())).thenReturn(true);

        befehl.ausfuehren(sender, new String[]{"7d", "Grund"});

        verify(banService).banAlle(anyLong(), anyBoolean(), anyString(), anyString(), any(), any(), any(),
                anyBoolean());
        assertFalse(org.mockito.Mockito.mockingDetails(messages).getInvocations().stream()
                        .anyMatch(inv -> MessageKeys.MASSEN_IO_RUECKSTAU.equals(inv.getArgument(1))),
                "Ohne Rückstau darf die Rückstau-Meldung nicht erscheinen");
    }

    @Test
    void frischerExecutorIstGesundUndNichtUeberlastet() {
        IoExecutor io = new IoExecutor();
        try {
            assertFalse(io.istUeberlastet());
            assertTrue(io.istGesund());
        } finally {
            io.herunterfahren(2, java.util.concurrent.TimeUnit.SECONDS);
        }
    }
}
