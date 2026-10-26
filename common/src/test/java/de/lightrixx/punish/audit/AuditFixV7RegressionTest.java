package de.lightrixx.punish.audit;

import de.lightrixx.punish.command.ScopeParser;
import de.lightrixx.punish.model.IpBanRecord;
import de.lightrixx.punish.model.MuteRecord;
import de.lightrixx.punish.model.NoteRecord;
import de.lightrixx.punish.model.ParseResult;
import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.model.TimeoutRecord;
import de.lightrixx.punish.net.Protokoll;
import de.lightrixx.punish.storage.IoExecutor;
import de.lightrixx.punish.storage.MuteRepository;
import de.lightrixx.punish.storage.NoteRepository;
import de.lightrixx.punish.storage.ReportRepository;
import de.lightrixx.punish.storage.TimeoutRepository;
import de.lightrixx.punish.storage.WarnRepository;
import de.lightrixx.punish.storage.YamlSpeicher;
import de.lightrixx.punish.util.TimeParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AuditFixV7RegressionTest {

    private IoExecutor io;

    @AfterEach
    void shutdown() {
        if (io != null) {
            try {
                io.herunterfahren(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
            io = null;
        }
    }

    static final class MapSpeicher implements YamlSpeicher {
        final Map<String, Map<String, Object>> dateien = new ConcurrentHashMap<>();

        @Override
        public Map<String, Object> laden(File datei) {
            Map<String, Object> daten = dateien.get(datei.getName());
            return daten == null ? Map.of() : new HashMap<>(daten);
        }

        @Override
        public void speichern(Map<String, Object> daten, File datei) {
            dateien.put(datei.getName(), new HashMap<>(daten));
        }
    }

    private IoExecutor executor() {
        io = new IoExecutor();
        return io;
    }

    @Test
    void timeParserVerstehtDeutscheEinheiten() {
        TimeParser parser = new TimeParser();
        assertInstanceOf(ParseResult.Geltzeit.class, parser.parsen("1t"));
        assertInstanceOf(ParseResult.Geltzeit.class, parser.parsen("5min"));
        assertInstanceOf(ParseResult.Geltzeit.class, parser.parsen("10sek"));
        assertInstanceOf(ParseResult.Geltzeit.class, parser.parsen("1j"));
        assertInstanceOf(ParseResult.Geltzeit.class, parser.parsen("2Stunden"));
        ParseResult.Geltzeit tag = (ParseResult.Geltzeit) parser.parsen("1t");
        assertEquals(24L * 60L * 60L * 1000L, tag.millis());
    }

    @Test
    void scopeStripBehaeltLegitimenGrund() {
        String[] args = {"Hacking", "--cool"};
        String[] bereinigt = ScopeParser.strippeScopeFlags(args, List.of("lobby", "survival"), false);
        assertArrayEquals(new String[]{"Hacking", "--cool"}, bereinigt);
    }

    @Test
    void scopeStripEntferntBekanntesFlag() {
        String[] args = {"Hacking", "--global"};
        String[] bereinigt = ScopeParser.strippeScopeFlags(args, List.of("lobby", "survival"), false);
        assertArrayEquals(new String[]{"Hacking"}, bereinigt);
    }

    @Test
    void muteOhneAblaufWirdQuarantaeniert(@TempDir File ordner) {
        MapSpeicher speicher = new MapSpeicher();
        Map<String, Object> eintrag = new HashMap<>();
        eintrag.put("spieler-name", "Steve");
        eintrag.put("grund", "Spam");
        eintrag.put("ausfuehrer", "Admin");
        eintrag.put("erstellt-am", System.currentTimeMillis());
        eintrag.put("scope", "global");
        Map<String, Object> daten = new HashMap<>();
        UUID uuid = UUID.randomUUID();
        daten.put(uuid + "|global", eintrag);
        speicher.dateien.put("mutes.yml", daten);
        MuteRepository repo = new MuteRepository(ordner, speicher, executor());
        repo.laden();
        assertTrue(repo.alleFuer(uuid).isEmpty());

        long jetzt = System.currentTimeMillis();
        assertTrue(repo.mutenWennFrei(new MuteRecord(UUID.randomUUID(), "Alex", "Spam",
                "Admin", jetzt, jetzt + 60_000L, ServerScope.global())));
        assertTrue(io.warteAufIdle(5, TimeUnit.SECONDS));

        Map<String, Object> nachSave = speicher.dateien.get("mutes.yml");
        String schluessel = uuid + "|global";
        assertTrue(nachSave.containsKey(schluessel),
                "Quarantänierter Rohsatz muss beim Speichern zurückgeschrieben werden");
        Object roh = nachSave.get(schluessel);
        assertInstanceOf(Map.class, roh);
        assertEquals("Steve", ((Map<?, ?>) roh).get("spieler-name"));
        assertFalse(((Map<?, ?>) roh).containsKey("ablauf-am"),
                "Die Quarantäne darf den Rohsatz nicht heilen oder ergänzen");

        MuteRepository neu = new MuteRepository(ordner, speicher, io);
        neu.laden();
        assertTrue(neu.alleFuer(uuid).isEmpty(), "Quarantänierter Satz bleibt inaktiv");
    }

    @Test
    void timeoutOhneAblaufWirdQuarantaeniert(@TempDir File ordner) {
        MapSpeicher speicher = new MapSpeicher();
        Map<String, Object> eintrag = new HashMap<>();
        eintrag.put("spieler-name", "Steve");
        eintrag.put("grund", "Spam");
        eintrag.put("ausfuehrer", "Admin");
        eintrag.put("erstellt-am", System.currentTimeMillis());
        eintrag.put("scope", "global");
        Map<String, Object> daten = new HashMap<>();
        UUID uuid = UUID.randomUUID();
        daten.put(uuid + "|global", eintrag);
        speicher.dateien.put("timeouts.yml", daten);
        TimeoutRepository repo = new TimeoutRepository(ordner, speicher, executor());
        repo.laden();
        assertTrue(repo.alleFuer(uuid).isEmpty());

        long jetzt = System.currentTimeMillis();
        assertTrue(repo.timeoutVerhaengenWennFrei(new TimeoutRecord(UUID.randomUUID(), "Alex",
                "Spam", "Admin", jetzt, jetzt + 60_000L, ServerScope.global())));
        assertTrue(io.warteAufIdle(5, TimeUnit.SECONDS));

        Map<String, Object> nachSave = speicher.dateien.get("timeouts.yml");
        String schluessel = uuid + "|global";
        assertTrue(nachSave.containsKey(schluessel),
                "Quarantänierter Rohsatz muss beim Speichern zurückgeschrieben werden");
        Object roh = nachSave.get(schluessel);
        assertInstanceOf(Map.class, roh);
        assertEquals("Steve", ((Map<?, ?>) roh).get("spieler-name"));
        assertFalse(((Map<?, ?>) roh).containsKey("ablauf-am"),
                "Die Quarantäne darf den Rohsatz nicht heilen oder ergänzen");

        TimeoutRepository neu = new TimeoutRepository(ordner, speicher, io);
        neu.laden();
        assertTrue(neu.alleFuer(uuid).isEmpty(), "Quarantänierter Satz bleibt inaktiv");
    }

    @Test
    void notizenSindAuf200Begrenzt(@TempDir File ordner) {
        MapSpeicher speicher = new MapSpeicher();
        NoteRepository repo = new NoteRepository(ordner, speicher, executor());
        UUID uuid = UUID.randomUUID();
        for (int i = 0; i < 250; i++) {
            repo.hinzufuegen(uuid, "Steve", "Notiz " + i, "Admin");
        }
        assertEquals(200, repo.anzahl(uuid), "Der Bestand muss exakt auf 200 begrenzt sein");
        List<NoteRecord> bestand = repo.finden(uuid);
        assertEquals(200, bestand.size());
        assertEquals(51, bestand.get(0).nummer(), "Die ältesten 50 Notizen werden verdrängt");
        assertEquals(250, bestand.get(bestand.size() - 1).nummer(), "Die neueste Notiz bleibt erhalten");

        assertTrue(io.warteAufIdle(5, TimeUnit.SECONDS));
        Map<String, Object> gespeichert = speicher.dateien.get(uuid + ".yml");
        assertNotNull(gespeichert, "Die Notizdatei muss geschrieben worden sein");
        @SuppressWarnings("unchecked")
        List<Object> persistiert = (List<Object>) gespeichert.get("notizen");
        assertEquals(200, persistiert.size(), "Auch auf der Platte bleiben genau 200 Notizen");
        assertEquals(51, ((Map<?, ?>) persistiert.get(0)).get("nummer"),
                "Die älteste verbliebene Notiz ist Nummer 51");
        assertEquals(250, ((Map<?, ?>) persistiert.get(persistiert.size() - 1)).get("nummer"),
                "Die neueste Notiz ist Nummer 250");
    }

    @Test
    void ipLaengenCapVerhindertRegexDoS() {
        String lang = "1".repeat(65) + ".1.1.1";
        assertThrows(IllegalArgumentException.class, () -> IpBanRecord.kanonisieren(lang));
    }

    @Test
    void warnHinzufuegenLiefertAtomareAnzahl(@TempDir File ordner) {
        MapSpeicher speicher = new MapSpeicher();
        WarnRepository repo = new WarnRepository(ordner, speicher, executor());
        UUID uuid = UUID.randomUUID();
        var erstes = repo.hinzufuegenMitAnzahl(uuid, "Steve", "Spam", "Admin");
        assertEquals(1, erstes.anzahl());
        var zweites = repo.hinzufuegenMitAnzahl(uuid, "Steve", "Spam", "Admin");
        assertEquals(2, zweites.anzahl());
    }

    @Test
    void reportUnbekannterStatusWirdQuarantaeniert(@TempDir File ordner) {
        MapSpeicher speicher = new MapSpeicher();
        Map<String, Object> eintrag = new HashMap<>();
        eintrag.put("id", 1L);
        eintrag.put("ziel-uuid", UUID.randomUUID().toString());
        eintrag.put("ziel-name", "Steve");
        eintrag.put("reporter-name", "Alex");
        eintrag.put("grund", "Hack");
        eintrag.put("server", "lobby");
        eintrag.put("erstellt-am", System.currentTimeMillis());
        eintrag.put("status", "ERLEDIGT_UNBEKANNT_XYZ");
        Map<String, Object> daten = new HashMap<>();
        daten.put("zaehler", 1L);
        daten.put("reports", List.of(eintrag));
        speicher.dateien.put("reports.yml", daten);
        ReportRepository repo = new ReportRepository(ordner, speicher, executor());
        repo.laden();
        assertTrue(repo.alle().isEmpty());
    }

    @Test
    void protokollV3HelloAckUndSyncRahmen() throws Exception {
        assertEquals(7, Protokoll.VERSION);
        byte[] ack = Protokoll.helloAck(Protokoll.VERSION);
        int version = Protokoll.leseHelloAck(Protokoll.paket(ack).nutzdaten());
        assertEquals(Protokoll.VERSION, version);
        long generation = 123456789L;
        byte[] begin = Protokoll.syncBegin(generation);
        byte[] end = Protokoll.syncEnd(generation);
        assertEquals(generation, Protokoll.leseSyncGeneration(Protokoll.paket(begin).nutzdaten()));
        assertEquals(generation, Protokoll.leseSyncGeneration(Protokoll.paket(end).nutzdaten()));
        assertEquals(Protokoll.HELLO_ACK, Protokoll.paket(ack).typ());
        assertEquals(Protokoll.SYNC_BEGIN, Protokoll.paket(begin).typ());
        assertEquals(Protokoll.SYNC_END, Protokoll.paket(end).typ());
    }

    @Test
    void ioExecutorZaehltFehler() {
        IoExecutor lokal = executor();
        lokal.ausfuehren(() -> {
            throw new RuntimeException("Testfehler");
        });
        assertTrue(lokal.warteAufIdle(5, TimeUnit.SECONDS));
        assertEquals(1, lokal.anzahlFehler());
        assertFalse(lokal.istGesund());
    }
}
