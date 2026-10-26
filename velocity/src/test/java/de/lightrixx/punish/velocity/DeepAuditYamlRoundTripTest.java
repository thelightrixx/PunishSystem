package de.lightrixx.punish.velocity;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tiefen-Audit (M3/M6/C): echter YAML-Rundlauf über den produktiven SnakeYamlSpeicher.
 * Deckt Sonderzeichen, Scope-Varianten, Permanent-Kodierungen und Reihenfolge ab.
 */
class DeepAuditYamlRoundTripTest {

    private static final String TRICKY = "Ümlaut, Emoji \uD83D\uDE00, \"Anführung\", 'Apostroph', "
            + "#hash, | pipe, > gt, & amp, * stern, ! ausruf, {klammer}, [eck], %prozent, "
            + "\\backslash, \ttab, \nzweite Zeile, : doppelpunkt, - strich";

    @TempDir
    Path temp;

    private IoExecutor io;
    private SnakeYamlSpeicher yaml;
    private File ordner;

    @BeforeEach
    void vorbereiten() {
        io = new IoExecutor();
        yaml = new SnakeYamlSpeicher();
        ordner = temp.resolve("daten").toFile();
        assertTrue(ordner.mkdirs() || ordner.isDirectory());
    }

    @AfterEach
    void aufraeumen() {
        if (io != null) {
            io.herunterfahren(10, TimeUnit.SECONDS);
        }
    }

    private void warteAufSchreibende() {
        assertTrue(io.warteAufIdle(10, TimeUnit.SECONDS), "I/O-Queue muss leer laufen");
    }

    @Test
    @DisplayName("Banns überstehen den echten YAML-Rundlauf (Scope, Permanent, Sonderzeichen)")
    void bannsUeberstehenYamlRundlauf() {
        BanRepository quelle = new BanRepository(ordner, yaml, io);
        quelle.laden();

        UUID global = UUID.fromString("11111111-1111-4111-8111-111111111111");
        UUID server = UUID.fromString("22222222-2222-4222-8222-222222222222");
        UUID unbekannt = UUID.fromString("33333333-3333-4333-8333-333333333333");

        assertTrue(quelle.bannenWennFrei(new BanRecord(global, "Globaler", TRICKY, "Mod mit Ü",
                1_700_000_000_000L, null, false, ServerScope.global())));
        assertTrue(quelle.bannenWennFrei(new BanRecord(server, "Server-Spieler", "Grund", "Konsole",
                1_700_000_000_001L, 1_800_000_000_000L, true, ServerScope.server("lobby-1"))));
        assertTrue(quelle.bannenWennFrei(new BanRecord(unbekannt, "Unbekannter", "", "Mod",
                1_700_000_000_002L, Long.MAX_VALUE, false, ServerScope.unbekannt())));
        warteAufSchreibende();

        BanRepository ziel = new BanRepository(ordner, yaml, io);
        ziel.laden();

        assertEquals(List.copyOf(quelle.alle()).size(), List.copyOf(ziel.alle()).size(),
                "Bann-Anzahl muss den Rundlauf überstehen");
        assertTrue(List.copyOf(ziel.alle()).containsAll(List.copyOf(quelle.alle())),
                "Jeder Bann muss feldgenau zurückkommen: " + ziel.alle());
    }

    @Test
    @DisplayName("Mutes/Timeouts/Freezes überstehen den YAML-Rundlauf")
    void mutesTimeoutsFreezesUeberstehenYamlRundlauf() {
        UUID uuid = UUID.fromString("44444444-4444-4444-8444-444444444444");

        MuteRepository mutes = new MuteRepository(ordner, yaml, io);
        mutes.laden();
        MuteRecord mute = new MuteRecord(uuid, "Spieler", TRICKY, "Mod", 1_700_000_000_000L,
                1_800_000_000_000L, ServerScope.server("skyblock"));
        assertTrue(mutes.mutenWennFrei(mute));
        warteAufSchreibende();
        MuteRepository mutesNeu = new MuteRepository(ordner, yaml, io);
        mutesNeu.laden();
        assertEquals(List.copyOf(mutes.alle()), List.copyOf(mutesNeu.alle()));

        TimeoutRepository timeouts = new TimeoutRepository(ordner, yaml, io);
        timeouts.laden();
        TimeoutRecord timeout = new TimeoutRecord(uuid, "Spieler", TRICKY, "Mod", 1_700_000_000_000L,
                1_800_000_000_000L, ServerScope.global());
        assertTrue(timeouts.timeoutVerhaengenWennFrei(timeout));
        warteAufSchreibende();
        TimeoutRepository timeoutsNeu = new TimeoutRepository(ordner, yaml, io);
        timeoutsNeu.laden();
        assertEquals(List.copyOf(timeouts.alle()), List.copyOf(timeoutsNeu.alle()));

        FreezeRepository freezes = new FreezeRepository(ordner, yaml, io);
        freezes.laden();
        FreezeRecord permanent = new FreezeRecord(uuid, "Spieler", TRICKY, "Mod",
                1_700_000_000_000L, Long.MAX_VALUE, ServerScope.unbekannt());
        assertTrue(freezes.freezenWennFrei(permanent));
        warteAufSchreibende();
        FreezeRepository freezesNeu = new FreezeRepository(ordner, yaml, io);
        freezesNeu.laden();
        assertEquals(List.copyOf(freezes.alle()), List.copyOf(freezesNeu.alle()));
    }

