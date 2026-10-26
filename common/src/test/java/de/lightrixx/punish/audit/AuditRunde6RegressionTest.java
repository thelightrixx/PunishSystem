package de.lightrixx.punish.audit;

import de.lightrixx.punish.model.IpBanRecord;
import de.lightrixx.punish.model.IpTimeoutRecord;
import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.storage.IoExecutor;
import de.lightrixx.punish.storage.IpBanRepository;
import de.lightrixx.punish.storage.IpTimeoutRepository;
import de.lightrixx.punish.storage.YamlSpeicher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditRunde6RegressionTest {

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

    @TempDir
    File datenOrdner;

    private IoExecutor io;
    private MapSpeicher speicher;

    @BeforeEach
    void aufsetzen() {
        io = new IoExecutor();
        speicher = new MapSpeicher();
    }

    @AfterEach
    void abbauen() {
        io.herunterfahren(5, TimeUnit.SECONDS);
    }

    @Test
    void doppelterIpBannWirdQuarantaenisiertStattVerworfen() {
        long jetzt = System.currentTimeMillis();
        Map<String, Object> erster = eintrag("192.0.2.10", "Erster Grund", jetzt, jetzt + 3_600_000L);
        Map<String, Object> zweiter = eintrag("192.0.2.10", "Zweiter Grund", jetzt, jetzt + 7_200_000L);
        speicher.dateien.put("ipbans.yml", datei("ip-banns", List.of(erster, zweiter)));

        IpBanRepository repository = new IpBanRepository(datenOrdner, speicher, io);
        repository.laden();

        assertEquals(1, repository.alle().size(), "Nur der letzte Eintrag bleibt aktiv.");
        assertEquals("Zweiter Grund", repository.alle().iterator().next().grund());

        assertTrue(repository.bannenWennFrei(new IpBanRecord("192.0.2.99", "Neu", "Mod",
                jetzt, jetzt + 600_000L, ServerScope.global())));
        assertTrue(io.warteAufIdle(5, TimeUnit.SECONDS));

        List<Object> gespeichert = liste(speicher.dateien.get("ipbans.yml"));
        assertEquals(3, gespeichert.size(),
                "Überschriebener Eintrag muss in der Quarantäne erhalten bleiben: " + gespeichert);
    }

    @Test
    void abgelaufenerIpTimeoutOhneErstelltAmWirdVerworfen() {
        long jetzt = System.currentTimeMillis();
        Map<String, Object> abgelaufen = eintrag("192.0.2.11", "Alt", 0L, jetzt - 1_000L);
        abgelaufen.remove("erstellt-am");
        Map<String, Object> aktiv = eintrag("192.0.2.12", "Neu", jetzt, jetzt + 3_600_000L);
        speicher.dateien.put("iptimeouts.yml", datei("ip-timeouts", List.of(abgelaufen, aktiv)));

        IpTimeoutRepository repository = new IpTimeoutRepository(datenOrdner, speicher, io);
        repository.laden();

        assertEquals(1, repository.alle().size(), "Abgelaufener Eintrag ohne Startzeit wird verworfen.");
        assertTrue(repository.finden("192.0.2.11", ServerScope.global()).isEmpty(),
                "Abgelaufener Eintrag darf nicht in der Quarantäne landen.");

        assertTrue(repository.timeoutVerhaengenWennFrei(new IpTimeoutRecord("192.0.2.98", "Neu", "Mod",
                jetzt, jetzt + 600_000L, ServerScope.global())));
        assertTrue(io.warteAufIdle(5, TimeUnit.SECONDS));

        assertEquals(2, liste(speicher.dateien.get("iptimeouts.yml")).size(),
                "Verworfener Eintrag darf beim Speichern nicht zurückkehren.");
    }

    private static Map<String, Object> eintrag(String ip, String grund, long erstelltAm, long ablaufAm) {
        Map<String, Object> eintrag = new LinkedHashMap<>();
        eintrag.put("ip", ip);
        eintrag.put("grund", grund);
        eintrag.put("ausfuehrer", "Mod");
        if (erstelltAm > 0L) {
            eintrag.put("erstellt-am", erstelltAm);
        }
        eintrag.put("ablauf-am", ablaufAm);
        eintrag.put("scope", "global");
        return eintrag;
    }

    private static Map<String, Object> datei(String schluessel, List<Map<String, Object>> eintraege) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put(schluessel, new ArrayList<>(eintraege));
        return root;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> liste(Map<String, Object> daten) {
        return (List<Object>) daten.get(daten.keySet().iterator().next());
    }
}
