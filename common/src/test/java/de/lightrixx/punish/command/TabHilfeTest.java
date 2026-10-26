package de.lightrixx.punish.command;

import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.OnlineSpieler;
import de.lightrixx.punish.service.BanService;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.storage.PlayerCache;
import de.lightrixx.punish.util.TabHinweis;
import de.lightrixx.punish.util.TimeParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class TabHilfeTest {

    @Test
    void teile_behaeltNachgestelltesLeerzeichen() {
        assertArrayEquals(new String[]{""}, TabHinweis.teile(null));
        assertArrayEquals(new String[]{""}, TabHinweis.teile(""));
        assertArrayEquals(new String[]{"Notch"}, TabHinweis.teile("Notch"));
        assertArrayEquals(new String[]{"Notch", ""}, TabHinweis.teile("Notch "));
        assertArrayEquals(new String[]{"Notch", "7d", ""}, TabHinweis.teile("Notch 7d "));
        assertArrayEquals(new String[]{"Notch", "7d"}, TabHinweis.teile("Notch  7d"));
    }

    @Test
    void praefixFuer_gibtRestBisLeerzeichen() {
        assertEquals("", TabHinweis.praefixFuer(null));
        assertEquals("", TabHinweis.praefixFuer("Notch"));
        assertEquals("Notch ", TabHinweis.praefixFuer("Notch 7"));
        assertEquals("Notch 7d ", TabHinweis.praefixFuer("Notch 7d Che"));
    }

    @Test
    void hatGrundBegonnen_erstAbZweitemGrundToken() {
        assertFalse(TabHinweis.hatGrundBegonnen(new String[]{"Notch", "7d", "Che"}, 2));
        assertTrue(TabHinweis.hatGrundBegonnen(new String[]{"Notch", "7d", "Cheating", ""}, 2));
        assertFalse(TabHinweis.hatGrundBegonnen(new String[]{"confirm", "Che"}, 1));
        assertTrue(TabHinweis.hatGrundBegonnen(new String[]{"confirm", "Cheating", ""}, 1));
    }

    @Test
    void filtere_behaeltReihenfolgeOhneSortierung() {
        List<String> eingabe = List.of("10m", "1h", "7d");
        assertEquals(List.of("10m", "1h", "7d"), TabHinweis.filtere(eingabe, ""));
        assertEquals(List.of("10m", "1h"), TabHinweis.filtere(eingabe, "1"));
        assertTrue(TabHinweis.filtere(eingabe, "x").isEmpty());
    }

    private BefehlsSender sender() {
        BefehlsSender sender = mock(BefehlsSender.class);
        when(sender.hatRecht(anyString())).thenReturn(true);
        when(sender.istSpieler()).thenReturn(false);
        when(sender.name()).thenReturn("Konsole");
        return sender;
    }

    private BanCommand banBefehl() {
        MessageService messages = mock(MessageService.class);
        Konfiguration config = mock(Konfiguration.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        when(config.tabGrundListe()).thenReturn(List.of("Cheating", "Spamming"));
        when(config.grundListe("ban")).thenReturn(List.of("Cheating"));
        when(config.tabDauerListe()).thenReturn(List.of("7d"));
        when(config.dauerListe("ban")).thenReturn(List.of("7d"));
        when(config.empfohleneDauer("ban", "Cheating")).thenReturn("30d");
        when(config.bekannteServer()).thenReturn(List.of("lobby"));
        ProtectionService schutz = mock(ProtectionService.class);
        when(schutz.istOnlineGeschuetzt(any())).thenReturn(false);
        PlayerCache cache = mock(PlayerCache.class);
        when(cache.alleNamen()).thenReturn(List.of());
        when(cache.alle()).thenReturn(List.of());
        de.lightrixx.punish.core.SpielerVerzeichnis verzeichnis =
                mock(de.lightrixx.punish.core.SpielerVerzeichnis.class);
        when(verzeichnis.onlineSpieler()).thenReturn(List.of(
                new OnlineSpieler(UUID.randomUUID(), "Notch", "1.2.3.4", false),
                new OnlineSpieler(UUID.randomUUID(), "Herobrine", "5.6.7.8", false)));
        return new BanCommand(messages, config, schutz, cache, mock(BanService.class),
                mock(TimeParser.class), mock(AuftragsPlaner.class), verzeichnis);
    }

    @Test
    void ban_schlaegtSpielerAnErsterStelleVor() {
        List<String> vorschlaege = banBefehl().vervollstaendigen(sender(), new String[]{"No"});
        assertTrue(vorschlaege.contains("Notch"), "Spieler mit Prefix No erwartet");
        assertFalse(vorschlaege.contains("Herobrine"));
    }

    @Test
    void ban_schlaegtGrundAnDritterStelleVor() {
        List<String> vorschlaege = banBefehl().vervollstaendigen(sender(), new String[]{"Notch", "7d", "Che"});
        assertTrue(vorschlaege.contains("Cheating"));
    }

    @Test
    void ban_nachFertigemGrundNurNochFlags() {
        List<String> vorschlaege = banBefehl()
                .vervollstaendigen(sender(), new String[]{"Notch", "7d", "Cheating", ""});
        assertFalse(vorschlaege.contains("Cheating"), "Grund darf nicht erneut vorgeschlagen werden");
        assertTrue(vorschlaege.contains("--global"));
        assertTrue(vorschlaege.contains("-s"));
    }

    @Test
    void ban_tooltipZeigtEmpfohleneDauerUndKategorien() {
        assertEquals("Empf. Dauer: 30d",
                banBefehl().tooltipFuer("Cheating", new String[]{"Notch", "7d", "Che"}));
        assertEquals("Spieler", banBefehl().tooltipFuer("Notch", new String[]{"No"}));
        assertEquals("Dauer", banBefehl().tooltipFuer("7d", new String[]{"Notch", "7"}));
        assertEquals("Gültigkeit: ganzes Netzwerk",
                banBefehl().tooltipFuer("--global", new String[]{"Notch", "7d", "Cheating", ""}));
        assertEquals("Still: keine Broadcasts",
                banBefehl().tooltipFuer("-s", new String[]{"Notch", "7d", "Cheating", ""}));
        assertEquals("Grund",
                banBefehl().tooltipFuer("Spamming", new String[]{"Notch", "7d", "Spa"}));
    }
}
