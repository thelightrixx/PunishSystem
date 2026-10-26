package de.lightrixx.punish.audit;

import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.model.BanRecord;
import de.lightrixx.punish.model.HistoryEntry;
import de.lightrixx.punish.model.HistoryType;
import de.lightrixx.punish.model.IpBanRecord;
import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.service.BanService;
import de.lightrixx.punish.service.BroadcastService;
import de.lightrixx.punish.service.HistoryService;
import de.lightrixx.punish.service.IpBanService;
import de.lightrixx.punish.service.ScreenTexte;
import de.lightrixx.punish.storage.BanRepository;
import de.lightrixx.punish.storage.HistoryRepository;
import de.lightrixx.punish.storage.IoExecutor;
import de.lightrixx.punish.storage.IpBanRepository;
import de.lightrixx.punish.storage.LogWriter;
import de.lightrixx.punish.storage.YamlSpeicher;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditV4RegressionTest {

    @TempDir
    File datenOrdner;

    private YamlSpeicher speicherMock() {
        return mock(YamlSpeicher.class);
    }

    private IoExecutor ioMock() {
        return mock(IoExecutor.class);
    }

    private HistoryService historyServiceNeu() {
        HistoryRepository repo = new HistoryRepository(datenOrdner, speicherMock(), ioMock());
        return new HistoryService(repo);
    }

    @Test
    @DisplayName("V4-01: entbannen eines abgelaufenen Banns meldet false (kein falscher Erfolg)")
    void entbannenAbgelaufenMeldetFalse() {
        BanRepository repo = new BanRepository(datenOrdner, speicherMock(), ioMock());
        HistoryService history = historyServiceNeu();
        BanService service = new BanService(repo, history, mock(BroadcastService.class),
                mock(LogWriter.class), mock(de.lightrixx.punish.service.ProtectionService.class),
                mock(de.lightrixx.punish.service.BanKickHelper.class),
                mock(de.lightrixx.punish.core.SpielerVerzeichnis.class));
        UUID uuid = UUID.randomUUID();
        long jetzt = System.currentTimeMillis();

        repo.bannenWennFrei(new BanRecord(uuid, "Steve", "Alt", "Mod",
                jetzt - 7_200_000L, jetzt - 3_600_000L, false, ServerScope.server("lobby")));
        boolean erfolg = service.entbannen(uuid, "Steve", "Mod",
                ServerScope.server("lobby"), "lobby");
        assertFalse(erfolg, "Abgelaufener Bann darf keinen Erfolg melden");
        assertTrue(repo.finden(uuid, ServerScope.server("lobby")).isEmpty(),
                "Abgelaufener Bann muss bereinigt sein");
    }

    @Test
    @DisplayName("V4-02: IP-Screen dauer ist Gesamtdauer, nicht Restzeit")
    void ipScreenDauerIstGesamt() {
        Konfiguration config = mock(Konfiguration.class);
        when(config.screen(MessageKeys.SCREEN_IP_BANN)).thenReturn("Dauer:%dauer% Rest:%verbleibend%");
        long jetzt = System.currentTimeMillis();
        long erstellt = jetzt - 6L * 24 * 3_600_000L;
        long ablauf = erstellt + 7L * 24 * 3_600_000L;
        IpBanRecord record = new IpBanRecord("192.0.2.55", "Test", "Mod", erstellt, ablauf,
                ServerScope.global());
        Component screen = ScreenTexte.baueIpBannScreen(record, config);
        String text = de.lightrixx.punish.util.MiniMessageUtil.miniMessage().serialize(screen);

        String gesamt = de.lightrixx.punish.util.ZeitFormat.formatRestzeit(ablauf - erstellt);
        assertEquals("7 Tage", gesamt, "Testannahme: Gesamtdauer ist 7 Tage");
        assertTrue(text.contains("Dauer:" + gesamt),
                "Dauer muss die Gesamtdauer (" + gesamt + ") zeigen, war: " + text);
        int restIndex = text.indexOf("Rest:");
        assertTrue(restIndex > text.indexOf("Dauer:"), "Restzeit-Platzhalter muss gesetzt sein: " + text);
        String restTeil = text.substring(restIndex + "Rest:".length()).trim();
        assertFalse(restTeil.isEmpty(), "Restzeit darf nicht leer sein: " + text);
        assertNotEquals(gesamt, restTeil, "Restzeit darf nicht die Gesamtdauer sein: " + text);
        assertTrue(restTeil.contains("Stunde") || restTeil.contains("Tag"),
                "Restzeit muss eine Restdauer zeigen, war: " + restTeil);
    }

    @Test
    @DisplayName("V4-03: RESET deaktiviert auch VERSTOSS_REMOVE; BAN_EDIT schont AUTO_BANN")
    void historyAufhebungstypen() {
        HistoryRepository repo = new HistoryRepository(datenOrdner, speicherMock(), ioMock());
        HistoryService history = new HistoryService(repo);
        UUID uuid = UUID.randomUUID();
        history.hinzufuegen(HistoryType.VERSTOSS_REMOVE, "Mod", uuid, "Steve", "weg", null,
                ServerScope.server("lobby"), "lobby");
        history.hinzufuegen(HistoryType.VERSTOSS_ADD, "Mod", uuid, "Steve", "neu", null,
                ServerScope.server("lobby"), "lobby");
        history.hinzufuegen(HistoryType.RESET, "Mod", uuid, "Steve", "clear", null,
                ServerScope.server("lobby"), "lobby");
        List<HistoryEntry> nachReset = repo.finden(uuid, 10);
        for (HistoryEntry e : nachReset) {
            if (e.typ() == HistoryType.VERSTOSS_REMOVE || e.typ() == HistoryType.VERSTOSS_ADD) {
                assertFalse(e.aktiv(), "RESET muss " + e.typ() + " deaktivieren");
            }
        }
        UUID uuid2 = UUID.randomUUID();
        history.hinzufuegen(HistoryType.AUTO_BANN, "System", uuid2, "Alex", "auto", null,
                ServerScope.global(), "unbekannt");
        history.hinzufuegen(HistoryType.BAN_EDIT, "Mod", uuid2, "Alex", "edit", null,
                ServerScope.global(), "lobby");
        List<HistoryEntry> nachEdit = repo.finden(uuid2, 10);
        boolean autoNochAktiv = nachEdit.stream()
                .filter(e -> e.typ() == HistoryType.AUTO_BANN)
                .findFirst().orElseThrow().aktiv();
        assertTrue(autoNochAktiv, "BAN_EDIT darf fremde AUTO_BANN-History nicht deaktivieren");
    }

    @Test
    @DisplayName("V4-04: Lobby-Pardon deaktiviert globalen Bann nicht in der History")
    void historyAufhebungIstScopeScharf() {
        HistoryRepository repo = new HistoryRepository(datenOrdner, speicherMock(), ioMock());
        HistoryService history = new HistoryService(repo);
        UUID uuid = UUID.randomUUID();
        history.hinzufuegen(HistoryType.BAN, "Mod", uuid, "Steve", "globaler Bann", null,
                ServerScope.global(), "unbekannt");
        history.hinzufuegen(HistoryType.PARDON, "Mod", uuid, "Steve", "Lobby-Pardon", null,
                ServerScope.server("lobby"), "lobby");
        List<HistoryEntry> eintraege = repo.finden(uuid, 10);
        boolean globalNochAktiv = eintraege.stream()
                .filter(e -> e.typ() == HistoryType.BAN)
                .findFirst().orElseThrow().aktiv();
        assertTrue(globalNochAktiv, "Lobby-Pardon darf globalen BAN nicht deaktivieren");

        history.hinzufuegen(HistoryType.PARDON, "Mod", uuid, "Steve", "Global-Pardon", null,
                ServerScope.global(), "unbekannt");
        List<HistoryEntry> danach = repo.finden(uuid, 10);
        for (HistoryEntry e : danach) {
            if (e.typ() == HistoryType.BAN) {
                assertFalse(e.aktiv(), "Global-Pardon muss covering wirken");
            }
        }
    }

    @Test
    @DisplayName("V4-05: Perm-IP-Bann eskaliert keinen globalen Temp-Bann via --lobby")
    void ipPermEskaliertNurExaktenScope() {
        IpBanRepository repo = new IpBanRepository(datenOrdner, speicherMock(), ioMock());
        HistoryService history = historyServiceNeu();
        IpBanService service = new IpBanService(repo, history, mock(BroadcastService.class),
                mock(LogWriter.class), mock(de.lightrixx.punish.service.BanKickHelper.class),
                mock(de.lightrixx.punish.service.ProtectionService.class),
                mock(de.lightrixx.punish.core.SpielerVerzeichnis.class));
        long jetzt = System.currentTimeMillis();

        repo.bannenWennFrei(new IpBanRecord("192.0.2.60", "Temp", "Mod", jetzt,
                jetzt + 3_600_000L, ServerScope.global()));
        boolean permOk = service.ipBannen("192.0.2.60", "Perm", "Mod",
                ServerScope.server("lobby"), "lobby");
        assertTrue(permOk, "Lobby-Perm muss als separater Eintrag möglich sein");
        var global = repo.finden("192.0.2.60", ServerScope.global());
        assertTrue(global.isPresent(), "Globaler Temp-Bann muss unangetastet bleiben");
        assertNotNull(global.get().ablaufAm(), "Globaler Bann darf nicht permanent eskaliert sein");
        var lobby = repo.finden("192.0.2.60", ServerScope.server("lobby"));
        assertTrue(lobby.isPresent() && lobby.get().ablaufAm() == null,
                "Lobby-Perm muss separat existieren");
    }

    @Test
    @DisplayName("V4-06: abgelaufener Temp-IP-Bann schreibt Abgelaufen-History statt still zu verschwinden")
    void ipAbgelaufenSchreibtHistory() {
        IpBanRepository repo = new IpBanRepository(datenOrdner, speicherMock(), ioMock());
        HistoryRepository historyRepo = new HistoryRepository(datenOrdner, speicherMock(), ioMock());
        HistoryService history = new HistoryService(historyRepo);
        IpBanService service = new IpBanService(repo, history, mock(BroadcastService.class),
                mock(LogWriter.class), mock(de.lightrixx.punish.service.BanKickHelper.class),
                mock(de.lightrixx.punish.service.ProtectionService.class),
                mock(de.lightrixx.punish.core.SpielerVerzeichnis.class));
        long jetzt = System.currentTimeMillis();
        repo.bannenWennFrei(new IpBanRecord("192.0.2.61", "Temp", "Mod", jetzt - 7_200_000L,
                jetzt - 3_600_000L, ServerScope.server("lobby")));
        var ergebnis = service.ipEntbannen("192.0.2.61", "Mod", ServerScope.server("lobby"), "lobby");
        assertTrue(ergebnis.isEmpty(), "Abgelaufener IP-Bann meldet leer (kein falscher Erfolg)");
        List<HistoryEntry> alle = historyRepo.findeAlle(50);
        boolean abgelaufenVermerkt = alle.stream()
                .anyMatch(e -> e.typ() == HistoryType.UNBAN_IP && e.grund().contains("Abgelaufen"));
        assertTrue(abgelaufenVermerkt, "Abgelaufener IP-Bann braucht UNBAN_IP-Abgelaufen-History");
    }

    @Test
    @DisplayName("V4-07: Vorab-Migration zieht alle Scopes um, nicht nur den ersten")
    void vorabMigrationAlleScopes() {
        BanRepository repo = new BanRepository(datenOrdner, speicherMock(), ioMock());
        HistoryService history = historyServiceNeu();
        BanService service = new BanService(repo, history, mock(BroadcastService.class),
                mock(LogWriter.class), mock(de.lightrixx.punish.service.ProtectionService.class),
                mock(de.lightrixx.punish.service.BanKickHelper.class),
                mock(de.lightrixx.punish.core.SpielerVerzeichnis.class));
        UUID vorab = UUID.nameUUIDFromBytes(
                ("OfflinePlayer:Steve").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UUID echt = UUID.randomUUID();
        long jetzt = System.currentTimeMillis();
        repo.bannenWennFrei(new BanRecord(vorab, "Steve", "Lobby", "Mod", jetzt,
                jetzt + 3_600_000L, false, ServerScope.server("lobby")));
        repo.bannenWennFrei(new BanRecord(vorab, "Steve", "Survival", "Mod", jetzt,
                jetzt + 3_600_000L, false, ServerScope.server("survival")));
        var migriert = service.migriereVorabBann(vorab, echt, "Steve");
        assertTrue(migriert.isPresent(), "Migration muss greifen");
        assertTrue(repo.finden(echt, ServerScope.server("lobby")).isPresent(),
                "Lobby-Bann muss migriert sein");
        assertTrue(repo.finden(echt, ServerScope.server("survival")).isPresent(),
                "Survival-Bann muss ebenfalls migriert sein (kein break nach erstem)");
        assertTrue(repo.finden(vorab, ServerScope.server("lobby")).isEmpty()
                && repo.finden(vorab, ServerScope.server("survival")).isEmpty(),
                "Vorab-Keys müssen aufgeräumt sein");
    }
}
