package de.lightrixx.punish.audit;

import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.OfflineVerzeichnis;
import de.lightrixx.punish.core.OnlineSpieler;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.hook.LuckPermsZugang;
import de.lightrixx.punish.model.BanRecord;
import de.lightrixx.punish.model.ParseResult;
import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.net.Protokoll;
import de.lightrixx.punish.service.BanKickHelper;
import de.lightrixx.punish.service.BanService;
import de.lightrixx.punish.service.BroadcastService;
import de.lightrixx.punish.service.FreezeService;
import de.lightrixx.punish.service.HistoryService;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.MuteService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.service.TimeoutService;
import de.lightrixx.punish.storage.BanRepository;
import de.lightrixx.punish.storage.FreezeRepository;
import de.lightrixx.punish.storage.IoExecutor;
import de.lightrixx.punish.storage.LogWriter;
import de.lightrixx.punish.storage.MuteRepository;
import de.lightrixx.punish.storage.TimeoutRepository;
import de.lightrixx.punish.storage.YamlSpeicher;
import de.lightrixx.punish.util.TimeParser;
import de.lightrixx.punish.util.ZeitFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuditIngameSimulationTest {

    private IoExecutor io;
    private MapSpeicher speicher;
    private SpielerVerzeichnis verzeichnis;
    private ProtectionService schutz;

    @BeforeEach
    void start() {
        io = new IoExecutor();
        speicher = new MapSpeicher();
        verzeichnis = mock(SpielerVerzeichnis.class);
        OfflineVerzeichnis offline = mock(OfflineVerzeichnis.class);
        LuckPermsZugang luckPerms = mock(LuckPermsZugang.class);
        when(luckPerms.isAvailable()).thenReturn(true);
        when(offline.istMainThread()).thenReturn(false);
        schutz = new ProtectionService(verzeichnis, offline, luckPerms);
    }

    @AfterEach
    void stop() {
        if (io != null) {
            io.herunterfahren(5, TimeUnit.SECONDS);
            io = null;
        }
    }

    static final class MapSpeicher implements YamlSpeicher {
        final Map<String, Map<String, Object>> dateien = new HashMap<>();

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

    private BanService banService(@TempDir File ordner) {
        BanRepository repo = new BanRepository(ordner, speicher, io);
        repo.laden();
        return new BanService(repo, mock(HistoryService.class), mock(BroadcastService.class),
                mock(LogWriter.class), schutz, mock(BanKickHelper.class), verzeichnis);
    }

    private MuteService muteService(@TempDir File ordner) {
        MuteRepository repo = new MuteRepository(ordner, speicher, io);
        repo.laden();
        return new MuteService(repo, mock(HistoryService.class), mock(BroadcastService.class),
                mock(LogWriter.class), mock(MessageService.class), mock(AuftragsPlaner.class),
                schutz, verzeichnis);
    }

    private FreezeService freezeService(@TempDir File ordner) {
        FreezeRepository repo = new FreezeRepository(ordner, speicher, io);
        repo.laden();
        return new FreezeService(repo, mock(HistoryService.class), mock(BroadcastService.class),
                mock(LogWriter.class), mock(MessageService.class), mock(AuftragsPlaner.class),
                schutz, verzeichnis);
    }

    private TimeoutService timeoutService(@TempDir File ordner) {
        TimeoutRepository repo = new TimeoutRepository(ordner, speicher, io);
        repo.laden();
        return new TimeoutService(repo, mock(Konfiguration.class), mock(HistoryService.class),
                mock(BroadcastService.class), mock(LogWriter.class), mock(AuftragsPlaner.class),
                schutz, verzeichnis);
    }

    private static OnlineSpieler online(UUID uuid, String name) {
        return new OnlineSpieler(uuid, name, "10.0.0.1", false);
    }

    private void schutzAn(UUID uuid, boolean geschuetzt) {
        when(verzeichnis.darfDirektAusfuehren()).thenReturn(true);
        when(verzeichnis.hatRecht(any(UUID.class), anyString()))
                .thenReturn(geschuetzt);
        when(verzeichnis.onlineSpieler(any(UUID.class)))
                .thenReturn(geschuetzt ? Optional.of(online(uuid, "Chef")) : Optional.empty());
    }

    private static long dauerVon(String eingabe) {
        ParseResult ergebnis = new TimeParser().parsen(eingabe);
        assertInstanceOf(ParseResult.Geltzeit.class, ergebnis);
        return ((ParseResult.Geltzeit) ergebnis).millis();
    }

    @Test
    void szenario1ModBanntSiebenTageGlobal(@TempDir File ordner) {
        BanService service = banService(ordner);
        UUID ziel = UUID.randomUUID();
        schutzAn(ziel, false);
        long dauer = dauerVon("7d");

        assertTrue(service.bannen(ziel, "GrieferX", dauer, "Griefing", "ModA",
                ServerScope.global(), "lobby"), "Bann muss gesetzt werden");

        assertTrue(service.findeWirksam(ziel, "lobby").isPresent(), "Gebannt auf lobby");
        assertTrue(service.findeWirksam(ziel, "survival").isPresent(), "Gebannt auf survival");
        Optional<BanRecord> bann = service.findeAbdeckend(ziel, ServerScope.global());
        assertTrue(bann.isPresent());
        assertEquals("Griefing", bann.get().grund());
        assertEquals("ModA", bann.get().ausfuehrer());
        long verbleibend = bann.get().verbleibendeMillis(System.currentTimeMillis());
        assertTrue(verbleibend > dauer - 1000L, "Restzeit fast voll: " + verbleibend);
        assertFalse(ZeitFormat.formatRestzeit(verbleibend).isBlank(), "Restzeit formatiert");

        assertTrue(service.entbannen(ziel, "GrieferX", "Admin", ServerScope.global(), "lobby"),
                "Entbannen muss wirken");
        assertTrue(service.findeWirksam(ziel, "lobby").isEmpty(), "Nach Pardon wieder joinbar");
    }

    @Test
    void szenario2ScopeBannBetrifftNurLobby(@TempDir File ordner) {
        BanService service = banService(ordner);
        UUID ziel = UUID.randomUUID();
        schutzAn(ziel, false);

        assertTrue(service.bannen(ziel, "Troll", dauerVon("1h"), "Beleidigung", "ModB",
                ServerScope.server("lobby"), "lobby"));

        assertTrue(service.findeWirksam(ziel, "lobby").isPresent(), "lobby blockiert");
        assertTrue(service.findeWirksam(ziel, "survival").isEmpty(), "survival frei");
        assertTrue(service.findeWirksam(ziel, "creative").isEmpty(), "anderer Server frei");
    }

    @Test
    void szenario3MuteUndScopeMute(@TempDir File ordner) {
        MuteService service = muteService(ordner);
        UUID ziel = UUID.randomUUID();
        schutzAn(ziel, false);

        assertTrue(service.muten(ziel, "Spamer", dauerVon("30min"), "Spam", "ModC",
                ServerScope.global(), "lobby"));
        assertTrue(service.istGemutetFuer(ziel, "lobby"));
        assertTrue(service.istGemutetFuer(ziel, "creative"));

        assertTrue(service.entmuten(ziel, "Spamer", "ModD", ServerScope.global(), "lobby"));
        assertFalse(service.istGemutetFuer(ziel, "lobby"));

        assertTrue(service.muten(ziel, "Spamer", dauerVon("30min"), "Spam", "ModC",
                ServerScope.server("creative"), "lobby"));
        assertFalse(service.istGemutetFuer(ziel, "lobby"), "Scope-Mute greift nicht fremd");
        assertTrue(service.istGemutetFuer(ziel, "creative"), "Scope-Mute greift lokal");
    }

    @Test
    void szenario4BannEntferntEntsperrt(@TempDir File ordner) {
        BanService service = banService(ordner);
        UUID ziel = UUID.randomUUID();
        schutzAn(ziel, false);

        assertTrue(service.bannen(ziel, "OldOne", 1000L, "kurz", "ModE"));
        assertTrue(service.findeWirksam(ziel, "lobby").isPresent());
        assertTrue(service.entbannen(ziel, "OldOne", "ModE", ServerScope.unbekannt(), "lobby"),
                "Entbannen im selben Scope wie verhängt");
        assertTrue(service.findeWirksam(ziel, "lobby").isEmpty());
    }

    @Test
    void szenario5SchutzBlockiertBann(@TempDir File ordner) {
        BanService service = banService(ordner);
        UUID admin = UUID.randomUUID();
        schutzAn(admin, true);

        boolean versucht = service.bannen(admin, "Chef", dauerVon("1h"), "Rache", "Rachemod");
        assertFalse(versucht, "Bann gegen geschützten Mod muss abgewiesen werden");
    }

    @Test
    void szenario6TimeoutWorkflow(@TempDir File ordner) {
        TimeoutService service = timeoutService(ordner);
        UUID ziel = UUID.randomUUID();
        schutzAn(ziel, false);

        assertTrue(service.timeoutVerhaengen(ziel, "Flamer", dauerVon("15min"), "Flame", "ModF",
                ServerScope.global(), "lobby"));
        assertTrue(service.findeWirksamOhneCleanup(ziel, "lobby", System.currentTimeMillis()).isPresent());
        assertTrue(service.hatEintrag(ziel));

        assertTrue(service.timeoutAufheben(ziel, "Flamer", "ModG", ServerScope.global(), "lobby"));
        assertFalse(service.hatEintrag(ziel));
    }

    @Test
    void szenario7FreezeWorkflow(@TempDir File ordner) {
        FreezeService service = freezeService(ordner);
        UUID ziel = UUID.randomUUID();
        schutzAn(ziel, false);

        assertTrue(service.freezen(ziel, "Flüchter", "Freundliches Einfrieren", "ModH",
                ServerScope.global(), "lobby"));
        assertTrue(service.istGefrorenFuer(ziel, "lobby"));
        assertTrue(service.istIrgendwoGefroren(ziel));

        assertTrue(service.entfreezen(ziel, "Flüchter", "ModI", ServerScope.global(), "lobby"));
        assertFalse(service.istGefrorenFuer(ziel, "lobby"));
    }

    @Test
    void szenario8ProtokollRoundtrip() throws Exception {
        long jetzt = System.currentTimeMillis();
        UUID uuid = UUID.randomUUID();
        BanRecord bann = new BanRecord(uuid, "Verbrecher", "Hacking", "ModJ",
                jetzt, jetzt + 86_400_000L, false, ServerScope.global());
        Protokoll.BanEintrag eintrag = new Protokoll.BanEintrag(uuid, "Verbrecher", "Hacking",
                "ModJ", bann.erstelltAm(), bann.ablaufAm() == null ? Long.MAX_VALUE : bann.ablaufAm(),
                bann.scope().speicherwert());

        byte[] paket = Protokoll.banSync(List.of(eintrag));
        Protokoll.Paket gelesen = Protokoll.paket(paket);
        assertEquals(Protokoll.BAN_SYNC, gelesen.typ());
        List<Protokoll.BanEintrag> liste = Protokoll.leseBanSync(gelesen.nutzdaten());
        assertEquals(1, liste.size());
        assertEquals("Hacking", liste.get(0).grund());
        assertTrue(liste.get(0).istAktiv(jetzt));

        assertThrows(Exception.class, () -> Protokoll.paket(new byte[0]),
                "Leeres Paket muss verworfen werden");
        Protokoll.Paket abgeschnitten = Protokoll.paket(new byte[]{Protokoll.BAN_SYNC});
        assertThrows(Exception.class, () -> Protokoll.leseBanSync(abgeschnitten.nutzdaten()),
                "Abgeschnittenes Paket muss verworfen werden");
    }

    @Test
    void szenario9ZeiteingabenUndGrenzen() {
        TimeParser parser = new TimeParser();
        assertInstanceOf(ParseResult.Permanent.class, parser.parsen("perm"));
        assertInstanceOf(ParseResult.Geltzeit.class, parser.parsen("100j"));
        assertInstanceOf(ParseResult.Ungueltig.class, parser.parsen("101j"));
        assertInstanceOf(ParseResult.Ungueltig.class, parser.parsen("0min"));
        assertInstanceOf(ParseResult.Ungueltig.class, parser.parsen("-5min"));
        assertInstanceOf(ParseResult.Ungueltig.class, parser.parsen("99999999999999999999j"));
        assertInstanceOf(ParseResult.Ungueltig.class, parser.parsen("1x"));
        assertInstanceOf(ParseResult.Ungueltig.class, parser.parsen("perm 1min"));
        assertEquals(7L * 24L * 60L * 60L * 1000L, dauerVon("7d"));
        assertEquals(60L * 60L * 1000L, dauerVon("1h"));
        assertEquals(86_400_000L, dauerVon("1t"));
    }
}
