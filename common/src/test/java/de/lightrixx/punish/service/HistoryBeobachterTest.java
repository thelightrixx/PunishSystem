package de.lightrixx.punish.service;

import de.lightrixx.punish.model.HistoryEntry;
import de.lightrixx.punish.model.HistoryType;
import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.storage.HistoryRepository;
import de.lightrixx.punish.storage.IoExecutor;
import de.lightrixx.punish.storage.YamlSpeicher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class HistoryBeobachterTest {
    @Test
    void stilleKennzeichnungBleibtAmExaktenEreignisUndFaerbtNichtAufFolgeaktionenAb(@TempDir File ordner) {
        var repo = new HistoryRepository(ordner, mock(YamlSpeicher.class), mock(IoExecutor.class));
        var service = new HistoryService(repo);
        var meldungen = new ArrayList<de.lightrixx.punish.model.HistoryEreignis>();
        var akte = new ArrayList<HistoryEntry>();
        service.ereignisBeobachterHinzufuegen(meldungen::add);
        service.beobachterHinzufuegen((uuid, h) -> akte.add(h));
        UUID uuid = UUID.randomUUID();
        service.hinzufuegen(HistoryType.MUTE, "Mod", uuid, "Spieler", "Still", null, ServerScope.global(), "lobby", true);
        service.hinzufuegen(HistoryType.WARN_ADD, "Mod", uuid, "Spieler", "Normal", null);
        service.hinzufuegen(HistoryType.BAN_IP, "Mod", "203.0.113.7", "Still", null, ServerScope.global(), "lobby", true);
        assertEquals(3, akte.size());
        assertTrue(meldungen.get(0).silent());
        assertFalse(meldungen.get(1).silent());
        assertTrue(meldungen.get(2).silent());
        assertEquals(akte.getFirst().id(), meldungen.getFirst().eintrag().id());
    }
    @Test
    void neueEreignisseMeldenExakteHistoryIdUndImportErzeugtKeineDoppelmeldung(@TempDir File ordner) {
        var repo = new HistoryRepository(ordner, mock(YamlSpeicher.class), mock(IoExecutor.class));
        var service = new HistoryService(repo);
        UUID uuid = UUID.randomUUID();
        var ereignisse = new ArrayList<HistoryEntry>();
        service.beobachterHinzufuegen((ziel, h) -> {
            assertEquals(uuid, ziel);
            ereignisse.add(h);
        });
        service.hinzufuegen(HistoryType.MUTE, "Mod", uuid, "Spieler", "Grund", null, ServerScope.global(), "lobby");
        assertEquals(1, ereignisse.size());
        assertEquals(repo.finden(uuid, 1).getFirst().id(), ereignisse.getFirst().id());
        repo.hinzufuegen(new HistoryEntry(400, HistoryType.WARN_ADD, "Mod", "Spieler", "Import", 1, null, true), uuid);
        assertEquals(1, ereignisse.size());
    }
}
