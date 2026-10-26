package de.lightrixx.punish.storage.mysql;

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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tiefen-Audit (M6/M6a/M5): prüft alle Repositories gegen eine echte MariaDB/MySQL.
 * Läuft nur mit gesetzter Verbindung, sonst sauber übersprungen:
 * -Dpunish.it.host=127.0.0.1 -Dpunish.it.port=33070 -Dpunish.it.db=punish_it
 * -Dpunish.it.user=punish_it -Dpunish.it.password=...
 */
@EnabledIf("datenbankKonfiguriert")
class MysqlEchtIntegrationTest {

    private static final String TRICKY = "Ümlaut, Emoji \uD83D\uDE00, \"Anführung\", 'Apostroph', "
            + "#hash, | pipe, > gt, & amp, * stern, ! ausruf, \\backslash, \ttab, \nzweite Zeile, : dop, - strich";

    private static MysqlKonfiguration konfiguration;
    private static MysqlVerbindung mysql;
    private static IoExecutor io;

    static boolean datenbankKonfiguriert() {
        return wert("punish.it.db", null) != null && wert("punish.it.user", null) != null;
    }

    @BeforeAll
    static void vorbereiten() throws Exception {
        String host = wert("punish.it.host", "127.0.0.1");
        String db = wert("punish.it.db", null);
        String user = wert("punish.it.user", null);
        String passwort = wert("punish.it.password", "");
        konfiguration = new MysqlKonfiguration(host, zahl("punish.it.port", 3306), db, user, passwort, false, 5);
        mysql = MysqlVerbindung.oeffnen(konfiguration);
        io = new IoExecutor();
        MysqlSchema.initialisieren(mysql);
        leeren(mysql);
    }

    @AfterAll
    static void aufraeumen() {
        if (io != null) {
            io.herunterfahren(10, TimeUnit.SECONDS);
        }
        if (mysql != null) {
            mysql.schliessen();
        }
    }

    @BeforeEach
    void tabellenLeeren() {
        leeren(mysql);
    }

    @Test
    @DisplayName("Schema-Anlage ist idempotent und setzt die Schema-Version")
    void schemaIstIdempotent() {
        MysqlSchema.initialisieren(mysql);
        MysqlSchema.initialisieren(mysql);
        assertTrue(MysqlSchema.istLeer(mysql), "Frische Schema-Anlage ohne Strafen gilt als leer");
        assertEquals("2", metaWert("schema_version"));
    }

