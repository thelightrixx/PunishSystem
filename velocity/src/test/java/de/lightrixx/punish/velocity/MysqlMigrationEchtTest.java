package de.lightrixx.punish.velocity;

import de.lightrixx.punish.core.OfflineVerzeichnis;
import de.lightrixx.punish.model.BanRecord;
import de.lightrixx.punish.model.FreezeRecord;
import de.lightrixx.punish.model.HistoryEntry;
import de.lightrixx.punish.model.HistoryType;
import de.lightrixx.punish.model.IpBanRecord;
import de.lightrixx.punish.model.IpMuteRecord;
import de.lightrixx.punish.model.IpTimeoutRecord;
import de.lightrixx.punish.model.MuteRecord;
import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.model.TimeoutRecord;
import de.lightrixx.punish.model.VerstossStufe;
import de.lightrixx.punish.storage.BanRepository;
import de.lightrixx.punish.storage.FreezeRepository;
import de.lightrixx.punish.storage.HistoryRepository;
import de.lightrixx.punish.storage.IoExecutor;
import de.lightrixx.punish.storage.IpBanRepository;
import de.lightrixx.punish.storage.IpMuteRepository;
import de.lightrixx.punish.storage.IpTimeoutRepository;
import de.lightrixx.punish.storage.MuteRepository;
import de.lightrixx.punish.storage.NoteRepository;
import de.lightrixx.punish.storage.TimeoutRepository;
import de.lightrixx.punish.storage.VerstossRepository;
import de.lightrixx.punish.storage.WarnRepository;
import de.lightrixx.punish.storage.mysql.MysqlKonfiguration;
import de.lightrixx.punish.storage.mysql.MysqlSchema;
import de.lightrixx.punish.storage.mysql.MysqlVerbindung;
import de.lightrixx.punish.storage.mysql.YamlNachMysqlMigrator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tiefen-Audit (M6/M11): echte YAML-Dateien → {@link YamlNachMysqlMigrator} → echte Datenbank → Rückvergleich.
 * Läuft nur mit konfigurierter Verbindung, sonst sauber übersprungen.
 */
class MysqlMigrationEchtTest {

    private static final String TRICKY = "Ümlaut, Emoji \uD83D\uDE00, \"Anführung\", #hash, | pipe, \\slash, \nZeile";

    @TempDir
    Path temp;

    private IoExecutor io;
    private SnakeYamlSpeicher yaml;
    private File daten;
    private File plugin;
    private MysqlVerbindung mysql;

    private static final OfflineVerzeichnis VERZEICHNIS = new OfflineVerzeichnis() {
        @Override
        public Optional<ExternSpieler> findeNachName(String name) {
            return Optional.empty();
        }

        @Override
        public Optional<ExternSpieler> findeNachUuid(UUID uuid) {
            return Optional.empty();
        }

        @Override
        public boolean istMainThread() {
            return false;
        }
    };

    @BeforeEach
    void vorbereiten() throws Exception {
        String db = wert("punish.it.db", null);
        String user = wert("punish.it.user", null);
        Assumptions.assumeTrue(db != null && user != null,
                "Keine Test-Datenbank konfiguriert – Migrationstest übersprungen.");
        io = new IoExecutor();
        yaml = new SnakeYamlSpeicher();
        daten = temp.resolve("daten").toFile();
        plugin = temp.resolve("plugin").toFile();
        assertTrue(daten.mkdirs() || daten.isDirectory());
        assertTrue(plugin.mkdirs() || plugin.isDirectory());
        mysql = MysqlVerbindung.oeffnen(new MysqlKonfiguration(
                wert("punish.it.host", "127.0.0.1"), zahl("punish.it.port", 3306), db, user,
                wert("punish.it.password", ""), false, 5));
        MysqlSchema.initialisieren(mysql);
        leeren();
    }

    @AfterEach
    void aufraeumen() {
        if (io != null) {
            io.herunterfahren(10, TimeUnit.SECONDS);
        }
        if (mysql != null) {
            mysql.schliessen();
        }
    }

