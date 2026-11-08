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
import de.lightrixx.punish.service.IpSchutzHelper;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.service.WarnService;
import de.lightrixx.punish.storage.IoExecutor;
import de.lightrixx.punish.storage.PlayerCache;
import de.lightrixx.punish.storage.WarnRepository;
import de.lightrixx.punish.storage.YamlSpeicher;
import de.lightrixx.punish.update.HangarUpdateService;
import org.junit.jupiter.api.AfterEach;
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
        assertTrue(Konfiguration.istDowngrade("2026-11-02"));
        assertFalse(Konfiguration.istDowngrade("2026-10-31"));
        assertFalse(Konfiguration.istDowngrade(Konfiguration.AKTUELLE_CONFIG_VERSION));
        assertFalse(Konfiguration.istDowngrade(null));
        assertFalse(Konfiguration.istDowngrade("unbekannt"));
        assertTrue(Konfiguration.istConfigNeuerAlsJar("2026-11-02", "2026-11-01"));
        assertFalse(Konfiguration.istConfigNeuerAlsJar("2026-11-01", "2026-11-01"));
        assertTrue(Konfiguration.istConfigNeuerAlsJar("2026-11-01", "2026-10-31"));
        assertFalse(Konfiguration.istConfigNeuerAlsJar("2026-10-31", "2026-11-01"));
    }
    @Test
    void serverBannTrenntNurSpielerAufBetroffenemServer() {
        var verzeichnis = mock(SpielerVerzeichnis.class);
        UUID uuid = UUID.randomUUID();
        when(verzeichnis.darfDirektAusfuehren()).thenReturn(true);
        when(verzeichnis.onlineSpieler(uuid)).thenReturn(Optional.of(
                new OnlineSpieler(uuid, "Steve", "1.2.3.4", false)));
        when(verzeichnis.serverName(uuid)).thenReturn("lobby");
        var config = mock(Konfiguration.class);
        when(config.screen(any())).thenReturn("Gesperrt");
        var helper = new de.lightrixx.punish.service.BanKickHelper(
                mock(de.lightrixx.punish.core.AuftragsPlaner.class), config,
                mock(ProtectionService.class), verzeichnis);
        helper.spielerKicken(uuid, "bann", Map.of("scope", "survival"));
        org.mockito.Mockito.verify(verzeichnis, org.mockito.Mockito.never()).trennen(any(), any());
        helper.spielerKicken(uuid, "bann", Map.of("scope", "lobby"));
        helper.spielerKicken(uuid, "bann", Map.of("scope", "global"));
        org.mockito.Mockito.verify(verzeichnis, org.mockito.Mockito.times(2)).trennen(eq(uuid), any());
    }

    @Test
    void manuellesAutoPardonWirdNichtSofortRueckgaengigGemacht() {
        var cache = mock(PlayerCache.class);
        UUID uuid = UUID.randomUUID();
        when(cache.findenNachName("Steve")).thenReturn(Optional.of(
                new CachedPlayer(uuid, "Steve", "1.2.3.4", 1L)));
        when(cache.istFrischeNamensZuordnung("Steve")).thenReturn(true);
        var ban = mock(de.lightrixx.punish.service.BanService.class);
        var scope = de.lightrixx.punish.model.ServerScope.global();
        when(ban.finden(uuid, scope)).thenReturn(Optional.of(new de.lightrixx.punish.model.BanRecord(
                uuid, "Steve", "Auto", "System", 1L, System.currentTimeMillis() + 60_000L, true, scope)));
        when(ban.entbannen(eq(uuid), eq("Steve"), any(), eq(scope), any(), any(Boolean.class)))
                .thenReturn(true);
        var config = mock(Konfiguration.class);
        when(config.standardScope()).thenReturn(scope);
        var befehl = new de.lightrixx.punish.command.PardonCommand(mock(de.lightrixx.punish.service.MessageService.class),
                config, mock(ProtectionService.class), cache, ban,
                mock(de.lightrixx.punish.core.AuftragsPlaner.class), mock(SpielerVerzeichnis.class));
        var verstoesse = mock(de.lightrixx.punish.service.VerstossService.class);
        befehl.setVerstossService(verstoesse);
        var sender = mock(BefehlsSender.class);
        when(sender.name()).thenReturn("Mod");
        befehl.ausfuehren(sender, new String[]{"Steve", "--global"});
        org.mockito.Mockito.verify(ban).entbannen(eq(uuid), eq("Steve"), any(), eq(scope), any(), eq(false));
        org.mockito.Mockito.verify(verstoesse, org.mockito.Mockito.never()).autoBannErneutPruefen(any(), any(), any());
    }
    @Test
    void ipStrafeTrenntNurSpielerImScopeAuchBeiGleicherIp() {
        var verzeichnis = mock(SpielerVerzeichnis.class);
        var lobby = new OnlineSpieler(UUID.randomUUID(), "Lobby", "1.2.3.4", false);
        var survival = new OnlineSpieler(UUID.randomUUID(), "Survival", "1.2.3.4", false);
        when(verzeichnis.darfDirektAusfuehren()).thenReturn(true);
        when(verzeichnis.onlineSpieler()).thenReturn(List.of(lobby, survival));
        when(verzeichnis.serverName(lobby.uuid())).thenReturn("lobby");
        when(verzeichnis.serverName(survival.uuid())).thenReturn("survival");
        var config = mock(Konfiguration.class);
        when(config.screen(any())).thenReturn("Gesperrt");
        var helper = new de.lightrixx.punish.service.BanKickHelper(
                mock(de.lightrixx.punish.core.AuftragsPlaner.class), config,
                mock(ProtectionService.class), verzeichnis);
        helper.onlineSpielerMitIpKickenMitScreen("1.2.3.4", "Test", "Mod", 60_000L,
                System.currentTimeMillis() + 60_000L, "ip-timeout", ProtectionService.IgnoreTyp.TIMEOUT,
                de.lightrixx.punish.model.ServerScope.ausSpeicherwert("survival"));
        org.mockito.Mockito.verify(verzeichnis, org.mockito.Mockito.never()).trennen(eq(lobby.uuid()), any());
        org.mockito.Mockito.verify(verzeichnis).trennen(eq(survival.uuid()), any());
    }

    @Test
    void pardonOhneExplizitenScopeWirdAbgelehnt() {
        var message = mock(de.lightrixx.punish.service.MessageService.class);
        var ban = mock(de.lightrixx.punish.service.BanService.class);
        var config = mock(Konfiguration.class);
        var scope = de.lightrixx.punish.model.ServerScope.global();
        when(config.standardScope()).thenReturn(scope);
        var befehl = new de.lightrixx.punish.command.PardonCommand(message, config,
                mock(ProtectionService.class), mock(PlayerCache.class), ban,
                mock(de.lightrixx.punish.core.AuftragsPlaner.class), mock(SpielerVerzeichnis.class));
        var sender = mock(BefehlsSender.class);
        befehl.ausfuehren(sender, new String[]{"Steve"});
        org.mockito.Mockito.verify(message).senden(sender, MessageKeys.PARDON_SCOPE_FEHLT);
        org.mockito.Mockito.verifyNoInteractions(ban);
    }

    @Test
    void banPersistenzFehlerWirdNichtAlsErfolgGemeldet() {
        var repo = mock(de.lightrixx.punish.storage.BanRepository.class);
        when(repo.bannenWennFreiDauerhaft(any()))
                .thenReturn(de.lightrixx.punish.storage.BanRepository.BannPersistenz.FEHLER);
        var history = mock(de.lightrixx.punish.service.HistoryService.class);
        var broadcast = mock(de.lightrixx.punish.service.BroadcastService.class);
        var kick = mock(de.lightrixx.punish.service.BanKickHelper.class);
        var schutz = mock(ProtectionService.class);
        when(schutz.istErlaubteSelbstbestrafung(any(), any())).thenReturn(false);
        when(schutz.istGeschuetzt(any(UUID.class), any(ProtectionService.IgnoreTyp.class))).thenReturn(false);
        var service = new de.lightrixx.punish.service.BanService(repo, history, broadcast,
                mock(de.lightrixx.punish.storage.LogWriter.class), schutz, kick,
                mock(SpielerVerzeichnis.class));
        var ergebnis = service.bannenMitErgebnis(UUID.randomUUID(), "Steve", 60_000L, "Test",
                "Mod", de.lightrixx.punish.model.ServerScope.global(), "lobby", false, null);
        assertEquals(de.lightrixx.punish.service.BanService.BanErgebnis.PERSISTENZ_FEHLER, ergebnis);
        org.mockito.Mockito.verifyNoInteractions(history, broadcast, kick);
    }

    @Test
    void historyFehlerRolltBannZurueck() {
        UUID uuid = UUID.randomUUID();
        var repo = mock(de.lightrixx.punish.storage.BanRepository.class);
        when(repo.bannenWennFreiDauerhaft(any()))
                .thenReturn(de.lightrixx.punish.storage.BanRepository.BannPersistenz.ERFOLG);
        when(repo.entbannenDauerhaft(eq(uuid), any(), any())).thenReturn(true);
        var history = mock(de.lightrixx.punish.service.HistoryService.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("History nicht schreibbar"))
                .when(history).hinzufuegenDauerhaft(any(), any(), any(), any(), any(), any(), any(), any());
        var broadcast = mock(de.lightrixx.punish.service.BroadcastService.class);
        var kick = mock(de.lightrixx.punish.service.BanKickHelper.class);
        var schutz = mock(ProtectionService.class);
        when(schutz.istErlaubteSelbstbestrafung(any(), any())).thenReturn(false);
        when(schutz.istGeschuetzt(any(UUID.class), any(ProtectionService.IgnoreTyp.class))).thenReturn(false);
        var service = new de.lightrixx.punish.service.BanService(repo, history, broadcast,
                mock(de.lightrixx.punish.storage.LogWriter.class), schutz, kick,
                mock(SpielerVerzeichnis.class));
        var ergebnis = service.bannenMitErgebnis(uuid, "Steve", 60_000L, "Test",
                "Mod", de.lightrixx.punish.model.ServerScope.global(), "lobby", false, null);
        assertEquals(de.lightrixx.punish.service.BanService.BanErgebnis.PERSISTENZ_FEHLER, ergebnis);
        org.mockito.Mockito.verify(repo).entbannenDauerhaft(eq(uuid), any(), any());
        org.mockito.Mockito.verifyNoInteractions(broadcast, kick);
    }

    @Test
    void fehlgeschlagenerRollbackWirdSeparatGemeldet() {
        UUID uuid = UUID.randomUUID();
        var repo = mock(de.lightrixx.punish.storage.BanRepository.class);
        when(repo.bannenWennFreiDauerhaft(any()))
                .thenReturn(de.lightrixx.punish.storage.BanRepository.BannPersistenz.ERFOLG);
        when(repo.entbannenDauerhaft(eq(uuid), any(), any())).thenReturn(false);
        var history = mock(de.lightrixx.punish.service.HistoryService.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("History nicht schreibbar"))
                .when(history).hinzufuegenDauerhaft(any(), any(), any(), any(), any(), any(), any(), any());
        var broadcast = mock(de.lightrixx.punish.service.BroadcastService.class);
        var kick = mock(de.lightrixx.punish.service.BanKickHelper.class);
        var schutz = mock(ProtectionService.class);
        when(schutz.istErlaubteSelbstbestrafung(any(), any())).thenReturn(false);
        when(schutz.istGeschuetzt(any(UUID.class), any(ProtectionService.IgnoreTyp.class))).thenReturn(false);
        var service = new de.lightrixx.punish.service.BanService(repo, history, broadcast,
                mock(de.lightrixx.punish.storage.LogWriter.class), schutz, kick,
                mock(SpielerVerzeichnis.class));
        var ergebnis = service.bannenMitErgebnis(uuid, "Steve", 60_000L, "Test",
                "Mod", de.lightrixx.punish.model.ServerScope.global(), "lobby", false, null);
        assertEquals(de.lightrixx.punish.service.BanService.BanErgebnis.ROLLBACK_FEHLER, ergebnis);
        org.mockito.Mockito.verify(repo).entbannenDauerhaft(eq(uuid), any(), any());
        org.mockito.Mockito.verifyNoInteractions(broadcast, kick);
    }

    @Test
    void unsichererSchutzHinweisNenntErlaubnisBeiFailOpen() {
        var messages = mock(de.lightrixx.punish.service.MessageService.class);
        var schutz = mock(ProtectionService.class);
        when(schutz.istFailClosed()).thenReturn(false);
        var befehl = new HinweisBefehl(messages, schutz);
        var sender = mock(BefehlsSender.class);
        befehl.hinweis(sender, "Steve", ProtectionService.SchutzErgebnis.UNSICHER);
        org.mockito.Mockito.verify(messages).senden(sender,
                MessageKeys.SCHUTZ_HINWEIS_UNBEKANNT_ERLAUBT, Map.of("spieler", "Steve"));
    }

    private static final class HinweisBefehl extends PunishCommand {
        private HinweisBefehl(de.lightrixx.punish.service.MessageService messages,
                              ProtectionService schutz) {
            super(messages, mock(Konfiguration.class), schutz, mock(PlayerCache.class));
        }

        private void hinweis(BefehlsSender sender, String name,
                             ProtectionService.SchutzErgebnis ergebnis) {
            sendeSchutzHinweis(sender, name, ergebnis);
        }

        @Override
        protected String recht() {
            return "test";
        }

        @Override
        public void ausfuehren(BefehlsSender sender, String[] args) {
        }

        @Override
        public List<String> vervollstaendigen(BefehlsSender sender, String[] args) {
            return List.of();
        }
    }
}
