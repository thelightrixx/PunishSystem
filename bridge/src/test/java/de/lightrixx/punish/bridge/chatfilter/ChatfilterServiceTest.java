package de.lightrixx.punish.bridge.chatfilter;

import de.lightrixx.punish.bridge.chatfilter.ChatfilterKonfiguration.Modus;
import de.lightrixx.punish.chatfilter.FilterKanal;
import de.lightrixx.punish.chatfilter.FilterTreffer;
import de.lightrixx.punish.chatfilter.ToxizitaetsModell;
import de.lightrixx.punish.chatfilter.TrefferQuelle;
import de.lightrixx.punish.chatfilter.WortlistenFilter;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class ChatfilterServiceTest {

    private ChatfilterKonfiguration konfiguration;
    private Player spieler;
    private UUID uuid;
    private List<FilterTreffer> gemeldet;
    private ChatfilterService dienst;
    private ZaehlModell modell;

    private static final class ZaehlModell implements ToxizitaetsModell {
        private final double bewertung;
        private final boolean fehler;
        private final AtomicInteger aufrufe = new AtomicInteger();

        private ZaehlModell(double bewertung, boolean fehler) {
            this.bewertung = bewertung;
            this.fehler = fehler;
        }

        @Override
        public double bewerte(String text) {
            aufrufe.incrementAndGet();
            if (fehler) {
                throw new de.lightrixx.punish.chatfilter.ChatfilterAusfall("Modell defekt.");
            }
            return bewertung;
        }
    }

    @BeforeEach
    void aufsetzen() {
        konfiguration = Mockito.mock(ChatfilterKonfiguration.class);
        when(konfiguration.aktiv()).thenReturn(true);
        when(konfiguration.modusFuer(any())).thenReturn(Modus.KI);
        when(konfiguration.schwelle()).thenReturn(0.7d);
        when(konfiguration.kiTimeoutMillis()).thenReturn(150L);
        when(konfiguration.bypassPermission()).thenReturn("punish.chatfilter.bypass");
        spieler = Mockito.mock(Player.class);
        uuid = UUID.randomUUID();
        when(spieler.getUniqueId()).thenReturn(uuid);
        when(spieler.getName()).thenReturn("Notch");
        when(spieler.hasPermission(anyString())).thenReturn(false);
        gemeldet = new CopyOnWriteArrayList<>();
        modell = new ZaehlModell(0.95d, false);
        dienst = new ChatfilterService(konfiguration,
                new WortlistenFilter(List.of("hurensohn")), modell,
                (id, name, text, treffer) -> gemeldet.add(treffer));
    }

    @AfterEach
    void aufraeumen() {
        dienst.close();
    }

    @Test
    void bypass_wirdNichtGeprueft() {
        when(spieler.hasPermission(anyString())).thenReturn(true);
        assertTrue(dienst.pruefeChat(spieler, "Du Hurensohn").isEmpty());
        assertEquals(0, modell.aufrufe.get());
    }

    @Test
    void wortliste_blockiertVorKi() {
        Optional<FilterTreffer> treffer = dienst.pruefeChat(spieler, "Du Hurensohn!");
        assertTrue(treffer.isPresent());
        assertEquals(TrefferQuelle.WORTLISTE, treffer.get().quelle());
        assertEquals(1.0d, treffer.get().bewertung());
        assertEquals(0, modell.aufrufe.get());
    }

    @Test
    void kiTreffer_abSchwelle() {
        Optional<FilterTreffer> treffer = dienst.pruefeChat(spieler, "Du bist ein Idiot");
        assertTrue(treffer.isPresent());
        assertEquals(TrefferQuelle.KI, treffer.get().quelle());
        assertEquals(64, treffer.get().textHash().length());
    }

    @Test
    void kiUnterSchwelle_keinTreffer() {
        dienst.close();
        modell = new ZaehlModell(0.2d, false);
        dienst = new ChatfilterService(konfiguration,
                new WortlistenFilter(List.of("hurensohn")), modell,
                (id, name, text, treffer) -> gemeldet.add(treffer));
        assertTrue(dienst.pruefeChat(spieler, "Hallo zusammen").isEmpty());
    }

    private void starteMitFreigaben(List<String> freigaben) {
        dienst.close();
        when(konfiguration.kiFreigaben()).thenReturn(freigaben);
        dienst = new ChatfilterService(konfiguration,
                new WortlistenFilter(List.of("hurensohn")), modell,
                (id, name, text, treffer) -> gemeldet.add(treffer));
    }

    @Test
    void minecraftFreigaben_sindUnabhaengigVonScoreSchreibweiseUndSatzzeichen() {
        starteMitFreigaben(List.of("xpfarm", "xp-farm", "redstone", "", "!!!"));
        for (String text : List.of("xpfarm", "-xpfarm", "XPFARM!", "(xpfarm)", "XP-Farm?",
                "xp farm", "\u00a0redstone\u00a0", "ＸＰＦＡＲＭ")) {
            assertTrue(dienst.pruefeChat(spieler, text).isEmpty(), text);
            for (FilterKanal kanal : FilterKanal.values()) {
                assertTrue(dienst.pruefeBlockierend(spieler, kanal, text).isEmpty(), text + ": " + kanal);
            }
            dienst.pruefeKiNachgelagert(uuid, "Notch", FilterKanal.CHAT, text);
        }
        assertEquals(0, modell.aufrufe.get());
        assertTrue(gemeldet.isEmpty());
    }

    @Test
    void freigabeGiltNurFuerDieVollstaendigeNachricht() {
        starteMitFreigaben(List.of("xpfarm"));
        for (String text : List.of("xpfarm du bist ein Idiot", "Du bist ein Idiot xpfarm",
                "xpfarm-Idiot", "xpfarm\nIdiot", "xpfarm123", "meine-xpfarm", "!!!")) {
            assertEquals(TrefferQuelle.KI, dienst.pruefeChat(spieler, text).orElseThrow().quelle(), text);
        }
        assertTrue(dienst.pruefeBlockierend(spieler, FilterKanal.BUCH, "xpfarm du bist ein Idiot").isPresent());
    }

    @Test
    void sperrwortlisteHatVorrangVorMinecraftFreigabe() {
        starteMitFreigaben(List.of("xpfarm", "hurensohn"));
        dienst.aktualisiereWortliste(new WortlistenFilter(List.of("hurensohn", "xpfarm")));
        assertEquals(TrefferQuelle.WORTLISTE, dienst.pruefeChat(spieler, "-xpfarm").orElseThrow().quelle());
        assertEquals(TrefferQuelle.WORTLISTE, dienst.pruefeBlockierend(
                spieler, FilterKanal.SCHILD, "hurensohn").orElseThrow().quelle());
        assertEquals(0, modell.aufrufe.get());
    }

    @Test
    void leereFreigabelisteDeaktiviertDieAusnahmen() {
        starteMitFreigaben(List.of());
        assertEquals(TrefferQuelle.KI, dienst.pruefeChat(spieler, "xpfarm").orElseThrow().quelle());
        assertEquals(1, modell.aufrufe.get());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY, -0.1d, 1.1d})
    void ungueltigeModellwerteWerdenNichtAlsTrefferOderCacheEintragVerwendet(double wert) {
        dienst.close();
        modell = new ZaehlModell(wert, false);
        dienst = new ChatfilterService(konfiguration, null, modell,
                (id, name, text, treffer) -> gemeldet.add(treffer));
        assertTrue(dienst.pruefeChat(spieler, "Hallo zusammen").isEmpty());
        assertTrue(dienst.pruefeChat(spieler, "Hallo zusammen").isEmpty());
        assertThrows(de.lightrixx.punish.chatfilter.ChatfilterAusfall.class,
                () -> dienst.pruefeBlockierend(spieler, FilterKanal.BUCH, "Hallo zusammen"));
        assertEquals(3, modell.aufrufe.get());
        assertEquals(3, dienst.kiFehlerFolge());
    }

    @Test
    void ungueltigeNachgelagerteBewertungWirdAlsAusfallGezaehlt() throws Exception {
        dienst.close();
        CountDownLatch fertig = new CountDownLatch(1);
        AtomicInteger ungueltigeAufrufe = new AtomicInteger();
        AtomicInteger fehlerVorErfolg = new AtomicInteger();
        ToxizitaetsModell ki = text -> {
            if (text.equals("ungueltig")) {
                ungueltigeAufrufe.incrementAndGet();
                return Double.POSITIVE_INFINITY;
            }
            fehlerVorErfolg.set(dienst.kiFehlerFolge());
            return 0.95d;
        };
        dienst = new ChatfilterService(konfiguration, null, ki, (id, name, text, treffer) -> {
            gemeldet.add(treffer);
            fertig.countDown();
        });
        dienst.pruefeKiNachgelagert(uuid, "Notch", FilterKanal.PRIVATNACHRICHT, "ungueltig");
        dienst.pruefeKiNachgelagert(uuid, "Notch", FilterKanal.PRIVATNACHRICHT, "ungueltig");
        dienst.pruefeKiNachgelagert(uuid, "Notch", FilterKanal.PRIVATNACHRICHT, "gueltig");
        assertTrue(fertig.await(5L, TimeUnit.SECONDS));
        assertEquals(2, ungueltigeAufrufe.get());
        assertEquals(2, fehlerVorErfolg.get());
        assertEquals(1, gemeldet.size());
        assertEquals(0.95d, gemeldet.getFirst().bewertung());
        assertEquals(0, dienst.kiFehlerFolge());
    }

    @Test
    void nachgelagerteFreigabeMeldetNichtsAberZusatztextBleibtPruefbar() throws Exception {
        dienst.close();
        CountDownLatch fertig = new CountDownLatch(1);
        when(konfiguration.kiFreigaben()).thenReturn(List.of("xpfarm"));
        dienst = new ChatfilterService(konfiguration, null, modell, (id, name, text, treffer) -> {
            gemeldet.add(treffer);
            fertig.countDown();
        });
        dienst.pruefeKiNachgelagert(uuid, "Notch", FilterKanal.PRIVATNACHRICHT, "xpfarm");
        dienst.pruefeKiNachgelagert(uuid, "Notch", FilterKanal.PRIVATNACHRICHT, "-xpfarm");
        String text = "xpfarm du bist ein Idiot";
        dienst.pruefeKiNachgelagert(uuid, "Notch", FilterKanal.PRIVATNACHRICHT, text);
        assertTrue(fertig.await(5L, TimeUnit.SECONDS));
        assertEquals(1, modell.aufrufe.get());
        assertEquals(1, gemeldet.size());
        assertEquals(de.lightrixx.punish.chatfilter.TextHash.sha256Hex(text), gemeldet.getFirst().textHash());
    }

    @Test
    void kiFehler_failOpen() {
        dienst.close();
        modell = new ZaehlModell(0.0d, true);
        dienst = new ChatfilterService(konfiguration,
                new WortlistenFilter(List.of("hurensohn")), modell,
                (id, name, text, treffer) -> gemeldet.add(treffer));
        assertTrue(dienst.pruefeChat(spieler, "Hallo zusammen").isEmpty());
    }

    @Test
    void gleicheNachricht_nurEinmalBewertet() {
        dienst.pruefeChat(spieler, "Hallo zusammen, wie geht es euch heute?");
        dienst.pruefeChat(spieler, "Hallo zusammen, wie geht es euch heute?");
        assertEquals(1, modell.aufrufe.get());
    }

    @Test
    void lehrerText_keinTreffer() {
        assertTrue(dienst.pruefeChat(spieler, "   ").isEmpty());
        assertEquals(0, modell.aufrufe.get());
    }

    @Test
    void nachgelagert_meldetKiTreffer() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        dienst.close();
        dienst = new ChatfilterService(konfiguration,
                new WortlistenFilter(List.of("hurensohn")), modell,
                (id, name, text, treffer) -> {
                    gemeldet.add(treffer);
                    latch.countDown();
                });
        dienst.pruefeKiNachgelagert(uuid, "Notch", FilterKanal.PRIVATNACHRICHT, "Du bist ein Idiot");
        assertTrue(latch.await(5L, TimeUnit.SECONDS));
        assertEquals(1, gemeldet.size());
        assertEquals(FilterKanal.PRIVATNACHRICHT, gemeldet.get(0).kanal());
    }

    @Test
    void melden_reichtWeiter() {
        var treffer = new FilterTreffer(FilterKanal.CHAT, TrefferQuelle.WORTLISTE, 1.0d,
                "abc123abc123abc123abc123abc123abc123abc123abc123abc123abc123ab12");
        dienst.melden(spieler, "Du Hurensohn", treffer);
        assertEquals(1, gemeldet.size());
    }

    @Test
    void filterAusOverride_schaltetAllesAb() {
        dienst.setFilterAktiv(Boolean.FALSE);
        assertTrue(dienst.pruefeChat(spieler, "Du Hurensohn").isEmpty());
        assertTrue(dienst.pruefeStufe1(spieler, FilterKanal.SCHILD, "Du Hurensohn").isEmpty());
        dienst.setFilterAktiv(null);
        assertTrue(dienst.pruefeChat(spieler, "Du Hurensohn").isPresent());
    }

    @Test
    void kiAusOverride_nurStufe1() {
        dienst.setKiAktiv(Boolean.FALSE);
        assertTrue(dienst.pruefeChat(spieler, "Du bist ein Idiot").isEmpty());
        assertEquals(0, modell.aufrufe.get());
        assertTrue(dienst.pruefeChat(spieler, "Du Hurensohn").isPresent());
        dienst.setKiAktiv(null);
        assertTrue(dienst.pruefeChat(spieler, "Du bist ein Idiot").isPresent());
    }

    @Test
    void uebernimmSchalterVon_kopiert() {
        dienst.setFilterAktiv(Boolean.FALSE);
        var neu = new ChatfilterService(konfiguration,
                new WortlistenFilter(List.of("hurensohn")), modell,
                (id, name, text, treffer) -> gemeldet.add(treffer));
        try {
            neu.uebernimmSchalterVon(dienst);
            assertEquals(Boolean.FALSE, neu.filterAktivOverride());
        } finally {
            neu.close();
        }
    }

    @Test
    void aktualisiereWortliste_wirktSofort() {
        var vorher = dienst.pruefeChat(spieler, "Du Blödmann").orElseThrow();
        assertEquals(TrefferQuelle.KI, vorher.quelle());
        dienst.aktualisiereWortliste(new WortlistenFilter(List.of("blödmann")));
        var nachher = dienst.pruefeChat(spieler, "Du Blödmann").orElseThrow();
        assertEquals(TrefferQuelle.WORTLISTE, nachher.quelle());
    }
}
