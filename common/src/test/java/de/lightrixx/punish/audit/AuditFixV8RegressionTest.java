package de.lightrixx.punish.audit;

import de.lightrixx.punish.model.BanRecord;
import de.lightrixx.punish.model.HistoryEntry;
import de.lightrixx.punish.model.HistoryType;
import de.lightrixx.punish.storage.BanRepository;
import de.lightrixx.punish.storage.HistoryRepository;
import de.lightrixx.punish.storage.IoExecutor;
import de.lightrixx.punish.storage.IpBanRepository;
import de.lightrixx.punish.storage.VerstossRepository;
import de.lightrixx.punish.storage.WarnRepository;
import de.lightrixx.punish.storage.YamlSpeicher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AuditFixV8RegressionTest {

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

    private IoExecutor executor() {
        io = new IoExecutor();
        return io;
    }

    static class MapSpeicher implements YamlSpeicher {
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

    static final class DateiMapSpeicher extends MapSpeicher {
        @Override
        public void speichern(Map<String, Object> daten, File datei) {
            super.speichern(daten, datei);
            try {
                File eltern = datei.getParentFile();
                if (eltern != null) {
                    Files.createDirectories(eltern.toPath());
                }
                StringBuilder text = new StringBuilder();
                for (Map.Entry<String, Object> eintrag : daten.entrySet()) {
                    text.append(eintrag.getKey()).append('=')
                            .append(String.valueOf(eintrag.getValue())).append('\n');
                }
                Files.writeString(datei.toPath(), text.toString(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new IllegalStateException("Testspeicher konnte nicht schreiben.", e);
            }
        }
    }

    private static Map<String, Object> banEintrag(Object ablaufWert, boolean mitAblauf) {
        Map<String, Object> eintrag = new HashMap<>();
        eintrag.put("spieler-name", "Steve");
        eintrag.put("grund", "Hacking");
        eintrag.put("ausfuehrer", "Admin");
        eintrag.put("erstellt-am", System.currentTimeMillis());
        eintrag.put("auto-bann", false);
        eintrag.put("scope", "global");
        if (mitAblauf) {
            eintrag.put("ablauf-am", ablaufWert);
        }
        return eintrag;
    }

    private static Map<String, Object> ipBanEintrag(Object ablaufWert, boolean mitAblauf) {
        Map<String, Object> eintrag = new HashMap<>();
        eintrag.put("ip", "203.0.113.7");
        eintrag.put("grund", "Hacking");
        eintrag.put("ausfuehrer", "Admin");
        eintrag.put("erstellt-am", System.currentTimeMillis());
        eintrag.put("scope", "global");
        if (mitAblauf) {
            eintrag.put("ablauf-am", ablaufWert);
        }
        return eintrag;
    }

    private static HistoryEntry historyEintrag(long id) {
        long jetzt = System.currentTimeMillis();
        return new HistoryEntry(id, HistoryType.BAN, "Admin", "Ziel", "Grund " + id,
                jetzt, jetzt + 60_000L, true);
    }

    @Test
    void banKorruptesAblaufAmWirdQuarantaeniertStattPermanent(@TempDir File ordner) {
        MapSpeicher speicher = new MapSpeicher();
        UUID uuid = UUID.randomUUID();
        String schluessel = uuid + "|global";
        Map<String, Object> daten = new HashMap<>();
        daten.put(schluessel, banEintrag("abgebrochen", true));
        speicher.dateien.put("bans.yml", daten);

        BanRepository repo = new BanRepository(ordner, speicher, executor());
        repo.laden();
        assertTrue(repo.alle().isEmpty(),
                "Ein korruptes ablauf-am darf nicht still in einen Permanent-Bann umgedeutet werden.");

        UUID zweite = UUID.randomUUID();
        assertTrue(repo.bannenWennFrei(new BanRecord(zweite, "Alex", "Test", "Admin",
                System.currentTimeMillis(), null, false)));
        assertTrue(io.warteAufIdle(5, TimeUnit.SECONDS));
        Map<String, Object> geschrieben = speicher.dateien.get("bans.yml");
        assertNotNull(geschrieben);
        Object roh = geschrieben.get(schluessel);
        assertInstanceOf(Map.class, roh, "Quarantäne-Eintrag muss unverändert zurückgeschrieben werden.");
        assertEquals("abgebrochen", ((Map<?, ?>) roh).get("ablauf-am"));
    }

    @Test
    void banOhneAblaufBleibtPermanent(@TempDir File ordner) {
        MapSpeicher speicher = new MapSpeicher();
        UUID mitNull = UUID.randomUUID();
        UUID ohneFeld = UUID.randomUUID();
        Map<String, Object> daten = new HashMap<>();
        daten.put(mitNull + "|global", banEintrag(null, true));
        daten.put(ohneFeld + "|global", banEintrag(null, false));
        speicher.dateien.put("bans.yml", daten);

        BanRepository repo = new BanRepository(ordner, speicher, executor());
        repo.laden();
        assertEquals(2, repo.alle().size(),
                "Fehlendes oder null-wertiges ablauf-am bedeutet weiterhin Permanent-Bann.");
        assertNull(repo.alleFuer(mitNull).get(0).ablaufAm());
        assertNull(repo.alleFuer(ohneFeld).get(0).ablaufAm());
    }

    @Test
    void banNumerischerStringAlsAblaufBleibtGueltig(@TempDir File ordner) {
        MapSpeicher speicher = new MapSpeicher();
        UUID uuid = UUID.randomUUID();
        Map<String, Object> daten = new HashMap<>();
        daten.put(uuid + "|global", banEintrag("4102444800000", true));
        speicher.dateien.put("bans.yml", daten);

        BanRepository repo = new BanRepository(ordner, speicher, executor());
        repo.laden();
        List<BanRecord> banns = repo.alleFuer(uuid);
        assertEquals(1, banns.size());
        assertEquals(4102444800000L, banns.get(0).ablaufAm());
    }

    @Test
    void ipBanKorruptesAblaufAmWirdQuarantaeniertStattPermanent(@TempDir File ordner) {
        MapSpeicher speicher = new MapSpeicher();
        Map<String, Object> daten = new HashMap<>();
        daten.put("ip-banns", List.of(ipBanEintrag(Boolean.TRUE, true)));
        speicher.dateien.put("ipbans.yml", daten);

        IpBanRepository repo = new IpBanRepository(ordner, speicher, executor());
        repo.laden();
        assertTrue(repo.alle().isEmpty(),
                "Ein korruptes ablauf-am darf keinen stillen Permanent-IP-Bann erzeugen.");
    }

    @Test
    void ipBanNumerischerStringAlsAblaufBleibtGueltig(@TempDir File ordner) {
        MapSpeicher speicher = new MapSpeicher();
        Map<String, Object> daten = new HashMap<>();
        daten.put("ip-banns", List.of(ipBanEintrag("4102444800000", true)));
        speicher.dateien.put("ipbans.yml", daten);

        IpBanRepository repo = new IpBanRepository(ordner, speicher, executor());
        repo.laden();
        assertEquals(1, repo.alle().size());
        assertEquals(4102444800000L, repo.alle().iterator().next().ablaufAm());
    }

    @Test
    void historyWirdErstNachBestaetigterArchivierungGekuerzt(@TempDir File ordner) throws Exception {
        DateiMapSpeicher speicher = new DateiMapSpeicher();
        HistoryRepository repo = new HistoryRepository(ordner, speicher, executor());
        UUID ziel = UUID.randomUUID();
        for (int i = 1; i <= 1001; i++) {
            repo.hinzufuegen(historyEintrag(i), ziel);
        }
        assertEquals(1000, repo.finden(ziel, 5000).size(),
                "Nach bestätigter Archivierung bleiben genau 1000 Einträge live.");
        File[] archiv = new File(new File(ordner, "history"), "archive")
                .listFiles((verzeichnis, name) -> name != null && name.endsWith(".yml"));
        assertNotNull(archiv);
        assertEquals(1, archiv.length, "Der ausgelagerte Eintrag muss als Archivdatei vorliegen.");

        String inhalt = Files.readString(archiv[0].toPath(), StandardCharsets.UTF_8);
        assertTrue(inhalt.contains("Grund 1"),
                "Archivdatei muss den ältesten Eintrag (ID 1) enthalten, war: " + inhalt);
        assertFalse(inhalt.contains("Grund 2"),
                "Archivdatei darf nur den ausgelagerten Eintrag enthalten, war: " + inhalt);
        assertTrue(repo.finden(ziel, 5000).stream()
                        .noneMatch(eintrag -> eintrag != null && eintrag.id() == 1L),
                "Der archivierte Eintrag darf nicht mehr im Live-Bestand liegen.");
        assertTrue(repo.finden(ziel, 5000).stream()
                        .anyMatch(eintrag -> eintrag != null && eintrag.id() == 1001L),
                "Der neueste Eintrag muss im Live-Bestand erhalten bleiben.");
    }

    @Test
    void historyArchivierungsfehlerVerhindertDatenverlust(@TempDir File ordner) {
        MapSpeicher speicher = new MapSpeicher();
        HistoryRepository repo = new HistoryRepository(ordner, speicher, executor());
        UUID ziel = UUID.randomUUID();
        for (int i = 1; i <= 1001; i++) {
            repo.hinzufuegen(historyEintrag(i), ziel);
        }
        assertEquals(1001, repo.finden(ziel, 5000).size(),
                "Ohne bestätigte Archivdatei dürfen keine Einträge gekürzt werden.");
    }

    @Test
    void warnNummerUeberIntBereichWirdQuarantaeniert(@TempDir File ordner) throws Exception {
        MapSpeicher speicher = new MapSpeicher();
        UUID uuid = UUID.randomUUID();
        IoExecutor executor = executor();
        WarnRepository erst = new WarnRepository(ordner, speicher, executor);
        erst.hinzufuegenMitAnzahl(uuid, "Steve", "Spam", "Admin");
        assertTrue(executor.warteAufIdle(5, TimeUnit.SECONDS));
        @SuppressWarnings("unchecked")
        Map<String, Object> eintrag = (Map<String, Object>)
                ((List<Object>) speicher.dateien.get(uuid + ".yml").get("warns")).get(0);
        eintrag.put("nummer", 4294967301L);
        File unterordner = new File(ordner, "warns");
        assertTrue(unterordner.mkdirs() || unterordner.isDirectory());
        Files.writeString(new File(unterordner, uuid + ".yml").toPath(), "# Testdatei",
                StandardCharsets.UTF_8);

        WarnRepository zweit = new WarnRepository(ordner, speicher, executor);
        zweit.laden();
        assertEquals(0, zweit.anzahl(uuid),
                "Eine Warn-Nummer außerhalb des int-Bereichs darf nicht abgeschnitten geladen werden.");
    }

    @Test
    void verstossNummerUeberIntBereichWirdQuarantaeniert(@TempDir File ordner) throws Exception {
        MapSpeicher speicher = new MapSpeicher();
        UUID uuid = UUID.randomUUID();
        IoExecutor executor = executor();
        VerstossRepository erst = new VerstossRepository(ordner, speicher, executor);
        erst.hinzufuegen(uuid, "Steve", "Hack", "Admin");
        assertTrue(executor.warteAufIdle(5, TimeUnit.SECONDS));
        @SuppressWarnings("unchecked")
        Map<String, Object> eintrag = (Map<String, Object>)
                ((List<Object>) speicher.dateien.get(uuid + ".yml").get("verstoesse")).get(0);
        eintrag.put("nummer", 4294967301L);
        File unterordner = new File(ordner, "verstoesse");
        assertTrue(unterordner.mkdirs() || unterordner.isDirectory());
        Files.writeString(new File(unterordner, uuid + ".yml").toPath(), "# Testdatei",
                StandardCharsets.UTF_8);

        VerstossRepository zweit = new VerstossRepository(ordner, speicher, executor);
        zweit.laden();
        assertEquals(0, zweit.anzahl(uuid),
                "Eine Verstoß-Nummer außerhalb des int-Bereichs darf nicht abgeschnitten geladen werden.");
    }
}
