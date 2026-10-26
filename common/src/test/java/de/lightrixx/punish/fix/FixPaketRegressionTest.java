package de.lightrixx.punish.fix;

import de.lightrixx.punish.command.PunishCommand;
import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.OfflineVerzeichnis;
import de.lightrixx.punish.core.OnlineSpieler;
import de.lightrixx.punish.core.RangBuendel;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.hook.LuckPermsZugang;
import de.lightrixx.punish.model.CachedPlayer;
import de.lightrixx.punish.model.HistoryEntry;
import de.lightrixx.punish.model.HistoryType;
import de.lightrixx.punish.service.IpSchutzHelper;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.service.WarnService;
import de.lightrixx.punish.storage.HistoryRepository;
import de.lightrixx.punish.storage.IoExecutor;
import de.lightrixx.punish.storage.PlayerCache;
import de.lightrixx.punish.storage.WarnRepository;
import de.lightrixx.punish.storage.YamlSpeicher;
import de.lightrixx.punish.update.HangarUpdateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FixPaketRegressionTest {

    /** Merkt sich den zuletzt geschriebenen YAML-Stand, um Persistenz pruefen zu koennen. */
    private static final class MerkenderSpeicher implements YamlSpeicher {
        private final java.util.concurrent.ConcurrentHashMap<File, Map<String, Object>> staende =
                new java.util.concurrent.ConcurrentHashMap<>();
        private volatile Map<String, Object> letzterStand;

        @Override
        public Map<String, Object> laden(File datei) {
            return staende.getOrDefault(datei, Map.of());
        }

        @Override
        public void speichern(Map<String, Object> daten, File datei) {
            Map<String, Object> kopie = new java.util.HashMap<>(daten);
            staende.put(datei, kopie);
            letzterStand = kopie;
        }
    }

    @AfterEach
    void aufraeumen() {
        RangBuendel.laden(Map.of());
        RangBuendel.ladenGewichte(Map.of());
        PlayerCache.setMojangStubForTests(null);
        PlayerCache.resetMojangCircuitForTests();
    }

    // A-007 Rang-Hierarchie
    private ProtectionService rangSchutz(UUID niedrig, UUID hoch) {
        SpielerVerzeichnis verzeichnis = mock(SpielerVerzeichnis.class);
        when(verzeichnis.onlineSpieler(niedrig))
                .thenReturn(Optional.of(new OnlineSpieler(niedrig, "Niedrig", "1.1.1.1", false)));
        when(verzeichnis.onlineSpieler(hoch))
                .thenReturn(Optional.of(new OnlineSpieler(hoch, "Hoch", "2.2.2.2", false)));
        when(verzeichnis.hatRecht(eq(niedrig), any())).thenAnswer(inv -> {
            String knoten = inv.getArgument(1);
            return "punish.moderator".equals(knoten);
        });
        when(verzeichnis.hatRecht(eq(hoch), any())).thenAnswer(inv -> {
            String knoten = inv.getArgument(1);
            return "punish.admin".equals(knoten);
        });
        OfflineVerzeichnis offline = mock(OfflineVerzeichnis.class);
        when(offline.findeNachUuid(any(UUID.class))).thenReturn(Optional.empty());
        LuckPermsZugang zugang = mock(LuckPermsZugang.class);
        when(zugang.isAvailable()).thenReturn(false);
        when(zugang.rangGewicht(any())).thenReturn(OptionalIntLeer());
        return new ProtectionService(verzeichnis, offline, zugang);
    }

    private static java.util.OptionalInt OptionalIntLeer() {
        return java.util.OptionalInt.empty();
    }

    @Test
    void a007_niedrigNachHochBlockiert() {
        RangBuendel.ladenGewichte(Map.of("moderator", 50, "admin", 80));
        UUID niedrig = UUID.randomUUID();
        UUID hoch = UUID.randomUUID();
        ProtectionService schutz = rangSchutz(niedrig, hoch);
        assertTrue(schutz.istRanghoeher(niedrig, hoch));
        assertFalse(schutz.istRanghoeher(hoch, niedrig));
        assertFalse(schutz.istRanghoeher(niedrig, niedrig));
        BefehlsSender sender = mock(BefehlsSender.class);
        when(sender.istSpieler()).thenReturn(true);
        when(sender.spielerUuid()).thenReturn(niedrig);
        assertTrue(schutz.istRangBlockiert(sender, hoch));
        when(sender.spielerUuid()).thenReturn(hoch);
        assertFalse(schutz.istRangBlockiert(sender, niedrig));
        BefehlsSender konsole = mock(BefehlsSender.class);
        when(konsole.istSpieler()).thenReturn(false);
        assertFalse(schutz.istRangBlockiert(konsole, hoch));
        assertFalse(schutz.istRangBlockiert(null, hoch));
    }

    @Test
    void a007_rangGewichteAusBuendel() {
        RangBuendel.ladenGewichte(Map.of("moderator", 50));
        assertEquals(50, RangBuendel.gewichtFuerGruppe("moderator"));
        assertEquals(80, RangBuendel.gewichtFuerGruppe("admin"));
        assertEquals(0, RangBuendel.gewichtFuerGruppe("unbekannt"));
        assertEquals(50, RangBuendel.maxGewichtFuer(k -> "punish.moderator".equals(k)));
    }

    // A-008 Offline-IP-Schutz
    @Test
    void a008_offlineIpSchutzBlockiert() throws Exception {
        File tmp = new File(System.getProperty("java.io.tmpdir"), "punish-fix-" + System.nanoTime());
        tmp.mkdirs();
        YamlSpeicher yaml = mock(YamlSpeicher.class);
        when(yaml.laden(any(File.class))).thenReturn(Map.of());
        IoExecutor io = new IoExecutor();
        OfflineVerzeichnis offline = mock(OfflineVerzeichnis.class);
        when(offline.istMainThread()).thenReturn(false);
        PlayerCache cache = new PlayerCache(tmp, yaml, io, offline);
        UUID geschuetztUuid = UUID.randomUUID();
        cache.aktualisieren(new CachedPlayer(geschuetztUuid, "AltSpieler", "5.6.7.8", System.currentTimeMillis()));
        ProtectionService schutz = mock(ProtectionService.class);
        when(schutz.istGeschuetzt(eq(geschuetztUuid))).thenReturn(true);
        when(schutz.istGeschuetzt(eq(geschuetztUuid), any())).thenReturn(true);
        SpielerVerzeichnis verzeichnis = mock(SpielerVerzeichnis.class);
        when(verzeichnis.onlineSpieler()).thenReturn(List.of());
        assertTrue(IpSchutzHelper.istIpDurchOfflineSchutzBlockiert("5.6.7.8", schutz, cache, null));
        assertFalse(IpSchutzHelper.istIpDurchOfflineSchutzBlockiert("9.9.9.9", schutz, cache, null));
        io.herunterfahren(5, TimeUnit.SECONDS);
    }

    // A-009 Stale Namen Revalidierung
    @Test
    void a009_staleLoestRevalidierungAus() throws Exception {
        File tmp = new File(System.getProperty("java.io.tmpdir"), "punish-fix-" + System.nanoTime());
        tmp.mkdirs();
        YamlSpeicher yaml = mock(YamlSpeicher.class);
        when(yaml.laden(any(File.class))).thenReturn(Map.of());
        IoExecutor io = new IoExecutor();
        OfflineVerzeichnis offline = mock(OfflineVerzeichnis.class);
        when(offline.istMainThread()).thenReturn(true);
        PlayerCache cache = new PlayerCache(tmp, yaml, io, offline);
        UUID altUuid = UUID.randomUUID();
        long alt = System.currentTimeMillis() - 38L * 24L * 60L * 60L * 1000L;
        cache.aktualisieren(new CachedPlayer(altUuid, "StaleName", "unbekannt", alt));
        // aktualisieren setzt zuletzt-gesehen? CachedPlayer behält übergebenen Wert
        AtomicReference<String> beobachtet = new AtomicReference<>();
        cache.setRevalidierungsBeobachter(beobachtet::set);
        Optional<CachedPlayer> ergebnis = cache.findeExternOffline("StaleName");
        assertTrue(ergebnis.isPresent());
        assertEquals(altUuid, ergebnis.get().uuid());
        assertEquals("StaleName", beobachtet.get());
        assertTrue(cache.anzahlRevalidierungenAngestossen() >= 1);
        io.herunterfahren(5, TimeUnit.SECONDS);
    }

    // A-015 Eskalations-Cap zählt und warnt
    @Test
    void a015_capZaehltVerworfen() throws Exception {
        WarnRepository repo = mock(WarnRepository.class);
        WarnService service = new WarnService(repo);
        var methode = WarnService.class.getDeclaredMethod("merkeAusstehende", UUID.class, int.class,
                de.lightrixx.punish.model.VerstossStufe.class);
        methode.setAccessible(true);
        for (int i = 0; i < 1000; i++) {
            methode.invoke(service, UUID.randomUUID(), 3, de.lightrixx.punish.model.VerstossStufe.LOW);
        }
        assertEquals(0, service.anzahlVerworfenerEskalationen());
        methode.invoke(service, UUID.randomUUID(), 3, de.lightrixx.punish.model.VerstossStufe.LOW);
        assertEquals(1, service.anzahlVerworfenerEskalationen());
        methode.invoke(service, UUID.randomUUID(), 3, de.lightrixx.punish.model.VerstossStufe.LOW);
        assertEquals(2, service.anzahlVerworfenerEskalationen());
    }

    // A-016 IP-Retention
    @Test
    void a016_abgelaufeneIpWirdAnonymisiertFrischeBleibt() throws Exception {
        File tmp = new File(System.getProperty("java.io.tmpdir"), "punish-fix-" + System.nanoTime());
        tmp.mkdirs();
        YamlSpeicher yaml = mock(YamlSpeicher.class);
        when(yaml.laden(any(File.class))).thenReturn(Map.of());
        IoExecutor io = new IoExecutor();
        OfflineVerzeichnis offline = mock(OfflineVerzeichnis.class);
        PlayerCache cache = new PlayerCache(tmp, yaml, io, offline);
        long frist = 90L * 24L * 60L * 60L * 1000L;
        long jetzt = System.currentTimeMillis();
        UUID altUuid = UUID.randomUUID();
        UUID neuUuid = UUID.randomUUID();
        cache.aktualisieren(new CachedPlayer(altUuid, "AltSpieler", "1.1.1.1", jetzt - frist - 1000L));
        cache.aktualisieren(new CachedPlayer(neuUuid, "NeuSpieler", "2.2.2.2", jetzt));
        int anonymisiert = cache.sweepAbgelaufeneIps(jetzt, frist);
        assertEquals(1, anonymisiert);
        assertEquals("unbekannt", cache.finden(altUuid).orElseThrow().letzteIp());
        assertEquals("2.2.2.2", cache.finden(neuUuid).orElseThrow().letzteIp());
        io.herunterfahren(5, TimeUnit.SECONDS);
    }

    // A-016b Konfigurierte IP-Retention wird angewendet
    @Test
    void a016b_konfigurierteFristWirdAngewendet() throws Exception {
        File tmp = new File(System.getProperty("java.io.tmpdir"), "punish-fix-retention-" + System.nanoTime());
        tmp.mkdirs();
        YamlSpeicher yaml = mock(YamlSpeicher.class);
        when(yaml.laden(any(File.class))).thenReturn(Map.of());
        IoExecutor io = new IoExecutor();
        OfflineVerzeichnis offline = mock(OfflineVerzeichnis.class);
        PlayerCache cache = new PlayerCache(tmp, yaml, io, offline);
        cache.setIpRetentionTage(30);
        long jetzt = System.currentTimeMillis();
        long tag = 24L * 60L * 60L * 1000L;
        UUID altUuid = UUID.randomUUID();
        UUID neuUuid = UUID.randomUUID();
        cache.aktualisieren(new CachedPlayer(altUuid, "AltSpieler", "1.1.1.1", jetzt - 31L * tag));
        cache.aktualisieren(new CachedPlayer(neuUuid, "NeuSpieler", "2.2.2.2", jetzt - 29L * tag));
        assertEquals(1, cache.sweepAbgelaufeneIps());
        assertEquals("unbekannt", cache.finden(altUuid).orElseThrow().letzteIp());
        assertEquals("2.2.2.2", cache.finden(neuUuid).orElseThrow().letzteIp());
        io.herunterfahren(5, TimeUnit.SECONDS);
    }

    /**
     * A-016c: Der Sweep muss das Ergebnis auch schreiben. Die Verdrahtung (Aufruf beim
     * Start/Reload) liegt im Velocity-Modul und ist hier nicht erreichbar; dieser Test deckt
     * die Persistenz des anonymisierten Stands ab.
     */
    @Test
    void a016c_sweepPersistiertAnonymisierteIp() throws Exception {
        File tmp = new File(System.getProperty("java.io.tmpdir"),
                "punish-fix-retention-save-" + System.nanoTime());
        tmp.mkdirs();
        MerkenderSpeicher yaml = new MerkenderSpeicher();
        IoExecutor io = new IoExecutor();
        OfflineVerzeichnis offline = mock(OfflineVerzeichnis.class);
        PlayerCache cache = new PlayerCache(tmp, yaml, io, offline);
        cache.setIpRetentionTage(30);
        long jetzt = System.currentTimeMillis();
        long tag = 24L * 60L * 60L * 1000L;
        UUID altUuid = UUID.randomUUID();
        cache.aktualisieren(new CachedPlayer(altUuid, "AltSpieler", "1.1.1.1", jetzt - 40L * tag));
        assertEquals(1, cache.sweepAbgelaufeneIps());
        assertTrue(io.warteAufIdle(5, TimeUnit.SECONDS), "IO-Queue sollte leer sein");
        Map<String, Object> stand = yaml.letzterStand;
        assertTrue(stand != null, "Retention muss den Cache persistieren");
        Object roh = stand.get(altUuid.toString());
        assertTrue(roh instanceof Map<?, ?>, "Persistierter Eintrag fehlt");
        assertEquals("unbekannt", ((Map<?, ?>) roh).get("letzte-ip"),
                "Anonymisierte IP muss auf der Platte landen");
        io.herunterfahren(5, TimeUnit.SECONDS);
    }

    // A-018 Kürzungshinweis
    @Test
    void a018_grundKuerzungErkannt() {
        String lang = "x".repeat(300);
        assertTrue(PunishCommand.wurdeGekuerzt(lang));
        assertFalse(PunishCommand.wurdeGekuerzt("kurz"));
        String normalisiert = PunishCommand.normalisiereGrund(lang);
        assertEquals(256, normalisiert.length());
        assertEquals(MessageKeys.GRUND_GEKUERZT, "grund-gekuerzt-hinweis");
    }

    // A-020 Circuit-Breaker
    @Test
    void a020_fuenfFehlerOeffnenCircuit() {
        PlayerCache.resetMojangCircuitForTests();
        PlayerCache.setMojangStubForTests(name -> {
            throw new IllegalStateException("simulierter Ausfall");
        });
        for (int i = 0; i < 5; i++) {
            PlayerCache.mojangUuidFuer("TestSpieler");
        }
        assertEquals(5, PlayerCache.mojangFehlerZaehler());
        assertTrue(PlayerCache.mojangCircuitOffen());
        long versucheVorher = PlayerCache.mojangHttpVersuche();
        PlayerCache.mojangUuidFuer("TestSpieler");
        assertEquals(versucheVorher, PlayerCache.mojangHttpVersuche());
        assertTrue(PlayerCache.mojangGeblockteAufrufe() >= 1);
        PlayerCache.setMojangStubForTests(null);
        PlayerCache.resetMojangCircuitForTests();
    }

    // D-004 Intervall-Check
    @Test
    void d004_intervallFeuertScheduler() {
        var planer = mock(de.lightrixx.punish.core.AuftragsPlaner.class);
        var aufgabe = mock(de.lightrixx.punish.core.GeplanteAufgabe.class);
        when(planer.intervallAsync(any(), any(Long.class), any(Long.class))).thenReturn(aufgabe);
        var feuer = new java.util.concurrent.atomic.AtomicBoolean(false);
        var ergebnis = HangarUpdateService.planeHinweisIntervall(planer, () -> 60L, () -> feuer.set(true));
        assertTrue(ergebnis == aufgabe);
        org.mockito.Mockito.verify(planer).intervallAsync(any(), eq(60L * 60L * 20L), eq(60L * 60L * 20L));
        assertEquals(60L * 60L * 20L, HangarUpdateService.ticksFuerIntervallMinuten(60L));
        assertEquals(0L, HangarUpdateService.ticksFuerIntervallMinuten(0L));
        var kein = HangarUpdateService.planeHinweisIntervall(planer, () -> 0L, () -> {
        });
        assertTrue(kein == null);
    }

    // D-006 Downgrade
    @Test
    void d006_downgradeErkannt() {
        assertTrue(Konfiguration.istDowngrade("EA99z"));
        assertFalse(Konfiguration.istDowngrade("EA01a"));
        assertFalse(Konfiguration.istDowngrade(Konfiguration.AKTUELLE_CONFIG_VERSION));
        assertFalse(Konfiguration.istDowngrade(null));
        assertFalse(Konfiguration.istDowngrade("unbekannt"));
        assertTrue(Konfiguration.istConfigNeuerAlsJar("EA26a", "EA25b"));
        assertFalse(Konfiguration.istConfigNeuerAlsJar("EA25b", "EA25b"));
        assertTrue(Konfiguration.istConfigNeuerAlsJar("EA26", "EA25b"));
        assertFalse(Konfiguration.istConfigNeuerAlsJar("EA25b", "EA26"));
    }
}
