package de.lightrixx.punish.velocity;

import de.lightrixx.punish.net.Protokoll;
import de.lightrixx.punish.service.BroadcastService;
import de.lightrixx.punish.service.MuteService;
import de.lightrixx.punish.service.WarnService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatfilterStaffelServiceTest {

    private VelocityKonfiguration konfiguration;
    private WarnService warnService;
    private MuteService muteService;
    private BroadcastService broadcastService;
    private ChatfilterStaffelService dienst;
    private UUID uuid;

    @BeforeEach
    void aufsetzen() {
        konfiguration = Mockito.mock(VelocityKonfiguration.class);
        when(konfiguration.chatfilterAktiv()).thenReturn(true);
        when(konfiguration.chatfilterTeamInfo()).thenReturn(false);
        when(konfiguration.chatfilterKlartextUbertragen()).thenReturn(true);
        when(konfiguration.chatfilterFensterMillis()).thenReturn(3600000L);
        when(konfiguration.chatfilterStaffel()).thenReturn(List.of(
                new ChatfilterStaffelService.Stufe(5, ChatfilterStaffelService.Aktion.WARN, 0L, null),
                new ChatfilterStaffelService.Stufe(8, ChatfilterStaffelService.Aktion.MUTE, 3600000L, null)));
        warnService = Mockito.mock(WarnService.class);
        muteService = Mockito.mock(MuteService.class);
        broadcastService = Mockito.mock(BroadcastService.class);
        when(muteService.muten(any(), anyString(), anyLong(), anyString(), anyString())).thenReturn(true);
        dienst = new ChatfilterStaffelService(konfiguration, warnService, muteService, broadcastService);
        uuid = UUID.randomUUID();
    }

    private void melde() {
        dienst.melde(uuid, "Notch", new Protokoll.ChatfilterTreffer(uuid, "Notch",
                "CHAT", "KI", 0.9d,
                "abc123abc123abc123abc123abc123abc123abc123abc123abc123abc123ab12",
                "Du Idiot",
                System.currentTimeMillis()));
    }

    @Test
    void fuenfTreffer_loesenWarnAus() {
        for (int i = 0; i < 4; i++) {
            melde();
        }
        verify(warnService, never()).hinzufuegen(any(), anyString(), anyString(), anyString());
        melde();
        verify(warnService, times(1)).hinzufuegen(eq(uuid), eq("Notch"), anyString(), eq("Chatfilter"));
    }

    @Test
    void stufe_loestNurEinmalAus() {
        for (int i = 0; i < 7; i++) {
            melde();
        }
        verify(warnService, times(1)).hinzufuegen(any(), anyString(), anyString(), anyString());
        verify(muteService, never()).muten(any(), anyString(), anyLong(), anyString(), anyString());
        melde();
        verify(muteService, times(1)).muten(eq(uuid), eq("Notch"), eq(3600000L), anyString(), eq("Chatfilter"));
        melde();
        verify(warnService, times(1)).hinzufuegen(any(), anyString(), anyString(), anyString());
        verify(muteService, times(1)).muten(any(), anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    void inaktiv_keineAktion() {
        when(konfiguration.chatfilterAktiv()).thenReturn(false);
        for (int i = 0; i < 10; i++) {
            melde();
        }
        verify(warnService, never()).hinzufuegen(any(), anyString(), anyString(), anyString());
        verify(muteService, never()).muten(any(), anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    void schutzfehler_wirdGeschluckt() {
        when(warnService.hinzufuegen(any(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("geschützt"));
        for (int i = 0; i < 5; i++) {
            melde();
        }
        verify(warnService, times(1)).hinzufuegen(any(), anyString(), anyString(), anyString());
    }

    @Test
    void teamInfo_ausStandard_keinBroadcast() {
        melde();
        verify(broadcastService, never()).senden(anyString(), anyMap());
    }

    @Test
    void teamInfo_an_sendetMitInhalt() {
        when(konfiguration.chatfilterTeamInfo()).thenReturn(true);
        when(konfiguration.chatfilterKlartextUbertragen()).thenReturn(true);
        melde();
        verify(broadcastService, times(1)).senden(
                eq(de.lightrixx.punish.core.MessageKeys.BROADCAST_CHATFILTER_TREFFER),
                anyMap());
    }

    @Test
    void teamInfo_hashOnly_zeigtKeinenKlartext() {
        when(konfiguration.chatfilterTeamInfo()).thenReturn(true);
        when(konfiguration.chatfilterKlartextUbertragen()).thenReturn(false);
        var hash = "a".repeat(64);
        dienst.melde(uuid, "Notch", new Protokoll.ChatfilterTreffer(uuid, "Notch",
                "CHAT", "KI", 0.9d, hash, "Geheimer Klartext", System.currentTimeMillis()));
        var captor = org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(broadcastService, times(1)).senden(
                eq(de.lightrixx.punish.core.MessageKeys.BROADCAST_CHATFILTER_TREFFER),
                captor.capture());
        Object text = captor.getValue().get("text");
        assertNotNull(text);
        assertFalse(String.valueOf(text).contains("Geheimer Klartext"));
        assertTrue(String.valueOf(text).contains("Hash"));
    }

    @Test
    void fensterMap_wirdHartBegrenzt() {
        for (int i = 0; i < 10_050; i++) {
            UUID anderer = UUID.randomUUID();
            dienst.melde(anderer, "Notch", new Protokoll.ChatfilterTreffer(anderer, "Notch",
                    "CHAT", "KI", 0.9d, "b".repeat(64), "Text", System.currentTimeMillis()));
        }
        assertTrue(dienst.fensterGroesse() <= 10_000,
                "FensterMap muss hart begrenzt bleiben, war: " + dienst.fensterGroesse());
    }

    @Test
    void kurzHash_kuerztAuf12() {
        assertEquals("unbekannt", ChatfilterStaffelService.kurzHash(null));
        assertEquals("unbekannt", ChatfilterStaffelService.kurzHash("  "));
        assertEquals("abc", ChatfilterStaffelService.kurzHash("abc"));
        assertEquals("a".repeat(12), ChatfilterStaffelService.kurzHash("a".repeat(64)));
    }
}
