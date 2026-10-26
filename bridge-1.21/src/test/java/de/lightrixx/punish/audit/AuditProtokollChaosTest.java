package de.lightrixx.punish.audit;

import de.lightrixx.punish.bridge.BanCache;
import de.lightrixx.punish.bridge.FreezeCache;
import de.lightrixx.punish.bridge.MuteCache;
import de.lightrixx.punish.bridge.gui.GuiSitzung;
import de.lightrixx.punish.command.ScopeParser;
import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.net.Protokoll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditProtokollChaosTest {

    private static final long JETZT = System.currentTimeMillis();

    @Test
    @DisplayName("S3: MUTE_SYNC-Frame → Cache-Entscheidung; Ablauf hebt Chat-Sperre ohne Reconnect auf")
    void szenario3MuteKetteUeberFrame() throws Exception {
        MuteCache cache = new MuteCache();
        UUID spieler = UUID.randomUUID();
        Protokoll.MuteEintrag aktiv = new Protokoll.MuteEintrag(spieler, "Spamer", "Spam", "ModA",
                JETZT, JETZT + 60_000L, "lobby");

        byte[] frame = Protokoll.muteSync(List.of(aktiv));
        Protokoll.Paket paket = Protokoll.paket(frame);
        assertEquals(Protokoll.MUTE_SYNC, paket.typ());
        List<Protokoll.MuteEintrag> empfangen = Protokoll.leseMuteSync(paket.nutzdaten());
        cache.ersetzeAlle(empfangen);

        assertTrue(cache.findeAktiv(spieler, "lobby", JETZT).isPresent(),
                "Bridge-Cache sperrt Chat auf lobby");
        assertTrue(cache.findeAktiv(spieler, "survival", JETZT).isEmpty(),
                "Scope-Mute gilt nicht auf survival");

        assertTrue(cache.findeAktiv(spieler, "lobby", JETZT + 120_000L).isEmpty(),
                "Chat muss nach Ablauf ohne Reconnect wieder frei sein");

        cache.setze(aktiv);
        byte[] entfernFrame = Protokoll.nachricht(Protokoll.MUTE_ENTFERNEN,
                out -> {
                    out.writeInt(1);
                    Protokoll.schreibeUuid(out, spieler);
                });
        Protokoll.Paket entfernPaket = Protokoll.paket(entfernFrame);
        List<UUID> uuids = Protokoll.leseUuidListe(entfernPaket.nutzdaten());
        cache.entferne(uuids);
        assertTrue(cache.findeAktiv(spieler, "lobby", JETZT).isEmpty(), "Nach MUTE_ENTFERNEN frei");
    }

    @Test
    @DisplayName("S4: Freeze mit Scope server:a wirkt nach Serverwechsel nach b nicht mehr")
    void szenario4FreezeScopeWechsel() {
        FreezeCache cache = new FreezeCache();
        UUID spieler = UUID.randomUUID();
        cache.setze(new Protokoll.FreezeEintrag(spieler, "Ziel", "Prüfung", "ModB",
                JETZT, Long.MAX_VALUE, "a"));

        assertTrue(cache.findeAktiv(spieler, "a", JETZT).isPresent(), "Auf a eingefroren");
        assertTrue(cache.findeAktiv(spieler, "b", JETZT).isEmpty(),
                "Nach Serverwechsel nach b ist die Freeze-Wirkung aufgehoben (Scope server)");
        assertTrue(cache.findeAktiv(spieler, "unbekannt", JETZT).isPresent(),
                "Unbekannter Server gilt als betroffen (fail-closed, wie ServerScope.giltFuer)");
    }

    @Test
    @DisplayName("S8: SYNC_END ohne SYNC_BEGIN räumt den Cache nicht (Generationen-Schutz)")
    void szenario8SyncEndeOhneBeginn() {
        MuteCache cache = new MuteCache();
        cache.setze(new Protokoll.MuteEintrag(UUID.randomUUID(), "N", "G", "M",
                JETZT, JETZT + 60_000L, "global"));
        assertEquals(1, cache.groesse());

        cache.syncEnde(999L);
        assertEquals(1, cache.groesse(), "SYNC_END ohne passendes SYNC_BEGIN muss wirkungslos bleiben");
    }

    @Test
    @DisplayName("S8: MUTE_SETZEN während laufendem Sync wird gestaged und bei SYNC_END übernommen")
    void szenario8SetzenWaehrendSync() {
        MuteCache cache = new MuteCache();
        UUID spieler = UUID.randomUUID();
        cache.syncBeginn(1L);
        cache.setze(new Protokoll.MuteEintrag(spieler, "N", "G", "M",
                JETZT, JETZT + 60_000L, "global"));
        assertTrue(cache.findeAktiv(spieler, "global", JETZT).isEmpty(),
                "Während des Syncs zählt nur das Staging (alter Stand bleibt sichtbar)");
        cache.syncEnde(1L);
        assertTrue(cache.findeAktiv(spieler, "global", JETZT).isPresent(),
                "SYNC_END übernimmt gestagte Einträge");
    }

    @Test
    @DisplayName("S8: Doppeltes SYNC_BEGIN verwirft gestagte Einträge der abgebrochenen Generation (dokumentiert)")
    void szenario8DoppeltesSyncBeginn() {
        MuteCache cache = new MuteCache();
        UUID spieler = UUID.randomUUID();
        cache.syncBeginn(1L);
        cache.setze(new Protokoll.MuteEintrag(spieler, "N", "G", "M",
                JETZT, JETZT + 60_000L, "global"));
        cache.syncBeginn(2L);
        cache.syncEnde(2L);
        assertTrue(cache.findeAktiv(spieler, "global", JETZT).isEmpty(),
                "Erwartet/dokumentiert: Einträge der abgebrochenen Generation gehen verloren; "
                        + "der nächste Voll-Sync des Proxys stellt den Stand wieder her");
    }

    @Test
    @DisplayName("S8: BAN_ENTFERNEN für eine fremde UUID lässt den eigenen Bann unangetastet")
    void szenario8FremdeUuidEntfernen() {
        BanCache cache = new BanCache();
        UUID ziel = UUID.randomUUID();
        cache.setze(new Protokoll.BanEintrag(ziel, "Ziel", "G", "M",
                JETZT, Long.MAX_VALUE, "global"));
        cache.entferne(List.of(UUID.randomUUID()));
        assertTrue(cache.findeAktiv(ziel, "lobby", JETZT).isPresent(),
                "Fremde UUID-Removal darf den Bann nicht löschen");
        cache.entferne(List.of(ziel));
        assertTrue(cache.findeAktiv(ziel, "lobby", JETZT).isEmpty());
    }

    @Test
    @DisplayName("S8: SYNC_END mit falscher Generation ist wirkungslos")
    void szenario8FalscheGeneration() {
        BanCache cache = new BanCache();
        cache.setze(new Protokoll.BanEintrag(UUID.randomUUID(), "Z", "G", "M",
                JETZT, Long.MAX_VALUE, "global"));
        cache.syncBeginn(5L);
        cache.syncEnde(6L);
        assertEquals(1, cache.groesse(), "Generation 6 darf den unter 5 gestarten Sync nicht abschließen");
    }

    @Test
    @DisplayName("C-K1.1 (Fix A-013): Grund-Text mit Scope-Flag überschreibt die GUI-Scope-Auswahl nicht mehr")
    void cK11GuiScopeWirdVonGrundUeberschrieben() {
        GuiSitzung sitzung = new GuiSitzung();
        sitzung.ziel("Ziel");
        sitzung.aktion(GuiSitzung.Aktion.BAN);
        sitzung.dauer("7d");
        sitzung.grund("Griefing --global");
        sitzung.scope("--server=lobby");

        String zeile = sitzung.baueZeile();
        assertEquals("ban Ziel 7d Griefing --server=lobby", zeile);

        String[] teile = zeile.split("\\s+");
        String[] args = java.util.Arrays.copyOfRange(teile, 1, teile.length);
        ScopeParser.Ergebnis ergebnis = ScopeParser.parsen(args, ServerScope.unbekannt(), List.of("lobby"));

        assertEquals(ServerScope.Typ.SERVER, ergebnis.scope().typ(),
                "Fix A-013: GUI-Scope-Auswahl muss wirksam bleiben");
        assertEquals("lobby", ergebnis.scope().server());
        assertEquals(List.of("Ziel", "7d", "Griefing"), List.of(ergebnis.bereinigteArgs()));
    }

    @Test
    @DisplayName("C-K.2: Finden/Entbannen mit identiscope Scope ist symmetrisch (Exakt-Match über StrafSchluessel)")
    void cK2ScopeSymmetrie() {
        MuteCache cache = new MuteCache();
        UUID spieler = UUID.randomUUID();
        cache.setze(new Protokoll.MuteEintrag(spieler, "N", "G", "M",
                JETZT, JETZT + 60_000L, "server:lobby"));

        Optional<Protokoll.MuteEintrag> gefunden =
                cache.findeAktiv(spieler, "lobby", JETZT);
        assertTrue(gefunden.isPresent());
        assertEquals("server:lobby", gefunden.get().scope());

        cache.entferne(List.of(spieler));
        assertFalse(cache.findeAktiv(spieler, "lobby", JETZT).isPresent(),
                "Entfernen über UUID deckt alle Scopes desselben Spielers ab (kein Scope-Rest)");
    }

    @Test
    @DisplayName("C-K1.2 (Fix A-014): Legacy-Speicherwert 'server:lobby' bleibt auf den Server beschränkt")
    void cK12LegacyServerPraefixWirdUnbekannt() {
        assertEquals(ServerScope.Typ.SERVER, ServerScope.ausSpeicherwert("server:lobby").typ(),
                "Fix A-014: Alt-Format 'server:NAME' muss als SERVER-Scope gelesen werden");

        MuteCache cache = new MuteCache();
        UUID spieler = UUID.randomUUID();
        cache.setze(new Protokoll.MuteEintrag(spieler, "N", "G", "M",
                JETZT, JETZT + 60_000L, "server:lobby"));
        assertTrue(cache.findeAktiv(spieler, "lobby", JETZT).isPresent());
        assertTrue(cache.findeAktiv(spieler, "survival", JETZT).isEmpty(),
                "Fix A-014: der auf lobby beschränkte Mute darf auf survival nicht greifen");
    }
}
