package de.lightrixx.punish.velocity;

import de.lightrixx.punish.command.PunishStatsCommand;
import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.HistoryEntry;
import de.lightrixx.punish.model.HistoryType;
import de.lightrixx.punish.service.HistoryService;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.storage.PlayerCache;
import de.lightrixx.punish.util.TimeParser;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PunishStatsCommandTest {

    private static final long TAG = 24L * 60L * 60L * 1000L;

    private MessageService messageService;
    private HistoryService historyService;
    private PunishStatsCommand befehl;

    @BeforeEach
    void aufsetzen() {
        messageService = Mockito.mock(MessageService.class);
        historyService = Mockito.mock(HistoryService.class);
        var config = Mockito.mock(Konfiguration.class);
        var protection = Mockito.mock(ProtectionService.class);
        var playerCache = Mockito.mock(PlayerCache.class);
        var verzeichnis = Mockito.mock(SpielerVerzeichnis.class);
        when(messageService.komponenteOhnePraefix(anyString(), anyMap()))
                .thenReturn(Component.text("zeile"));
        befehl = new PunishStatsCommand(messageService, config, protection, playerCache,
                verzeichnis, historyService, null, new TimeParser());
    }

    private static HistoryEntry eintrag(long id, HistoryType typ, String staff,
                                        long alterMillis) {
        return new HistoryEntry(id, typ, staff, "Steve", "Grund",
                System.currentTimeMillis() - alterMillis, null, true);
    }

    @Test
    void zeitraumFiltertAlteEintraegeUndAggregiertProStaff() {
        when(historyService.findeAlle()).thenReturn(List.of(
                eintrag(1, HistoryType.BAN, "ModA", TAG),
                eintrag(2, HistoryType.MUTE, "ModB", TAG),
                eintrag(3, HistoryType.BAN, "ModA", 10 * TAG)));

        befehl.ausfuehren(Mockito.mock(BefehlsSender.class), new String[]{"7d"});

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(messageService, Mockito.times(2)).komponenteOhnePraefix(
                eq(MessageKeys.PUNISH_STATS_ZEILE), captor.capture());
        List<Map<String, String>> zeilen = captor.getAllValues();
        assertEquals(2, zeilen.size());
        Map<String, String> erster = zeilen.getFirst();
        assertEquals("ModA", erster.get("staff"));
        assertEquals("1", erster.get("banns"));
        assertEquals("1", zeilen.get(1).get("mutes"));
    }

    @Test
    void ungueltigerZeitraumMeldetFehler() {
        befehl.ausfuehren(Mockito.mock(BefehlsSender.class), new String[]{"7x", "ModA"});
        verify(messageService).senden(Mockito.any(), eq(MessageKeys.PUNISH_STATS_ZEITRAUM_UNGUELTIG),
                anyMap());
    }
}
