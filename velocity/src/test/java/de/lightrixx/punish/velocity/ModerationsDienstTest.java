package de.lightrixx.punish.velocity;

import de.lightrixx.punish.model.HistoryEntry;
import de.lightrixx.punish.model.HistoryType;
import de.lightrixx.punish.model.ReportRecord;
import de.lightrixx.punish.model.ReportStatus;
import de.lightrixx.punish.net.Protokoll;
import de.lightrixx.punish.storage.IoExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModerationsDienstTest {
    @TempDir File ordner;
    IoExecutor io;
    VelocityKonfiguration config;
    ModerationsDienst dienst;
    UUID uuid = UUID.randomUUID();
    AtomicLong jetzt = new AtomicLong(2_000_000_000_000L);

    @BeforeEach
    void vorbereiten() {
        io = new IoExecutor();
        config = mock(VelocityKonfiguration.class);
        when(config.chatfilterPruefungAufbewahrungTage()).thenReturn(7);
        when(config.chatfilterKlartextUbertragen()).thenReturn(true);
        dienst = neuerDienst();
        dienst.laden();
    }

    ModerationsDienst neuerDienst() {
        return new ModerationsDienst(new ModerationsSpeicher(ordner, new SnakeYamlSpeicher(), io, null, "faelle"),
                new ModerationsSpeicher(ordner, new SnakeYamlSpeicher(false), io, null, "chatfilter-pruefung"), config, jetzt::get);
    }

    @AfterEach
    void beenden() {
        assertTrue(io.herunterfahren(5, TimeUnit.SECONDS));
    }

    Protokoll.ChatfilterTreffer treffer(boolean beobachtung) {
        return new Protokoll.ChatfilterTreffer(uuid, "Spieler", "CHAT", "KI", .9,
                "b".repeat(64), "Prueftext mit Umlaut ä", jetzt.get(), beobachtung);
    }

    ModerationsDienst.Ereignis history(long id) {
        return new ModerationsDienst.Ereignis(uuid, new HistoryEntry(id, HistoryType.WARN_ADD,
                "Chatfilter", "Spieler", "Filter", jetzt.get(), null, true, "global", "lobby"));
    }

    @Test
    void fallSpeichertUnicodeNotizenBeweiseUndStrafverweiseDauerhaft() {
        var fall = dienst.erstellen(uuid, "Spieler", "Beleidigung prüfen", "Mod");
        dienst.eintrag(fall.id(), "Mod", "NOTIZ", 0, "Zeuge wurde befragt.");
        dienst.eintrag(fall.id(), "Mod", "BEWEIS", 0, ModerationsDienst.beweisLink("https://example.org/beleg"));
        dienst.historyVerknuepfen(fall.id(), history(41));
        dienst.schliessen(fall.id(), true, "Mod");
        assertThrows(IllegalStateException.class, () -> dienst.eintrag(fall.id(), "Mod", "NOTIZ", 0, "Gesperrt"));
        assertTrue(io.warteAufIdle(5, TimeUnit.SECONDS));
        var geladen = neuerDienst();
        geladen.laden();
        assertEquals(dienst.fall(fall.id()), geladen.fall(fall.id()));
        assertEquals(5, geladen.fall(fall.id()).eintraege().size());
        assertTrue(geladen.fall(fall.id()).geschlossen());
    }

    @Test
    void fremdeSpielerUndDoppelteHistoryVerweiseWerdenAbgefangen() {
        var fall = dienst.erstellen(UUID.randomUUID(), "Anderer", "Prüfung", "Mod");
        assertThrows(IllegalArgumentException.class, () -> dienst.historyVerknuepfen(fall.id(), history(1)));
        var eigener = dienst.erstellen(uuid, "Spieler", "Prüfung", "Mod");
        dienst.historyVerknuepfen(eigener.id(), history(1));
        dienst.historyVerknuepfen(eigener.id(), history(1));
        assertEquals(2, dienst.fall(eigener.id()).eintraege().size());
    }

    @Test
    void reportErstelltFallUndStatusaenderungBleibtImSelbenFallOhneStrafstufe() {
        var report = new ReportRecord(7, uuid, "Spieler", UUID.randomUUID(), "Melder", "Spam", "lobby",
                jetzt.get(), ReportStatus.OFFEN, "", 0, "");
        dienst.report(report);
        dienst.report(report.mitStatus(ReportStatus.ANGENOMMEN, "Mod", "Prüfe"));
        assertEquals(1, dienst.faelle(uuid).size());
        assertEquals(3, dienst.faelle(uuid).getFirst().eintraege().size());
        assertEquals(0, dienst.anzahlRegel(uuid, "spam", 30L * 86_400_000L));
    }

    @Test
    void katalogZaehltNurErfolgreicheAnwendungenJeRegelUndAccountImZeitfenster() {
        var regel = new Strafkatalog.Regel("spam", "Spam", 30L * 86_400_000L,
                List.of(new Strafkatalog.Stufe("warn", "", 0)));
        assertEquals(0, dienst.anzahlRegel(uuid, "spam", regel.fensterMillis()));
        var fall = dienst.katalogAngewendet(uuid, "Spieler", regel, 1, 0, "Mod", List.of(history(11)));
        dienst.schliessen(fall.id(), true, "Mod");
        assertEquals(1, dienst.anzahlRegel(uuid, "spam", regel.fensterMillis()));
        assertEquals(0, dienst.anzahlRegel(uuid, "cheating", regel.fensterMillis()));
        assertEquals(0, dienst.anzahlRegel(UUID.randomUUID(), "spam", regel.fensterMillis()));
        jetzt.addAndGet(31L * 86_400_000L);
        assertEquals(0, dienst.anzahlRegel(uuid, "spam", regel.fensterMillis()));
        assertEquals(1, dienst.faelle(uuid).size());
    }

    @Test
    void prueftexteWerdenNachSiebenTagenAuchAufDatentraegerEntferntUndIdsNichtWiederverwendet() throws Exception {
        var p = dienst.treffer(uuid, "Spieler", treffer(false));
        assertTrue(io.warteAufIdle(5, TimeUnit.SECONDS));
        var pfad = ordner.toPath().resolve("chatfilter-pruefung.yml");
        assertTrue(Files.readString(pfad).contains("Prueftext"));
        jetzt.addAndGet(7L * 86_400_000L + 1);
        dienst.wartung();
        assertTrue(io.warteAufIdle(5, TimeUnit.SECONDS));
        assertFalse(Files.readString(pfad).contains("Prueftext"));
        try (var dateien = Files.list(ordner.toPath())) {
            assertFalse(dateien.anyMatch(f -> f.getFileName().toString().startsWith("chatfilter-pruefung.yml.bak")));
        }
        var neu = neuerDienst();
        neu.laden();
        assertTrue(neu.pruefungen(uuid).isEmpty());
        assertTrue(neu.treffer(uuid, "Spieler", treffer(false)).id() > p.id());
    }

    @Test
    void hashOnlySpeichertNiemalsDenUebergebenenKlartext() throws Exception {
        when(config.chatfilterKlartextUbertragen()).thenReturn(false);
        var p = dienst.treffer(uuid, "Spieler", treffer(false));
        assertEquals("", p.text());
        assertTrue(io.warteAufIdle(5, TimeUnit.SECONDS));
        assertFalse(Files.readString(ordner.toPath().resolve("chatfilter-pruefung.yml")).contains("Prueftext"));
    }

    @Test
    void korrekturErfordertFehlalarmUndVollstaendigVorhandeneBelege() {
        var p1 = dienst.treffer(uuid, "Spieler", treffer(false));
        var p2 = dienst.treffer(uuid, "Spieler", treffer(false));
        dienst.aktion(p2.id(), 2, 3_600_000L, List.of(p1.id(), p2.id()),
                Map.of("warn-nummer", 1, "erstellt", jetzt.get()), List.of(history(4)));
        var mitAktion = dienst.pruefung(p2.id());
        var aktion = mitAktion.aktionen().getFirst();
        assertFalse(dienst.korrekturNoetig(mitAktion, aktion));
        dienst.pruefen(p1.id(), ModerationsDienst.PruefStatus.FEHLALARM, "Mod");
        assertTrue(dienst.korrekturNoetig(mitAktion, aktion));
        assertThrows(IllegalArgumentException.class,
                () -> dienst.pruefen(p1.id(), ModerationsDienst.PruefStatus.BERECHTIGT, "Mod"));
        jetzt.addAndGet(8L * 86_400_000L);
        dienst.wartung();
        assertFalse(dienst.korrekturNoetig(mitAktion, aktion));
    }

    @Test
    void beobachtungDerBridgeBleibtNachProxyUmschaltungErhalten() {
        when(config.chatfilterBeobachten()).thenReturn(false);
        assertTrue(dienst.treffer(uuid, "Spieler", treffer(true)).beobachtung());
    }

    @Test
    void beweisLinksAkzeptierenNurHttpsOhneZugangsdatenUndYamlLehntObjekttagsAb() {
        assertThrows(IllegalArgumentException.class, () -> ModerationsDienst.beweisLink("javascript:alert(1)"));
        assertThrows(IllegalArgumentException.class, () -> ModerationsDienst.beweisLink("https://user:pass@example.org/test"));
        assertThrows(RuntimeException.class, () -> ModerationsSpeicher.dekodiere("!!java.net.URL ['https://example.org']"));
    }

    @Test
    void hashOnlyUmschaltungEntferntAuchSchonGespeichertePrueftexte() throws Exception {
        var p = dienst.treffer(uuid, "Spieler", treffer(false));
        assertTrue(io.warteAufIdle(5, TimeUnit.SECONDS));
        when(config.chatfilterKlartextUbertragen()).thenReturn(false);
        dienst.wartung();
        assertEquals("", dienst.pruefung(p.id()).text());
        assertTrue(io.warteAufIdle(5, TimeUnit.SECONDS));
        assertFalse(Files.readString(ordner.toPath().resolve("chatfilter-pruefung.yml")).contains("Prueftext"));
    }

    @Test
    void korrekturWirdAuchImGeschlossenenFallNachvollziehbarErgaenzt() {
        var f = dienst.erstellen(uuid, "Spieler", "Prüfung", "Mod");
        dienst.historyVerknuepfen(f.id(), history(11));
        dienst.schliessen(f.id(), true, "Mod");
        dienst.korrekturVerknuepfen(uuid, 11, List.of(new ModerationsDienst.Ereignis(uuid,
                new HistoryEntry(12, HistoryType.WARN_REMOVE, "Mod", "Spieler", "Fehlalarm", jetzt.get(), null, true))));
        assertTrue(dienst.fall(f.id()).geschlossen());
        assertEquals("KORREKTUR", dienst.fall(f.id()).eintraege().getLast().typ());
        assertEquals(12, dienst.fall(f.id()).eintraege().getLast().referenz());
    }
}
