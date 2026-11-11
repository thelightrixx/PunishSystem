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
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
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

    @Test
    void sucheFindetAlleDreiBereicheUndSeitennummern() {
        var config = Mockito.mock(Konfiguration.class);
        when(config.historyEintraegeProSeite()).thenReturn(1);
        var history = Mockito.mock(HistoryRepository.class);
        var notizen = Mockito.mock(NoteRepository.class);
        var reports = Mockito.mock(ReportRepository.class);
        UUID uuid = UUID.randomUUID();
        when(history.alleSchluessel()).thenReturn(Map.of(uuid, List.of(
                new HistoryEntry(1, HistoryType.BAN, "Mod", "Steve", "Spam im Chat", 1000L, null, true))));
        when(notizen.alle()).thenReturn(Map.of(uuid, List.of(
                new NoteRecord(1, "Steve", "Spam im Chat", "Mod", 2000L))));
        when(reports.alle()).thenReturn(List.of(new ReportRecord(1, uuid, "Steve", null,
                "Alex", "Spam im Chat", "lobby", 3000L, ReportStatus.OFFEN, "", 0L, "")));
        var suche = new VelocityBefehle.Suche(messageService, config,
                Mockito.mock(ProtectionService.class), Mockito.mock(PlayerCache.class),
                Mockito.mock(SpielerVerzeichnis.class), history, notizen, reports);

        assertFalse(suche.sichtbarFuer(sender));
        when(sender.hatRecht("punish.search")).thenReturn(true);
        assertTrue(suche.sichtbarFuer(sender));
        for (int seite = 1; seite <= 3; seite++) {
            suche.ausfuehren(sender, new String[]{"SPAM", "im", "chat", "page:" + seite});
            verify(messageService).komponente(MessageKeys.PUNISH_SEARCH_KOPF, Map.of(
                    "anzahl", "3", "mehr", "", "seite", String.valueOf(seite), "seiten", "3"));
        }

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> zeilen = ArgumentCaptor.forClass(Map.class);
        verify(messageService, Mockito.times(3)).komponenteOhnePraefix(
                eq(MessageKeys.PUNISH_SEARCH_ZEILE), zeilen.capture());
        assertEquals(List.of("Report", "Notiz", "Bann"),
                zeilen.getAllValues().stream().map(zeile -> zeile.get("typ")).toList());
    }

    @Test
    void sucheBegrenztAufZweihundertTreffer() {
        var config = Mockito.mock(Konfiguration.class);
        when(config.historyEintraegeProSeite()).thenReturn(100);
        var history = Mockito.mock(HistoryRepository.class);
        when(history.alleSchluessel()).thenReturn(Map.of(UUID.randomUUID(),
                IntStream.rangeClosed(1, 202).mapToObj(id -> new HistoryEntry(id, HistoryType.BAN,
                        "Mod", "Steve", "Spam", id, null, true)).toList()));
        var suche = new VelocityBefehle.Suche(messageService, config,
                Mockito.mock(ProtectionService.class), Mockito.mock(PlayerCache.class),
                Mockito.mock(SpielerVerzeichnis.class), history,
                Mockito.mock(NoteRepository.class), Mockito.mock(ReportRepository.class));

        suche.ausfuehren(sender, new String[]{"spam", "page:99"});

        verify(messageService).komponente(MessageKeys.PUNISH_SEARCH_KOPF,
                Map.of("anzahl", "200", "mehr", "+", "seite", "2", "seiten", "2"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> zeilen = ArgumentCaptor.forClass(Map.class);
        verify(messageService, Mockito.times(100)).komponenteOhnePraefix(
                eq(MessageKeys.PUNISH_SEARCH_ZEILE), zeilen.capture());
        assertEquals("102", zeilen.getAllValues().getFirst().get("id"));
        assertEquals("3", zeilen.getAllValues().getLast().get("id"));
    }
}
