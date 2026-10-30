package de.lightrixx.punish.velocity;

import de.lightrixx.punish.command.PunishCommand;
import de.lightrixx.punish.command.PunishUndoCommand;
import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.HistoryEntry;
import de.lightrixx.punish.model.HistoryType;
import de.lightrixx.punish.service.HistoryService;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.service.WarnService;
import de.lightrixx.punish.storage.PlayerCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalMatchers;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PunishUndoCommandTest {

    private MessageService messageService;
    private HistoryService historyService;
    private BefehlsSender sender;
    private PunishCommand pardon;
    private PunishCommand unmute;
    private PunishCommand warn;
    private PunishUndoCommand befehl;

    @BeforeEach
    void aufsetzen() {
        messageService = Mockito.mock(MessageService.class);
        historyService = Mockito.mock(HistoryService.class);
        var config = Mockito.mock(Konfiguration.class);
        var protection = Mockito.mock(ProtectionService.class);
        var playerCache = Mockito.mock(PlayerCache.class);
        var verzeichnis = Mockito.mock(SpielerVerzeichnis.class);
        var warnService = Mockito.mock(WarnService.class);
        sender = Mockito.mock(BefehlsSender.class);
        pardon = Mockito.mock(PunishCommand.class);
        unmute = Mockito.mock(PunishCommand.class);
        warn = Mockito.mock(PunishCommand.class);

        when(sender.name()).thenReturn("ModA");
        when(sender.hatRecht(anyString())).thenReturn(true);
        when(pardon.hatRecht(any(), any())).thenReturn(true);
        when(unmute.hatRecht(any(), any())).thenReturn(true);
        when(warn.hatRecht(any(), any())).thenReturn(true);

        Map<String, PunishCommand> ziele = new LinkedHashMap<>();
        ziele.put("pardon", pardon);
        ziele.put("unmute", unmute);
        ziele.put("warn", warn);
        befehl = new PunishUndoCommand(messageService, config, protection, playerCache,
                verzeichnis, historyService, warnService, ziele);
    }

    private static HistoryEntry eintrag(long id, HistoryType typ, String ausfuehrer, String ziel,
                                        String grund) {
        return new HistoryEntry(id, typ, ausfuehrer, ziel, grund,
                System.currentTimeMillis(), null, true);
    }

    @Test
    void banWirdUeberPardonZurueckgenommen() {
        when(historyService.findeAlle())
                .thenReturn(List.of(eintrag(1, HistoryType.BAN, "ModA", "Steve", "Cheating")));

        befehl.ausfuehren(sender, new String[0]);

        verify(pardon).ausfuehren(eq(sender),
                AdditionalMatchers.aryEq(new String[]{"Steve"}));
        verify(warn, never()).ausfuehren(any(), any());
    }

    @Test
    void fremdeAktionWirdNichtZurueckgenommen() {
        when(historyService.findeAlle())
                .thenReturn(List.of(eintrag(1, HistoryType.BAN, "ModB", "Steve", "Cheating")));

        befehl.ausfuehren(sender, new String[0]);

        verify(messageService).senden(eq(sender), eq(MessageKeys.PUNISH_UNDO_KEINE), anyMap());
        verify(pardon, never()).ausfuehren(any(), any());
    }

    @Test
    void kickIstNichtUmkehrbar() {
        when(historyService.findeAlle())
                .thenReturn(List.of(eintrag(1, HistoryType.KICK, "ModA", "Steve", "AFK")));

        befehl.ausfuehren(sender, new String[0]);

        verify(messageService).senden(eq(sender), eq(MessageKeys.PUNISH_UNDO_NICHT), anyMap());
        verify(pardon, never()).ausfuehren(any(), any());
    }

    @Test
    void fehlendesZielRechtBlockiertDelegation() {
        when(historyService.findeAlle())
                .thenReturn(List.of(eintrag(1, HistoryType.BAN, "ModA", "Steve", "Cheating")));
        when(pardon.hatRecht(any(), any())).thenReturn(false);

        befehl.ausfuehren(sender, new String[0]);

        verify(messageService).senden(sender, MessageKeys.KEIN_RECHT);
        verify(pardon, never()).ausfuehren(any(), any());
    }

    @Test
    void neuesteAktionGewinnt() {
        when(historyService.findeAlle()).thenReturn(List.of(
                eintrag(1, HistoryType.BAN, "ModA", "Steve", "Cheating"),
                eintrag(2, HistoryType.MUTE, "ModA", "Alex", "Spam")));

        befehl.ausfuehren(sender, new String[0]);

        verify(unmute).ausfuehren(eq(sender),
                AdditionalMatchers.aryEq(new String[]{"Alex"}));
    }

    @Test
    void spielerFilterWaehltPassendeAktion() {
        when(historyService.findeAlle()).thenReturn(List.of(
                eintrag(1, HistoryType.BAN, "ModA", "Steve", "Cheating"),
                eintrag(2, HistoryType.BAN, "ModA", "Alex", "Spam")));

        befehl.ausfuehren(sender, new String[]{"Steve"});

        verify(pardon).ausfuehren(eq(sender),
                AdditionalMatchers.aryEq(new String[]{"Steve"}));
    }
}