    @Test
    @DisplayName("YAML-Bestand wird vollständig, feldgenau und idempotent nach MySQL migriert")
    void migrationUebertraegtAlleStrafarten() {
        UUID global = UUID.fromString("11111111-1111-4111-8111-111111111111");
        UUID server = UUID.fromString("22222222-2222-4222-8222-222222222222");

        BanRepository yamlBans = new BanRepository(daten, yaml, io);
        yamlBans.laden();
        assertTrue(yamlBans.bannenWennFrei(new BanRecord(global, "Globaler", TRICKY, "Mod Ü",
                1_700_000_000_000L, null, false, ServerScope.global())));
        assertTrue(yamlBans.bannenWennFrei(new BanRecord(server, "Server-Spieler", "Grund", "Konsole",
                1_700_000_000_001L, 1_800_000_000_000L, true, ServerScope.server("lobby"))));

        MuteRepository yamlMutes = new MuteRepository(daten, yaml, io);
        yamlMutes.laden();
        assertTrue(yamlMutes.mutenWennFrei(new MuteRecord(server, "Spieler", TRICKY, "Mod",
                1_700_000_000_000L, 1_800_000_000_000L, ServerScope.server("skyblock"))));

        TimeoutRepository yamlTimeouts = new TimeoutRepository(daten, yaml, io);
        yamlTimeouts.laden();
        assertTrue(yamlTimeouts.timeoutVerhaengenWennFrei(new TimeoutRecord(global, "Spieler", TRICKY,
                "Mod", 1_700_000_000_000L, 1_800_000_000_000L, ServerScope.global())));

        FreezeRepository yamlFreezes = new FreezeRepository(daten, yaml, io);
        yamlFreezes.laden();
        assertTrue(yamlFreezes.freezenWennFrei(new FreezeRecord(global, "Spieler", TRICKY, "Mod",
                1_700_000_000_000L, Long.MAX_VALUE, ServerScope.unbekannt())));

        IpBanRepository yamlIpBans = new IpBanRepository(daten, yaml, io);
        yamlIpBans.laden();
        assertTrue(yamlIpBans.bannenWennFrei(new IpBanRecord("::1", TRICKY, "Mod",
                1_700_000_000_000L, null, ServerScope.global())));

        IpMuteRepository yamlIpMutes = new IpMuteRepository(daten, yaml, io);
        yamlIpMutes.laden();
        assertTrue(yamlIpMutes.mutenWennFrei(new IpMuteRecord("10.0.0.9", TRICKY, "Mod",
                1_700_000_000_000L, 1_800_000_000_000L, ServerScope.server("lobby"))));

        IpTimeoutRepository yamlIpTimeouts = new IpTimeoutRepository(daten, yaml, io);
        yamlIpTimeouts.laden();
        assertTrue(yamlIpTimeouts.timeoutVerhaengenWennFrei(new IpTimeoutRecord("192.168.1.1", TRICKY,
                "Mod", 1_700_000_000_000L, 1_800_000_000_000L, ServerScope.global())));

        WarnRepository yamlWarns = new WarnRepository(daten, yaml, io);
        yamlWarns.laden();
        yamlWarns.hinzufuegen(global, "Spieler", TRICKY, "Mod");
        yamlWarns.hinzufuegen(global, "Spieler", "zweiter", "Mod2");

        NoteRepository yamlNotizen = new NoteRepository(daten, yaml, io);
        yamlNotizen.laden();
        yamlNotizen.hinzufuegen(global, "Spieler", TRICKY, "Mod");

        VerstossRepository yamlVerstoesse = new VerstossRepository(daten, yaml, io);
        yamlVerstoesse.laden();
        yamlVerstoesse.hinzufuegen(global, "Spieler", TRICKY, "Mod", VerstossStufe.HIGH);

        HistoryRepository yamlHistory = new HistoryRepository(daten, yaml, io);
        yamlHistory.laden();
        for (int i = 0; i < 30; i++) {
            yamlHistory.hinzufuegenNeu(HistoryType.MUTE, "Mod" + i, global, "Spieler", "Mute " + i,
                    null, "global", "lobby");
        }
        yamlHistory.hinzufuegenNeu(HistoryType.PARDON, "Admin", global, "Spieler", "Grund",
                null, "global", "lobby");

        assertTrue(io.warteAufIdle(30, TimeUnit.SECONDS), "YAML-Schreibqueue muss leer laufen");

        Map<UUID, List<HistoryEntry>> historyVorher = yamlHistory.alleSchluessel();
        List<BanRecord> bansVorher = List.copyOf(yamlBans.alle());
        List<MuteRecord> mutesVorher = List.copyOf(yamlMutes.alle());
        List<TimeoutRecord> timeoutsVorher = List.copyOf(yamlTimeouts.alle());
        List<FreezeRecord> freezesVorher = List.copyOf(yamlFreezes.alle());
        List<IpBanRecord> ipBansVorher = List.copyOf(yamlIpBans.alle());
        List<IpMuteRecord> ipMutesVorher = List.copyOf(yamlIpMutes.alle());
        List<IpTimeoutRecord> ipTimeoutsVorher = List.copyOf(yamlIpTimeouts.alle());

        int migriert = YamlNachMysqlMigrator.migrieren(daten, plugin, yaml, io, VERZEICHNIS, mysql, 90);
        int erwartet = bansVorher.size() + mutesVorher.size() + timeoutsVorher.size()
                + freezesVorher.size() + ipBansVorher.size() + ipMutesVorher.size()
                + ipTimeoutsVorher.size() + 2 + 1 + 1 + 31;
        assertEquals(erwartet, migriert, "Jeder YAML-Eintrag muss genau einmal übernommen werden");

        BanRepository mysqlBans = new BanRepository(mysql, io);
        mysqlBans.laden();
        assertEquals(bansVorher.size(), List.copyOf(mysqlBans.alle()).size());
        assertTrue(List.copyOf(mysqlBans.alle()).containsAll(bansVorher),
                "Banns müssen den Migrationspfad feldgenau überstehen");

        MuteRepository mysqlMutes = new MuteRepository(mysql, io);
        mysqlMutes.laden();
        assertEquals(mutesVorher, List.copyOf(mysqlMutes.alle()));

        TimeoutRepository mysqlTimeouts = new TimeoutRepository(mysql, io);
        mysqlTimeouts.laden();
        assertEquals(timeoutsVorher, List.copyOf(mysqlTimeouts.alle()));

        FreezeRepository mysqlFreezes = new FreezeRepository(mysql, io);
        mysqlFreezes.laden();
        assertEquals(freezesVorher, List.copyOf(mysqlFreezes.alle()));

        IpBanRepository mysqlIpBans = new IpBanRepository(mysql, io);
        mysqlIpBans.laden();
        assertEquals(ipBansVorher, List.copyOf(mysqlIpBans.alle()));

        IpMuteRepository mysqlIpMutes = new IpMuteRepository(mysql, io);
        mysqlIpMutes.laden();
        assertEquals(ipMutesVorher, List.copyOf(mysqlIpMutes.alle()));

        IpTimeoutRepository mysqlIpTimeouts = new IpTimeoutRepository(mysql, io);
        mysqlIpTimeouts.laden();
        assertEquals(ipTimeoutsVorher, List.copyOf(mysqlIpTimeouts.alle()));

        WarnRepository mysqlWarns = new WarnRepository(mysql, io);
        mysqlWarns.laden();
        assertEquals(yamlWarns.finden(global), mysqlWarns.finden(global));

        NoteRepository mysqlNotizen = new NoteRepository(mysql, io);
        mysqlNotizen.laden();
        assertEquals(yamlNotizen.finden(global), mysqlNotizen.finden(global));

        VerstossRepository mysqlVerstoesse = new VerstossRepository(mysql, io);
        mysqlVerstoesse.laden();
        assertEquals(yamlVerstoesse.finden(global), mysqlVerstoesse.finden(global));

        HistoryRepository mysqlHistory = new HistoryRepository(mysql, io);
        mysqlHistory.laden();
        assertEquals(historyVorher, mysqlHistory.alleSchluessel(),
                "History muss mit IDs, Zeitstempeln und aktiv-Status identisch sein");


        int zweiterLauf = YamlNachMysqlMigrator.migrieren(daten, plugin, yaml, io, VERZEICHNIS, mysql, 90);
        assertEquals(migriert, zweiterLauf, "Wiederholte Migration darf nichts verdoppeln");
        HistoryRepository nachZweitem = new HistoryRepository(mysql, io);
        nachZweitem.laden();
        assertEquals(historyVorher, nachZweitem.alleSchluessel(),
                "Idempotenz: zweiter Lauf darf keine Duplikate erzeugen");
    }