    @Test
    @DisplayName("Alle Strafarten überstehen den echten MySQL-Rundlauf feldgenau")
    void alleStrafartenRundlauf() throws Exception {
        UUID global = UUID.fromString("11111111-1111-4111-8111-111111111111");
        UUID server = UUID.fromString("22222222-2222-4222-8222-222222222222");
        UUID unbekannt = UUID.fromString("33333333-3333-4333-8333-333333333333");
        UUID ip = UUID.fromString("44444444-4444-4444-8444-444444444444");

        BanRepository banns = new BanRepository(mysql, io);
        banns.laden();
        assertTrue(banns.bannenWennFrei(new BanRecord(global, "Globaler", TRICKY, "Mod Ü",
                1_700_000_000_000L, null, false, ServerScope.global())));
        assertTrue(banns.bannenWennFrei(new BanRecord(server, "Server-Spieler", "Grund", "Konsole",
                1_700_000_000_001L, 1_800_000_000_000L, true, ServerScope.server("lobby-1"))));
        assertTrue(banns.bannenWennFrei(new BanRecord(unbekannt, "Unbekannter", "",
                "Mod", 1_700_000_000_002L, Long.MAX_VALUE, false, ServerScope.unbekannt())));

        MuteRepository mutes = new MuteRepository(mysql, io);
        mutes.laden();
        assertTrue(mutes.mutenWennFrei(new MuteRecord(server, "Spieler", TRICKY, "Mod",
                1_700_000_000_000L, 1_800_000_000_000L, ServerScope.server("skyblock"))));

        TimeoutRepository timeouts = new TimeoutRepository(mysql, io);
        timeouts.laden();
        assertTrue(timeouts.timeoutVerhaengenWennFrei(new TimeoutRecord(global, "Spieler", TRICKY, "Mod",
                1_700_000_000_000L, 1_800_000_000_000L, ServerScope.global())));

        FreezeRepository freezes = new FreezeRepository(mysql, io);
        freezes.laden();
        assertTrue(freezes.freezenWennFrei(new FreezeRecord(unbekannt, "Spieler", TRICKY, "Mod",
                1_700_000_000_000L, Long.MAX_VALUE, ServerScope.unbekannt())));

        IpBanRepository ipBanns = new IpBanRepository(mysql, io);
        ipBanns.laden();
        assertTrue(ipBanns.bannenWennFrei(new IpBanRecord("::1", TRICKY, "Mod",
                1_700_000_000_000L, null, ServerScope.global())));
        assertTrue(ipBanns.bannenWennFrei(new IpBanRecord("10.0.0.5", "Grund", "Mod",
                1_700_000_000_001L, 1_800_000_000_000L, ServerScope.server("lobby"))));

        IpMuteRepository ipMutes = new IpMuteRepository(mysql, io);
        ipMutes.laden();
        assertTrue(ipMutes.mutenWennFrei(new IpMuteRecord("::ffff:10.0.0.9", TRICKY, "Mod",
                1_700_000_000_000L, 1_800_000_000_000L, ServerScope.server("lobby"))));

        IpTimeoutRepository ipTimeouts = new IpTimeoutRepository(mysql, io);
        ipTimeouts.laden();
        assertTrue(ipTimeouts.timeoutVerhaengenWennFrei(new IpTimeoutRecord("192.168.1.1", TRICKY,
                "Mod", 1_700_000_000_000L, 1_800_000_000_000L, ServerScope.global())));

        WarnRepository warns = new WarnRepository(mysql, io);
        warns.laden();
        warns.hinzufuegen(ip, "Spieler", TRICKY, "Mod");
        warns.hinzufuegen(ip, "Spieler", "zweiter", "Mod2");

        NoteRepository notizen = new NoteRepository(mysql, io);
        notizen.laden();
        notizen.hinzufuegen(ip, "Spieler", TRICKY, "Mod");
        notizen.hinzufuegen(ip, "Spieler", "", "Mod");

        VerstossRepository verstoesse = new VerstossRepository(mysql, io);
        verstoesse.laden();
        verstoesse.hinzufuegen(ip, "Spieler", TRICKY, "Mod", VerstossStufe.HIGH);
        verstoesse.hinzufuegen(ip, "Spieler", "leicht", "Mod", VerstossStufe.LOW);

        HistoryRepository history = new HistoryRepository(mysql, io);
        history.laden();
        history.hinzufuegenNeu(HistoryType.BAN, "Mod", global, "Spieler", TRICKY,
                1_800_000_000_000L, "global", "lobby");
        history.hinzufuegenNeu(HistoryType.PARDON, "Admin", global, "Spieler", "Grund",
                null, "global", "lobby");
        for (int i = 0; i < 25; i++) {
            history.hinzufuegenNeu(HistoryType.MUTE, "Mod" + i, global, "Spieler", "Mute " + i,
                    null, "unbekannt", "lobby");
        }

        assertTrue(io.warteAufIdle(30, TimeUnit.SECONDS), "Schreib-Queue muss leer laufen");

        MysqlVerbindung zweite = MysqlVerbindung.oeffnen(konfiguration);
        IoExecutor io2 = neuerIo();
        try {
            BanRepository banns2 = new BanRepository(zweite, io2);
            banns2.laden();
            assertEquals(List.copyOf(banns.alle()).size(), List.copyOf(banns2.alle()).size());
            assertTrue(List.copyOf(banns2.alle()).containsAll(List.copyOf(banns.alle())),
                    "Banns müssen feldgenau zurückkommen: " + banns2.alle());

            MuteRepository mutes2 = new MuteRepository(zweite, io2);
            mutes2.laden();
            assertEquals(List.copyOf(mutes.alle()), List.copyOf(mutes2.alle()));
            assertEquals(TRICKY, mutes2.alle().iterator().next().grund(), "Sonderzeichen im Grund");

            TimeoutRepository timeouts2 = new TimeoutRepository(zweite, io2);
            timeouts2.laden();
            assertEquals(List.copyOf(timeouts.alle()), List.copyOf(timeouts2.alle()));

            FreezeRepository freezes2 = new FreezeRepository(zweite, io2);
            freezes2.laden();
            assertEquals(List.copyOf(freezes.alle()), List.copyOf(freezes2.alle()));

            IpBanRepository ipBanns2 = new IpBanRepository(zweite, io2);
            ipBanns2.laden();
            assertEquals(List.copyOf(ipBanns.alle()), List.copyOf(ipBanns2.alle()),
                    "IPv6-Kanonisierung muss den Rundlauf überstehen");

            IpMuteRepository ipMutes2 = new IpMuteRepository(zweite, io2);
            ipMutes2.laden();
            assertEquals(List.copyOf(ipMutes.alle()), List.copyOf(ipMutes2.alle()));

            IpTimeoutRepository ipTimeouts2 = new IpTimeoutRepository(zweite, io2);
            ipTimeouts2.laden();
            assertEquals(List.copyOf(ipTimeouts.alle()), List.copyOf(ipTimeouts2.alle()));

            WarnRepository warns2 = new WarnRepository(zweite, io2);
            warns2.laden();
            assertEquals(warns.finden(ip), warns2.finden(ip));

            NoteRepository notizen2 = new NoteRepository(zweite, io2);
            notizen2.laden();
            assertEquals(notizen.finden(ip), notizen2.finden(ip));

            VerstossRepository verstoesse2 = new VerstossRepository(zweite, io2);
            verstoesse2.laden();
            assertEquals(verstoesse.finden(ip), verstoesse2.finden(ip));

            HistoryRepository history2 = new HistoryRepository(zweite, io2);
            history2.laden();
            List<HistoryEntry> vorher = history.finden(global, 100);
            assertEquals(27, vorher.size());
            assertEquals(vorher, history2.finden(global, 100),
                    "History muss feldgenau inklusive IDs und aktiv-Status zurückkommen");
        } finally {
            io2.herunterfahren(10, TimeUnit.SECONDS);
            zweite.schliessen();
        }
    }

