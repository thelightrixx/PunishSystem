package de.lightrixx.punish.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.storage.PlayerCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatfilterBefehlTest {

    private MessageService messageService;
    private BrueckenKanal kanal;
    private ProxyServer proxy;
    private BefehlsSender sender;
    private ChatfilterBefehl befehl;
    private UUID uuid;

    @BeforeEach
    void aufsetzen() {
        messageService = Mockito.mock(MessageService.class);
        var config = Mockito.mock(VelocityKonfiguration.class);
        var protection = Mockito.mock(ProtectionService.class);
        var playerCache = Mockito.mock(PlayerCache.class);
        var planer = Mockito.mock(AuftragsPlaner.class);
        var verzeichnis = Mockito.mock(SpielerVerzeichnis.class);
        proxy = Mockito.mock(ProxyServer.class);
        kanal = Mockito.mock(BrueckenKanal.class);
        sender = Mockito.mock(BefehlsSender.class);
        uuid = UUID.randomUUID();
        when(sender.istSpieler()).thenReturn(true);
        when(sender.spielerUuid()).thenReturn(uuid);
        befehl = new ChatfilterBefehl(messageService, config, protection, playerCache,
                planer, verzeichnis, proxy);
        befehl.setBrueckenKanal(kanal);
    }

    @Test
    void ohneArgs_syntax() {
        befehl.ausfuehren(sender, new String[0]);
        verify(messageService).senden(sender, MessageKeys.CHATFILTER_SYNTAX);
        verify(kanal, never()).chatfilterSteuerungAnAlle(any(), any());
    }

    @Test
    void unbekannt_syntax() {
        befehl.ausfuehren(sender, new String[]{"quatsch"});
        verify(messageService).senden(sender, MessageKeys.CHATFILTER_SYNTAX);
    }

    @Test
    void on_schaltetFilterAn() {
        befehl.ausfuehren(sender, new String[]{"on"});
        verify(kanal).chatfilterSteuerungAnAlle(Boolean.TRUE, null);
        verify(messageService).senden(sender, MessageKeys.CHATFILTER_AN);
    }

    @Test
    void off_schaltetFilterAus() {
        befehl.ausfuehren(sender, new String[]{"OFF"});
        verify(kanal).chatfilterSteuerungAnAlle(Boolean.FALSE, null);
        verify(messageService).senden(sender, MessageKeys.CHATFILTER_AUS);
    }

    @Test
    void aiSchalter() {
        befehl.ausfuehren(sender, new String[]{"ai_off"});
        verify(kanal).chatfilterSteuerungAnAlle(null, Boolean.FALSE);
        verify(messageService).senden(sender, MessageKeys.CHATFILTER_AI_AUS);
        befehl.ausfuehren(sender, new String[]{"ai_on"});
        verify(kanal).chatfilterSteuerungAnAlle(null, Boolean.TRUE);
        verify(messageService).senden(sender, MessageKeys.CHATFILTER_AI_AN);
    }

    @Test
    void add_ohneWort_syntax() {
        befehl.ausfuehren(sender, new String[]{"add"});
        verify(messageService).senden(sender, MessageKeys.CHATFILTER_SYNTAX);
    }

    @Test
    void add_ungueltig_abgewiesen() {
        befehl.ausfuehren(sender, new String[]{"add", "x"});
        verify(messageService).senden(sender, MessageKeys.CHATFILTER_WORT_UNGUELTIG);
        verify(kanal, never()).textWortHinzufuegen(anyString());
    }

    @Test
    void add_gueltig_zentralAnAlleServer() {
        when(kanal.textWortHinzufuegen("hurensohn")).thenReturn(true);
        befehl.ausfuehren(sender, new String[]{"add", "HurenS0hn"});
        verify(kanal).textWortHinzufuegen("hurensohn");
        verify(messageService).senden(eq(sender), eq(MessageKeys.CHATFILTER_ADD_ERFOLG), anyMap());
    }

    @Test
    void add_konsole_zentralErlaubt() {
        when(sender.istSpieler()).thenReturn(false);
        when(kanal.textWortHinzufuegen("hurensohn")).thenReturn(true);
        befehl.ausfuehren(sender, new String[]{"add", "hurensohn"});
        verify(kanal).textWortHinzufuegen("hurensohn");
        verify(messageService).senden(eq(sender), eq(MessageKeys.CHATFILTER_ADD_ERFOLG), anyMap());
    }

    @Test
    void ohneKanal_fehler() {
        befehl.setBrueckenKanal(null);
        befehl.ausfuehren(sender, new String[]{"on"});
        verify(messageService).senden(sender, MessageKeys.RELOAD_FEHLER);
    }

    @Test
    void tabComplete_unterbefehle() {
        var vorschlaege = befehl.vervollstaendigen(sender, new String[]{""});
        assertTrue(vorschlaege.containsAll(java.util.List.of("on", "off", "ai_on", "ai_off", "add", "sync")));
    }

    @Test
    void sync_aktiv_startetVerteilung() {
        when(kanal.modellSyncAnAlle()).thenReturn(true);
        befehl.ausfuehren(sender, new String[]{"sync"});
        verify(kanal).modellSyncAnAlle();
        verify(messageService).senden(sender, MessageKeys.CHATFILTER_SYNC_START);
    }

    @Test
    void sync_inaktiv_meldetAus() {
        when(kanal.modellSyncAnAlle()).thenReturn(false);
        befehl.ausfuehren(sender, new String[]{"sync"});
        verify(messageService).senden(sender, MessageKeys.CHATFILTER_SYNC_AUS);
    }

    @Test
    void liste_zeigtWoerter() {
        when(kanal.textWoerter()).thenReturn(java.util.List.of("wortA", "wortB"));
        befehl.ausfuehren(sender, new String[]{"list"});
        verify(kanal).textWoerter();
        verify(messageService).sendeBlock(eq(sender), any());
    }

    @Test
    void liste_leer_meldetLeer() {
        when(kanal.textWoerter()).thenReturn(java.util.List.of());
        befehl.ausfuehren(sender, new String[]{"list"});
        verify(messageService).sendeBlock(eq(sender), any());
    }

    @Test
    void remove_entferntWort() {
        when(kanal.textWortEntfernen("worta")).thenReturn(true);
        befehl.ausfuehren(sender, new String[]{"remove", "WortA"});
        verify(kanal).textWortEntfernen("worta");
        verify(messageService).senden(eq(sender), eq(MessageKeys.CHATFILTER_REMOVE_ERFOLG), anyMap());
    }

    @Test
    void remove_unbekannt_meldetNichtGefunden() {
        when(kanal.textWortEntfernen("worta")).thenReturn(false);
        befehl.ausfuehren(sender, new String[]{"remove", "wortA"});
        verify(messageService).senden(eq(sender), eq(MessageKeys.CHATFILTER_REMOVE_NICHT_GEFUNDEN), anyMap());
    }

    @Test
    void test_findetWortlistenTreffer() {
        when(kanal.textWoerter()).thenReturn(java.util.List.of("arsch"));
        befehl.ausfuehren(sender, new String[]{"test", "Du", "Arschloch"});
        verify(messageService).senden(eq(sender), eq(MessageKeys.CHATFILTER_TEST_TREFFER), anyMap());
    }

    @Test
    void test_ohneTreffer_meldetSauber() {
        when(kanal.textWoerter()).thenReturn(java.util.List.of("arsch"));
        befehl.ausfuehren(sender, new String[]{"test", "Hallo", "Welt"});
        verify(messageService).senden(eq(sender), eq(MessageKeys.CHATFILTER_TEST_SAUBER), anyMap());
    }

    @Test
    void tabComplete_removeBietetWoerter() {
        when(kanal.textWoerter()).thenReturn(java.util.List.of("wortA", "wortB"));
        var vorschlaege = befehl.vervollstaendigen(sender, new String[]{"remove", "wort"});
        assertEquals(java.util.List.of("wortA", "wortB"), vorschlaege);
    }
}