    @Test
    @DisplayName("IP-Strafen überstehen den YAML-Rundlauf inklusive IPv6-Kanonisierung")
    void ipStrafenUeberstehenYamlRundlauf() {
        IpBanRepository ipBanns = new IpBanRepository(ordner, yaml, io);
        ipBanns.laden();
        assertTrue(ipBanns.bannenWennFrei(new IpBanRecord("::1", TRICKY, "Mod",
                1_700_000_000_000L, null, ServerScope.global())));
        assertTrue(ipBanns.bannenWennFrei(new IpBanRecord("10.0.0.5", "Grund", "Mod",
                1_700_000_000_001L, 1_800_000_000_000L, ServerScope.server("lobby"))));
        warteAufSchreibende();
        IpBanRepository ipBannsNeu = new IpBanRepository(ordner, yaml, io);
        ipBannsNeu.laden();
        assertEquals(List.copyOf(ipBanns.alle()), List.copyOf(ipBannsNeu.alle()));

        IpMuteRepository ipMutes = new IpMuteRepository(ordner, yaml, io);
        ipMutes.laden();
        assertTrue(ipMutes.mutenWennFrei(new IpMuteRecord("::ffff:10.0.0.9", TRICKY, "Mod",
                1_700_000_000_000L, 1_800_000_000_000L, ServerScope.server("lobby"))));
        warteAufSchreibende();
        IpMuteRepository ipMutesNeu = new IpMuteRepository(ordner, yaml, io);
        ipMutesNeu.laden();
        assertEquals(List.copyOf(ipMutes.alle()), List.copyOf(ipMutesNeu.alle()));

        IpTimeoutRepository ipTimeouts = new IpTimeoutRepository(ordner, yaml, io);
        ipTimeouts.laden();
        assertTrue(ipTimeouts.timeoutVerhaengenWennFrei(new IpTimeoutRecord("192.168.1.1", TRICKY,
                "Mod", 1_700_000_000_000L, 1_800_000_000_000L, ServerScope.global())));
        warteAufSchreibende();
        IpTimeoutRepository ipTimeoutsNeu = new IpTimeoutRepository(ordner, yaml, io);
        ipTimeoutsNeu.laden();
        assertEquals(List.copyOf(ipTimeouts.alle()), List.copyOf(ipTimeoutsNeu.alle()));
    }

    @Test
    @DisplayName("Warns/Notizen/Verstöße überstehen den YAML-Rundlauf mit Nummern und Stufen")
    void warnsNotizenVerstoesseUeberstehenYamlRundlauf() {
        UUID uuid = UUID.fromString("55555555-5555-4555-8555-555555555555");

        WarnRepository warns = new WarnRepository(ordner, yaml, io);
        warns.laden();
        warns.hinzufuegen(uuid, "Spieler", TRICKY, "Mod");
        warns.hinzufuegen(uuid, "Spieler", "zweiter", "Mod2");
        warteAufSchreibende();
        WarnRepository warnsNeu = new WarnRepository(ordner, yaml, io);
        warnsNeu.laden();
        assertEquals(warns.finden(uuid), warnsNeu.finden(uuid));

        NoteRepository notizen = new NoteRepository(ordner, yaml, io);
        notizen.laden();
        notizen.hinzufuegen(uuid, "Spieler", TRICKY, "Mod");
        notizen.hinzufuegen(uuid, "Spieler", "", "Mod");
        warteAufSchreibende();
        NoteRepository notizenNeu = new NoteRepository(ordner, yaml, io);
        notizenNeu.laden();
        assertEquals(notizen.finden(uuid), notizenNeu.finden(uuid));

        VerstossRepository verstoesse = new VerstossRepository(ordner, yaml, io);
        verstoesse.laden();
        verstoesse.hinzufuegen(uuid, "Spieler", TRICKY, "Mod", VerstossStufe.HIGH);
        verstoesse.hinzufuegen(uuid, "Spieler", "leicht", "Mod", VerstossStufe.LOW);
        warteAufSchreibende();
        VerstossRepository verstoesseNeu = new VerstossRepository(ordner, yaml, io);
        verstoesseNeu.laden();
        assertEquals(verstoesse.finden(uuid), verstoesseNeu.finden(uuid));
    }

    @Test
    @DisplayName("History übersteht den YAML-Rundlauf inklusive IDs, Aufhebungsstatus und Reihenfolge")
    void historyUeberstehtYamlRundlauf() {
        UUID uuid = UUID.fromString("66666666-6666-4666-8666-666666666666");
        HistoryRepository quelle = new HistoryRepository(ordner, yaml, io);
        quelle.laden();

        quelle.hinzufuegenNeu(HistoryType.BAN, "Mod", uuid, "Spieler", TRICKY,
                1_800_000_000_000L, "global", "lobby");
        quelle.hinzufuegenNeu(HistoryType.PARDON, "Admin", uuid, "Spieler", "Grund",
                null, "global", "lobby");
        for (int i = 0; i < 25; i++) {
            quelle.hinzufuegenNeu(HistoryType.MUTE, "Mod" + i, uuid, "Spieler", "Mute " + i,
                    null, "unbekannt", "lobby");
        }
        warteAufSchreibende();

        List<HistoryEntry> vorher = quelle.finden(uuid, 100);
        assertEquals(27, vorher.size());

        HistoryRepository ziel = new HistoryRepository(ordner, yaml, io);
        ziel.laden();
        assertEquals(vorher, ziel.finden(uuid, 100),
                "History muss feldgenau, inklusive aktiv-Status und IDs, zurückkommen");
    }
}