    @Test
    @DisplayName("Repositories verweigern bei fehlendem CREATE-Recht den Start (fail-closed)")
    void fehlendeDdlRechteVerweigernStart() throws Exception {
        String user = wert("punish.it.noddl.user", null);
        Assumptions.assumeTrue(user != null, "Kein Testbenutzer ohne DDL-Rechte konfiguriert.");
        MysqlKonfiguration ohneDdl = new MysqlKonfiguration(konfiguration.host(), konfiguration.port(),
                konfiguration.datenbank(), user, wert("punish.it.noddl.password", ""), false, 2);
        try (MysqlVerbindung verbindung = MysqlVerbindung.oeffnen(ohneDdl)) {
            IllegalStateException fehler = assertThrows(IllegalStateException.class,
                    () -> MysqlSchema.initialisieren(verbindung));
            assertTrue(fehler.getMessage().contains("Schema"),
                    "Meldung muss das Schema nennen: " + fehler.getMessage());
        }
    }

    @Test
    @DisplayName("Verbindungsabbruch führt zu fail-closed statt leerem Strafbestand")
    void verbindungsabbruchVerliertKeineStrafen() throws Exception {
        UUID uuid = UUID.fromString("55555555-5555-4555-8555-555555555555");
        BanRepository banns = new BanRepository(mysql, io);
        banns.laden();
        assertTrue(banns.bannenWennFrei(new BanRecord(uuid, "Spieler", "Grund", "Mod",
                1_700_000_000_000L, null, false, ServerScope.global())));
        assertTrue(io.warteAufIdle(10, TimeUnit.SECONDS));

        alleVerbindungenTrennen();
        boolean wiederDa = false;
        for (int i = 0; i < 20 && !wiederDa; i++) {
            wiederDa = mysql.ping();
            if (!wiederDa) {
                Thread.sleep(250);
            }
        }
        assertTrue(wiederDa, "Der Pool muss nach Verbindungsabbruch selbstständig neue Verbindungen aufbauen");
        try {
            banns.laden();
            assertEquals(1, banns.alle().size(),
                    "Nach Verbindungsabbruch darf der Bestand nicht leer werden");
        } catch (IllegalStateException erwartet) {
            assertEquals(1, banns.alle().size(),
                    "Fail-Closed: alter Bestand bleibt erhalten (" + erwartet.getMessage() + ")");
        }
    }