    @Test
    @DisplayName("YAML-History-Archiv (history/archive) wird nach T_HISTORY_ARCHIV migriert")
    void archivWirdMigriert() throws Exception {
        UUID spieler = UUID.fromString("33333333-3333-4333-8333-333333333333");
        HistoryRepository yamlHistory = new HistoryRepository(daten, yaml, io);
        yamlHistory.laden();
        yamlHistory.hinzufuegenNeu(HistoryType.MUTE, "Mod", spieler, "Spieler", "aktiv",
                null, "global", "lobby");
        assertTrue(io.warteAufIdle(30, TimeUnit.SECONDS));

        File archivOrdner = new File(new File(daten, "history"), "archive");
        assertTrue(archivOrdner.mkdirs() || archivOrdner.isDirectory());
        Map<String, Object> archivEintrag = new java.util.LinkedHashMap<>();
        archivEintrag.put("id", 9001L);
        archivEintrag.put("typ", HistoryType.MUTE.name());
        archivEintrag.put("ausfuehrer", "Archiv-Mod");
        archivEintrag.put("ziel", "Spieler");
        archivEintrag.put("grund", "archivierter Eintrag");
        archivEintrag.put("zeitstempel", 1_700_000_000_000L);
        archivEintrag.put("aktiv", true);
        archivEintrag.put("scope", "global");
        archivEintrag.put("herkunft", "lobby");
        Map<String, Object> archivRoot = new java.util.LinkedHashMap<>();
        archivRoot.put("eintraege", List.of(archivEintrag));
        File archivDatei = new File(archivOrdner, spieler + "-archiv-test.yml");
        yaml.speichern(archivRoot, archivDatei);
        assertTrue(archivDatei.isFile(), "Archiv-Fixture muss geschrieben sein");

        int migriert = YamlNachMysqlMigrator.migrieren(daten, plugin, yaml, io, VERZEICHNIS, mysql, 90);
        assertEquals(2, migriert,
                "Ein Live-Eintrag und ein Archiveintrag müssen migriert werden, war: " + migriert);
        assertEquals(1L, zaehle("SELECT COUNT(*) FROM " + MysqlSchema.T_HISTORY_ARCHIV
                        + " WHERE id = 9001 AND schluessel = '" + spieler + "'"),
                "Archiv-Eintrag muss in T_HISTORY_ARCHIV liegen");
    }

    private long zaehle(String sql) {
        try (Connection verbindung = mysql.verbindung();
             Statement statement = verbindung.createStatement();
             java.sql.ResultSet ergebnis = statement.executeQuery(sql)) {
            return ergebnis.next() ? ergebnis.getLong(1) : -1L;
        } catch (Exception e) {
            throw new IllegalStateException("Zählabfrage fehlgeschlagen: " + sql, e);
        }
    }

    private void leeren() {
        try (Connection c = mysql.verbindung(); Statement s = c.createStatement()) {
            for (String tabelle : MysqlSchema.ALLE_TABELLEN) {
                s.execute("DELETE FROM " + tabelle);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Testdaten konnten nicht geleert werden.", e);
        }
    }

    private static String wert(String schluessel, String standard) {
        String wert = System.getProperty(schluessel);
        if (wert == null) {
            wert = System.getenv(schluessel.replace('.', '_').toUpperCase(java.util.Locale.ROOT));
        }
        return wert == null || wert.isBlank() ? standard : wert;
    }

    private static int zahl(String schluessel, int standard) {
        String wert = wert(schluessel, null);
        return wert == null ? standard : Integer.parseInt(wert);
    }
}
