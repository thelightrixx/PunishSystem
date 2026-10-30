package de.lightrixx.punish.velocity;

import de.lightrixx.punish.command.PunishExportCommand;
import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.HistoryEntry;
import de.lightrixx.punish.model.HistoryType;
import de.lightrixx.punish.model.NoteRecord;
import de.lightrixx.punish.model.ReportRecord;
import de.lightrixx.punish.model.ReportStatus;
import de.lightrixx.punish.model.WarnRecord;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.storage.HistoryRepository;
import de.lightrixx.punish.storage.NoteRepository;
import de.lightrixx.punish.storage.PlayerCache;
import de.lightrixx.punish.storage.ReportRepository;
import de.lightrixx.punish.storage.WarnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PunishExportCommandTest {

    @TempDir
    File datenOrdner;

    private MessageService messageService;
    private BefehlsSender sender;
    private PunishExportCommand befehl;

    @BeforeEach
    void aufsetzen() {
        messageService = Mockito.mock(MessageService.class);
        sender = Mockito.mock(BefehlsSender.class);
        var config = Mockito.mock(Konfiguration.class);
        when(config.anzeigeZone()).thenReturn(ZoneId.systemDefault());
        var history = Mockito.mock(HistoryRepository.class);
        var warns = Mockito.mock(WarnRepository.class);
        var notes = Mockito.mock(NoteRepository.class);
        var reports = Mockito.mock(ReportRepository.class);
        UUID uuid = UUID.randomUUID();

        when(history.findeAlle(anyInt())).thenReturn(List.of(
                new HistoryEntry(1, HistoryType.BAN, "ModA", "Steve", "Cheating",
                        System.currentTimeMillis(), null, true)));
        when(warns.alle()).thenReturn(Map.of(uuid, List.of(
                new WarnRecord(1, "Steve", "Spam", "ModA", System.currentTimeMillis()))));
        when(notes.alle()).thenReturn(Map.of(uuid, List.of(
                new NoteRecord(1, "Steve", "Beobachtung", "ModA", System.currentTimeMillis()))));
        when(reports.alle()).thenReturn(List.of(new ReportRecord(1, uuid, "Steve", uuid,
                "Melder", "Grund", "lobby", System.currentTimeMillis(),
                ReportStatus.ERLEDIGT, "ModA", System.currentTimeMillis(), "geprüft")));

        befehl = new PunishExportCommand(messageService, config,
                Mockito.mock(ProtectionService.class), Mockito.mock(PlayerCache.class),
                Mockito.mock(SpielerVerzeichnis.class), Mockito.mock(AuftragsPlaner.class),
                history, warns, notes, reports, datenOrdner);
    }

    @Test
    @SuppressWarnings("unchecked")
    void exportSchreibtJsonMitAllenBereichen() throws Exception {
        befehl.ausfuehren(sender, new String[0]);

        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(messageService).senden(eq(sender), eq(MessageKeys.PUNISH_EXPORT_ERFOLG), captor.capture());
        String pfad = captor.getValue().get("pfad");
        assertEquals("4", captor.getValue().get("eintraege"));
        String inhalt = Files.readString(new File(pfad).toPath(), StandardCharsets.UTF_8);
        assertTrue(inhalt.contains("\"history\""));
        assertTrue(inhalt.contains("\"ModA\""));
        assertTrue(inhalt.contains("\"warns\""));
        assertTrue(inhalt.contains("\"notes\""));
        assertTrue(inhalt.contains("\"reports\""));
        assertTrue(inhalt.endsWith("}"));
    }
}