    @Test
    @DisplayName("Zwei Instanzen verlieren bei gleichzeitigen Einzeländerungen keine Strafen")
    void zweiInstanzenGleichzeitigeEinzelAenderungen() throws Exception {
        int anzahl = 40;
        List<UUID> uuids = new ArrayList<>();
        for (int i = 0; i < anzahl; i++) {
            uuids.add(UUID.nameUUIDFromBytes(("deepaudit-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        MysqlVerbindung poolA = MysqlVerbindung.oeffnen(konfiguration);
        MysqlVerbindung poolB = MysqlVerbindung.oeffnen(konfiguration);
        IoExecutor ioA = neuerIo();
        IoExecutor ioB = neuerIo();
        try {
            BanRepository a = new BanRepository(poolA, ioA);
            BanRepository b = new BanRepository(poolB, ioB);
            a.laden();
            b.laden();
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> fehler = new AtomicReference<>();
            Thread links = new Thread(() -> schreibe(a, uuids.subList(0, anzahl / 2), start, fehler), "links");
            Thread rechts = new Thread(() -> schreibe(b, uuids.subList(anzahl / 2, anzahl), start, fehler), "rechts");
            links.start();
            rechts.start();
            start.countDown();
            links.join(30_000);
            rechts.join(30_000);
            assertTrue(ioA.warteAufIdle(30, TimeUnit.SECONDS));
            assertTrue(ioB.warteAufIdle(30, TimeUnit.SECONDS));
            assertEquals(null, fehler.get(), "Kein Schreibfehler erwartet");

            BanRepository pruefung = new BanRepository(mysql, io);
            pruefung.laden();
            assertEquals(anzahl, pruefung.alle().size(),
                    "Beide Instanzen müssen alle Banns in der Datenbank sehen");
        } finally {
            ioA.herunterfahren(10, TimeUnit.SECONDS);
            ioB.herunterfahren(10, TimeUnit.SECONDS);
            poolA.schliessen();
            poolB.schliessen();
        }
    }

    private static void schreibe(BanRepository repository, List<UUID> ids, CountDownLatch start,
                                 AtomicReference<Throwable> fehler) {
        try {
            start.await(10, TimeUnit.SECONDS);
            for (UUID id : ids) {
                repository.bannenWennFrei(new BanRecord(id, "Spieler", "Grund", "Mod",
                        1_700_000_000_000L, null, false, ServerScope.global()));
            }
        } catch (Throwable t) {
            fehler.compareAndSet(null, t);
        }
    }

    private static IoExecutor neuerIo() {
        return new IoExecutor();
    }

    private static void alleVerbindungenTrennen() throws Exception {
        try (Connection admin = DriverManager.getConnection(
                "jdbc:mysql://" + konfiguration.host() + ":" + konfiguration.port() + "/"
                        + konfiguration.datenbank() + "?useSSL=false&allowPublicKeyRetrieval=true",
                konfiguration.benutzer(), konfiguration.passwort());
             Statement statement = admin.createStatement();
             ResultSet ergebnis = statement.executeQuery(
                     "SELECT id FROM information_schema.processlist WHERE user = '"
                             + konfiguration.benutzer().replace("'", "''") + "' AND id <> CONNECTION_ID()")) {
            List<Long> ids = new ArrayList<>();
            while (ergebnis.next()) {
                ids.add(ergebnis.getLong(1));
            }
            try (Statement kill = admin.createStatement()) {
                for (Long id : ids) {
                    kill.execute("KILL " + id);
                }
            }
            assertTrue(ids.size() > 0, "Es müssen Pool-Verbindungen zum Trennen existieren");
        }
    }

    private static void leeren(MysqlVerbindung verbindung) {
        Exception letzter = null;
        for (int versuch = 0; versuch < 5; versuch++) {
            try (Connection c = verbindung.verbindung(); Statement s = c.createStatement()) {
                for (String tabelle : MysqlSchema.ALLE_TABELLEN) {
                    s.execute("DELETE FROM " + tabelle);
                }
                return;
            } catch (Exception e) {
                letzter = e;
                try {
                    Thread.sleep(300);
                } catch (InterruptedException unterbrochen) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new IllegalStateException("Testdaten konnten nicht geleert werden.", letzter);
    }

    private static String metaWert(String schluessel) {
        try (Connection c = mysql.verbindung();
             java.sql.PreparedStatement p = c.prepareStatement(
                     "SELECT wert FROM " + MysqlSchema.T_META + " WHERE schluessel = ?")) {
            p.setString(1, schluessel);
            try (ResultSet r = p.executeQuery()) {
                return r.next() ? r.getString(1) : null;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Meta-Wert nicht lesbar.", e);
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
