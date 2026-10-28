package de.lightrixx.punish.storage;

import de.lightrixx.punish.model.BanRecord;
import de.lightrixx.punish.model.FreezeRecord;
import de.lightrixx.punish.model.IpMuteRecord;
import de.lightrixx.punish.model.MuteRecord;
import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.model.TimeoutRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuarantaeneUebernahmeTest {

    static final class FakeSpeicher implements YamlSpeicher {
        final Map<String, Map<String, Object>> dateien = new ConcurrentHashMap<>();

        @Override
        public Map<String, Object> laden(File datei) {
            Map<String, Object> daten = dateien.get(datei.getAbsolutePath());
            return daten == null ? Map.of() : daten;
        }

        @Override
        public void speichern(Map<String, Object> daten, File datei) {
            dateien.put(datei.getAbsolutePath(), daten);
        }
    }

    @TempDir
    File temp;

    private IoExecutor ioExecutor;
    private FakeSpeicher speicher;

    @BeforeEach
    void setup() {
        ioExecutor = new IoExecutor();
        speicher = new FakeSpeicher();
    }

    @AfterEach
    void teardown() {
        ioExecutor.herunterfahren(10, TimeUnit.SECONDS);
    }

    private static Map<String, Object> spielerEintrag(String name, String grund, long erstelltAm, long ablaufAm) {
        Map<String, Object> abschnitt = new LinkedHashMap<>();
        abschnitt.put("spieler-name", name);
        abschnitt.put("grund", grund);
        abschnitt.put("ausfuehrer", "Konsole");
        abschnitt.put("erstellt-am", erstelltAm);
        if (ablaufAm > 0) {
            abschnitt.put("ablauf-am", ablaufAm);
        }
        abschnitt.put("scope", "global");
        return abschnitt;
    }

    private static Map<String, Object> ipEintrag(String ip, String grund, long erstelltAm, long ablaufAm) {
        Map<String, Object> eintrag = new LinkedHashMap<>();
        eintrag.put("ip", ip);
        eintrag.put("grund", grund);
        eintrag.put("ausfuehrer", "Konsole");
        eintrag.put("erstellt-am", erstelltAm);
        eintrag.put("ablauf-am", ablaufAm);
        eintrag.put("scope", "global");
        return eintrag;
    }

    private static void sammleGruende(Object wert, List<String> gruende) {
        if (wert instanceof Map<?, ?> karte) {
            Object grund = karte.get("grund");
            if (grund instanceof String text) {
                gruende.add(text);
            }
        }
    }

    @Test
    @DisplayName("F-011: überschriebener Bann (Alias-Schlüssel) überlebt den nächsten Save in Quarantäne")
    void bannAliasSchluesselUeberlebtSave() {
        BanRepository repository = new BanRepository(temp, speicher, ioExecutor);
        UUID uuid = UUID.randomUUID();
        Map<String, Object> daten = new LinkedHashMap<>();
        daten.put(uuid + "|global", spielerEintrag("Steve", "Alt", 1000L, 0L));
        daten.put(uuid.toString(), spielerEintrag("Steve", "Neu", 2000L, 0L));
        speicher.dateien.put(new File(temp, "bans.yml").getAbsolutePath(), daten);

        repository.laden();
        assertEquals(1, repository.alle().size());
        assertEquals("Neu", repository.alle().iterator().next().grund());

        assertTrue(repository.bannenWennFrei(new BanRecord(UUID.randomUUID(), "Alex", "Hack", "Mod",
                System.currentTimeMillis(), null, false, ServerScope.global())));
        assertTrue(ioExecutor.warteAufIdle(5, TimeUnit.SECONDS));

        Map<String, Object> gespeichert = speicher.dateien.get(new File(temp, "bans.yml").getAbsolutePath());
        assertNotNull(gespeichert);
        List<String> gruende = new ArrayList<>();
        gespeichert.values().forEach(wert -> sammleGruende(wert, gruende));
        assertTrue(gruende.contains("Neu"), "Der gültige Eintrag muss aktiv bleiben.");
        assertTrue(gruende.contains("Alt"), "Der überschriebene Alias-Eintrag darf nicht verloren gehen.");
    }

    @Test
    @DisplayName("F-011: überschriebener Mute überlebt den nächsten Save in Quarantäne")
    void muteDoppelterSchluesselUeberlebtSave() {
        MuteRepository repository = new MuteRepository(temp, speicher, ioExecutor);
        UUID uuid = UUID.randomUUID();
        long ablauf = System.currentTimeMillis() + 600_000L;
        Map<String, Object> daten = new LinkedHashMap<>();
        daten.put(uuid.toString(), spielerEintrag("Steve", "Alt", 1000L, ablauf));
        daten.put(uuid + "|global", spielerEintrag("Steve", "Neu", 2000L, ablauf));
        speicher.dateien.put(new File(temp, "mutes.yml").getAbsolutePath(), daten);

        repository.laden();
        assertEquals(1, repository.alle().size());
        assertEquals("Neu", repository.alle().iterator().next().grund());

        assertTrue(repository.mutenWennFrei(new MuteRecord(UUID.randomUUID(), "Alex", "Spam", "Mod",
                System.currentTimeMillis(), ablauf, ServerScope.global())));
        assertTrue(ioExecutor.warteAufIdle(5, TimeUnit.SECONDS));

        Map<String, Object> gespeichert = speicher.dateien.get(new File(temp, "mutes.yml").getAbsolutePath());
        assertNotNull(gespeichert);
        List<String> gruende = new ArrayList<>();
        gespeichert.values().forEach(wert -> sammleGruende(wert, gruende));
        assertTrue(gruende.contains("Neu"));
        assertTrue(gruende.contains("Alt"), "Der überschriebene Mute-Eintrag darf nicht verloren gehen.");
    }

    @Test
    @DisplayName("F-011: überschriebener Timeout überlebt den nächsten Save in Quarantäne")
    void timeoutDoppelterSchluesselUeberlebtSave() {
        TimeoutRepository repository = new TimeoutRepository(temp, speicher, ioExecutor);
        UUID uuid = UUID.randomUUID();
        long ablauf = System.currentTimeMillis() + 600_000L;
        Map<String, Object> daten = new LinkedHashMap<>();
        daten.put(uuid.toString(), spielerEintrag("Steve", "Alt", 1000L, ablauf));
        daten.put(uuid + "|global", spielerEintrag("Steve", "Neu", 2000L, ablauf));
        speicher.dateien.put(new File(temp, "timeouts.yml").getAbsolutePath(), daten);

        repository.laden();
        assertEquals(1, repository.alle().size());
        assertEquals("Neu", repository.alle().iterator().next().grund());

        assertTrue(repository.timeoutVerhaengenWennFrei(new TimeoutRecord(UUID.randomUUID(), "Alex", "Afk", "Mod",
                System.currentTimeMillis(), ablauf, ServerScope.global())));
        assertTrue(ioExecutor.warteAufIdle(5, TimeUnit.SECONDS));

        Map<String, Object> gespeichert = speicher.dateien.get(new File(temp, "timeouts.yml").getAbsolutePath());
        assertNotNull(gespeichert);
        List<String> gruende = new ArrayList<>();
        gespeichert.values().forEach(wert -> sammleGruende(wert, gruende));
        assertTrue(gruende.contains("Neu"));
        assertTrue(gruende.contains("Alt"), "Der überschriebene Timeout-Eintrag darf nicht verloren gehen.");
    }

    @Test
    @DisplayName("F-011: überschriebener Freeze überlebt den nächsten Save in Quarantäne")
    void freezeDoppelterSchluesselUeberlebtSave() {
        FreezeRepository repository = new FreezeRepository(temp, speicher, ioExecutor);
        UUID uuid = UUID.randomUUID();
        long ablauf = System.currentTimeMillis() + 600_000L;
        Map<String, Object> daten = new LinkedHashMap<>();
        daten.put(uuid.toString(), spielerEintrag("Steve", "Alt", 1000L, ablauf));
        daten.put(uuid + "|global", spielerEintrag("Steve", "Neu", 2000L, ablauf));
        speicher.dateien.put(new File(temp, "freezes.yml").getAbsolutePath(), daten);

        repository.laden();
        assertEquals(1, repository.alle().size());
        assertEquals("Neu", repository.alle().iterator().next().grund());

        assertTrue(repository.freezenWennFrei(new FreezeRecord(UUID.randomUUID(), "Alex", "Prüfung", "Mod",
                System.currentTimeMillis(), ablauf, ServerScope.global())));
        assertTrue(ioExecutor.warteAufIdle(5, TimeUnit.SECONDS));

        Map<String, Object> gespeichert = speicher.dateien.get(new File(temp, "freezes.yml").getAbsolutePath());
        assertNotNull(gespeichert);
        List<String> gruende = new ArrayList<>();
        gespeichert.values().forEach(wert -> sammleGruende(wert, gruende));
        assertTrue(gruende.contains("Neu"));
        assertTrue(gruende.contains("Alt"), "Der überschriebene Freeze-Eintrag darf nicht verloren gehen.");
    }

    @Test
    @DisplayName("F-011: überschriebener IP-Mute überlebt den nächsten Save in Quarantäne")
    void ipMuteDoppelterSchluesselUeberlebtSave() {
        IpMuteRepository repository = new IpMuteRepository(temp, speicher, ioExecutor);
        long ablauf = System.currentTimeMillis() + 600_000L;
        Map<String, Object> daten = new LinkedHashMap<>();
        List<Object> liste = new ArrayList<>();
        liste.add(ipEintrag("1.2.3.4", "Alt", 1000L, ablauf));
        liste.add(ipEintrag("1.2.3.4", "Neu", 2000L, ablauf));
        daten.put("ip-mutes", liste);
        speicher.dateien.put(new File(temp, "ipmutes.yml").getAbsolutePath(), daten);

        repository.laden();
        assertEquals(1, repository.alle().size());
        assertEquals("Neu", repository.alle().iterator().next().grund());

        assertTrue(repository.mutenWennFrei(new IpMuteRecord("5.6.7.8", "Spam", "Mod",
                System.currentTimeMillis(), ablauf, ServerScope.global())));
        assertTrue(ioExecutor.warteAufIdle(5, TimeUnit.SECONDS));

        Map<String, Object> gespeichert = speicher.dateien.get(new File(temp, "ipmutes.yml").getAbsolutePath());
        assertNotNull(gespeichert);
        List<String> gruende = new ArrayList<>();
        sammleListe(gespeichert.get("ip-mutes"), gruende);
        assertTrue(gruende.contains("Neu"));
        assertTrue(gruende.contains("Alt"), "Der überschriebene IP-Mute-Eintrag darf nicht verloren gehen.");
    }

    private static void sammleListe(Object wert, List<String> gruende) {
        if (wert instanceof List<?> liste) {
            liste.forEach(eintrag -> sammleGruende(eintrag, gruende));
        }
    }

    @Test
    @DisplayName("F-014: Warn-Datei mit falschem Abschnittstyp behält den Speicherstand (Fail-Closed)")
    void warnDateiMitFalschemAbschnittTypBehaeltBestand() throws Exception {
        WarnRepository repository = new WarnRepository(temp, speicher, ioExecutor);
        UUID uuid = UUID.randomUUID();
        File ordner = new File(temp, "warns");
        assertTrue(ordner.mkdirs() || ordner.isDirectory());
        File datei = new File(ordner, uuid + ".yml");
        assertTrue(datei.createNewFile());

        Map<String, Object> eintrag = new LinkedHashMap<>();
        eintrag.put("nummer", 1L);
        eintrag.put("spieler-name", "Steve");
        eintrag.put("grund", "Regel 1");
        eintrag.put("ausfuehrer", "Mod");
        eintrag.put("erstellt-am", 1000L);
        Map<String, Object> daten = new LinkedHashMap<>();
        daten.put("warns", new ArrayList<>(List.of(eintrag)));
        speicher.dateien.put(datei.getAbsolutePath(), daten);

        repository.laden();
        assertEquals(1, repository.finden(uuid).size());

        speicher.dateien.put(datei.getAbsolutePath(), Map.of("warns", "kaputt"));
        repository.laden();

        assertEquals(1, repository.finden(uuid).size(),
                "Ein unbrauchbarer Abschnitt darf den geladenen Bestand nicht ersetzen.");
        assertEquals("Regel 1", repository.finden(uuid).get(0).grund());
    }

    @Test
    @DisplayName("F-014: Report-Datei mit falschem Abschnittstyp behält den Speicherstand (Fail-Closed)")
    void reportDateiMitFalschemAbschnittTypBehaeltBestand() {
        ReportRepository repository = new ReportRepository(temp, speicher, ioExecutor);
        repository.hinzufuegen(UUID.randomUUID(), "Steve", UUID.randomUUID(), "Alex", "Hacking", "lobby");
        assertTrue(ioExecutor.warteAufIdle(5, TimeUnit.SECONDS));
        assertEquals(1, repository.alle().size());

        Map<String, Object> gespeichert = speicher.dateien.values().iterator().next();
        gespeichert.put("reports", "kaputt");

        repository.laden();

        assertEquals(1, repository.alle().size(),
                "Ein unbrauchbarer Abschnitt darf die geladenen Reports nicht ersetzen.");
        assertEquals("Hacking", repository.alle().iterator().next().grund());
    }
}
