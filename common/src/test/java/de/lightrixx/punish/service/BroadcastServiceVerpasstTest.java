package de.lightrixx.punish.service;

import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.GeplanteAufgabe;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.OnlineSpieler;
import de.lightrixx.punish.core.Permissions;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.storage.IoExecutor;
import de.lightrixx.punish.storage.VerpassteBroadcastsRepository;
import de.lightrixx.punish.storage.YamlSpeicher;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class BroadcastServiceVerpasstTest {

    static final class FakeSpeicher implements YamlSpeicher {
        final Map<String, Map<String, Object>> dateien = new ConcurrentHashMap<>();

        @Override
        public Map<String, Object> laden(File datei) {
            return dateien.getOrDefault(datei.getAbsolutePath(), Map.of());
        }

        @Override
        public void speichern(Map<String, Object> daten, File datei) {
            dateien.put(datei.getAbsolutePath(), daten);
        }
    }

    static final class StubConfig implements Konfiguration {
        @Override
        public String praefix() {
            return "<gray>[Punish]";
        }

        @Override
        public String nachricht(String schluessel) {
            return schluessel;
        }

        @Override
        public int verstoesseMaxPunkte() {
            return 6;
        }

        @Override
        public String verstoesseAutoBannDauer() {
            return "7d";
        }

        @Override
        public de.lightrixx.punish.model.VerstossStufe verstossStandardStufe() {
            return de.lightrixx.punish.model.VerstossStufe.standard();
        }

        @Override
        public long verstossStufenDauerMillis(de.lightrixx.punish.model.VerstossStufe stufe) {
            return 1000L;
        }

        @Override
        public int verstossStufenPunkte(de.lightrixx.punish.model.VerstossStufe stufe) {
            return 1;
        }

        @Override
        public LimitSnapshot limitSnapshot() {
            return new LimitSnapshot(6, "7d");
        }

        @Override
        public long timeoutMaxMinuten() {
            return 1440L;
        }

        @Override
        public long muteMaxMinuten() {
            return 43200L;
        }

        @Override
        public long banMaxMinuten() {
            return 525600L;
        }

        @Override
        public boolean bestaetigungPardonAll() {
            return true;
        }

        @Override
        public boolean bestaetigungKickAll() {
            return true;
        }

        @Override
        public boolean bestaetigungBanAll() {
            return true;
        }

        @Override
        public boolean schutzFailClosed() {
            return true;
        }

        @Override
        public boolean offlineAllowNeverSeen() {
            return true;
        }

        @Override
        public ZoneId anzeigeZone() {
            return ZoneId.of("Europe/Berlin");
        }

        @Override
        public int historyEintraegeProSeite() {
            return 10;
        }

        @Override
        public long reportCooldownSekunden() {
            return 60L;
        }

        @Override
        public int reportMaxOffenProReporter() {
            return 3;
        }

        @Override
        public long reportDuplikatSperreMinuten() {
            return 10L;
        }

        @Override
        public List<String> tabDauerListe() {
            return List.of();
        }

        @Override
        public List<String> tabGrundListe() {
            return List.of();
        }

        @Override
        public List<String> muteBlockierteBefehle() {
            return List.of();
        }

        @Override
        public String standardGrund(String aktion) {
            return "Regelverstoß";
        }

        @Override
        public String screen(String typ) {
            return typ;
        }

        @Override
        public boolean broadcastAktiv(String typ) {
            return true;
        }

        @Override
        public String broadcastNachricht(String typ) {
            return "<red>Live <white>%spieler%";
        }

        @Override
        public String serverName() {
            return "unbekannt";
        }

        @Override
        public List<String> bekannteServer() {
            return List.of();
        }

        @Override
        public de.lightrixx.punish.model.ServerScope standardScope() {
            return de.lightrixx.punish.model.ServerScope.unbekannt();
        }
    }

    static final class StubVerzeichnis implements SpielerVerzeichnis {
        final UUID teamUuid = UUID.randomUUID();
        boolean recht = true;
        final List<Component> gesendet = new ArrayList<>();
        final List<UUID> empfaenger = new ArrayList<>();

        private OnlineSpieler eintrag() {
            return new OnlineSpieler(teamUuid, "Team", "127.0.0.1", false);
        }

        @Override
        public List<OnlineSpieler> onlineSpieler() {
            return List.of(eintrag());
        }

        @Override
        public Optional<OnlineSpieler> onlineSpieler(UUID uuid) {
            return teamUuid.equals(uuid) ? Optional.of(eintrag()) : Optional.empty();
        }

        @Override
        public Optional<OnlineSpieler> onlineSpielerNachName(String name) {
            return Optional.empty();
        }

        @Override
        public void trennen(UUID uuid, Component grund) {
        }

        @Override
        public void mitteilen(UUID uuid, Component nachricht) {
            gesendet.add(nachricht);
            empfaenger.add(uuid);
        }

        @Override
        public boolean hatRecht(UUID uuid, String knoten) {
            return recht && teamUuid.equals(uuid)
                    && Permissions.BROADCAST_RECEIVE_ALL.node().equals(knoten);
        }

        @Override
        public boolean darfDirektAusfuehren() {
            return true;
        }

        @Override
        public boolean istBereit() {
            return true;
        }
    }

    static final class StubPlaner implements AuftragsPlaner {
        @Override
        public GeplanteAufgabe aufgabe(Runnable auftrag) {
            throw new UnsupportedOperationException("Nicht im Test verwendet.");
        }

        @Override
        public GeplanteAufgabe aufgabeAsync(Runnable auftrag) {
            throw new UnsupportedOperationException("Nicht im Test verwendet.");
        }

        @Override
        public GeplanteAufgabe spaeter(Runnable auftrag, long verzoegerungTicks) {
            throw new UnsupportedOperationException("Nicht im Test verwendet.");
        }

        @Override
        public GeplanteAufgabe spaeterAsync(Runnable auftrag, long verzoegerungTicks) {
            throw new UnsupportedOperationException("Nicht im Test verwendet.");
        }

        @Override
        public GeplanteAufgabe intervallAsync(Runnable auftrag, long startTicks, long periodeTicks) {
            throw new UnsupportedOperationException("Nicht im Test verwendet.");
        }

        @Override
        public void alleAbbrechen() {
        }

        @Override
        public boolean istGeschlossen() {
            return false;
        }
    }

    @TempDir
    File temp;

    private IoExecutor ioExecutor;
    private StubVerzeichnis verzeichnis;
    private VerpassteBroadcastsRepository ablage;
    private BroadcastService service;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    @BeforeEach
    void setup() {
        ioExecutor = new IoExecutor();
        verzeichnis = new StubVerzeichnis();
        ablage = new VerpassteBroadcastsRepository(temp, new FakeSpeicher(), ioExecutor);
        service = new BroadcastService(new StubConfig(), () -> true, new StubPlaner(), verzeichnis);
        service.mitVerpasstAblage(ablage);
    }

    @AfterEach
    void teardown() {
        ioExecutor.herunterfahren(5, TimeUnit.SECONDS);
    }

    @Test
    void sendenLegtVerpassteAblageAnUndStelltLiveZu() {
        service.senden("ban", Map.of("spieler", "Griefer"));
        assertEquals(1, ablage.anzahl());
        assertEquals(3, verzeichnis.gesendet.size());
    }

    @Test
    void silentWirdWederLiveNochAbgelegt() {
        service.senden("ban", Map.of("spieler", "Griefer"), true);
        assertEquals(0, ablage.anzahl());
        assertTrue(verzeichnis.gesendet.isEmpty());
    }

    @Test
    void nachsendungEnthaeltKopfUndMarkierung() {
        ablage.ablegen("ban", "<red>Griefer <gray>wurde gebannt");
        ablage.ablegen("mute", "<yellow>Spammer <gray>wurde gemutet");
        service.liefereVerpassteNach(verzeichnis.teamUuid, ablage.findeNach(0L, 100));

        List<String> inhalt = verzeichnis.gesendet.stream()
                .map(miniMessage::serialize)
                .filter(text -> !text.isBlank())
                .toList();
        assertEquals(3, inhalt.size(), "Kopf und beide Nachrichten, nichts doppelt: " + inhalt);
        String kopf = inhalt.get(0);
        assertTrue(kopf.contains("Verpasst") && kopf.contains("2")
                        && kopf.contains("verpasste Team-Nachrichten"), "Kopf: " + kopf);
        assertTrue(inhalt.get(1).contains("Verpasst") && inhalt.get(1).contains("Griefer"),
                "Erste Markierung: " + inhalt.get(1));
        assertTrue(inhalt.get(2).contains("Verpasst") && inhalt.get(2).contains("Spammer"),
                "Zweite Markierung: " + inhalt.get(2));
        assertEquals(verzeichnis.gesendet.size(), verzeichnis.empfaenger.size(),
                "Jede Nachricht muss genau einen Empfänger haben.");
        assertTrue(verzeichnis.empfaenger.stream().allMatch(verzeichnis.teamUuid::equals),
                "Nachsendung darf nur an das Teammitglied gehen: " + verzeichnis.empfaenger);
    }

    @Test
    void nachsendungOhneRechtWirdUnterdrueckt() {
        ablage.ablegen("ban", "Text");
        verzeichnis.recht = false;
        service.liefereVerpassteNach(verzeichnis.teamUuid, ablage.findeNach(0L, 100));
        assertTrue(verzeichnis.gesendet.isEmpty());
    }

    @Test
    void nachsendungAnOfflineSpielerWirdUnterdrueckt() {
        ablage.ablegen("ban", "Text");
        service.liefereVerpassteNach(UUID.randomUUID(), ablage.findeNach(0L, 100));
        assertTrue(verzeichnis.gesendet.isEmpty());
    }
}
