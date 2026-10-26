package de.lightrixx.punish.net;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProtokollTest {

    @Test
    void helloRoundtrip() throws Exception {
        byte[] paket = Protokoll.hello("lobby");
        Protokoll.Paket gelesen = Protokoll.paket(paket);
        assertEquals(Protokoll.HELLO, gelesen.typ());
        int version = gelesen.nutzdaten().readInt();
        assertEquals(Integer.valueOf(Protokoll.VERSION), Integer.valueOf(version));
        assertEquals("lobby", gelesen.nutzdaten().readUTF());
    }

    @Test
    void helloAckEnthaeltKanonischenNamen() throws Exception {
        byte[] paket = Protokoll.helloAck(Integer.valueOf(Protokoll.VERSION), "Lobby");
        Protokoll.Paket gelesen = Protokoll.paket(paket);
        assertEquals(Protokoll.HELLO_ACK, gelesen.typ());
        Protokoll.HelloAck ack = Protokoll.leseHelloAckMitName(gelesen.nutzdaten());
        assertEquals(Integer.valueOf(Protokoll.VERSION), Integer.valueOf(ack.proxyVersion()));
        assertEquals("Lobby", ack.kanonischerServerName());
    }

    @Test
    void helloAckAltOhneNameBleibtLesbar() throws Exception {
        byte[] paket = Protokoll.nachricht(Protokoll.HELLO_ACK, out -> out.writeInt(3));
        Protokoll.HelloAck ack = Protokoll.leseHelloAckMitName(Protokoll.paket(paket).nutzdaten());
        assertEquals(3, ack.proxyVersion());
        assertEquals("", ack.kanonischerServerName());
    }

    @Test
    void helloEnthaeltPluginVersion() throws Exception {
        byte[] paket = Protokoll.hello("lobby", "v4.0-beta+EA16c");
        Protokoll.Hello hello = Protokoll.leseHello(Protokoll.paket(paket).nutzdaten());
        assertEquals(Protokoll.VERSION, hello.protokollVersion());
        assertEquals("lobby", hello.serverName());
        assertEquals("v4.0-beta+EA16c", hello.pluginVersion());
        assertTrue(!hello.sessionId().isBlank(), "HELLO v7 enthält eine Session-ID");
    }

    @Test
    void helloAltOhnePluginVersionBleibtLesbar() throws Exception {
        byte[] paket = Protokoll.nachricht(Protokoll.HELLO, out -> {
            out.writeInt(Protokoll.VERSION);
            out.writeUTF("lobby");
        });
        Protokoll.Hello hello = Protokoll.leseHello(Protokoll.paket(paket).nutzdaten());
        assertEquals(Protokoll.VERSION, hello.protokollVersion());
        assertEquals("lobby", hello.serverName());
        assertEquals("", hello.pluginVersion());
        assertEquals("", hello.sessionId());
    }

    @Test
    void helloAckEnthaeltProxyPluginVersion() throws Exception {
        byte[] paket = Protokoll.helloAck(Protokoll.VERSION, "Lobby", "v4.0-beta+EA16c");
        Protokoll.HelloAck ack = Protokoll.leseHelloAckMitName(Protokoll.paket(paket).nutzdaten());
        assertEquals("Lobby", ack.kanonischerServerName());
        assertEquals("v4.0-beta+EA16c", ack.proxyPluginVersion());
    }

    @Test
    void helloAckAltOhnePluginVersionBleibtLesbar() throws Exception {
        byte[] paket = Protokoll.nachricht(Protokoll.HELLO_ACK, out -> {
            out.writeInt(Protokoll.VERSION);
            out.writeUTF("Lobby");
        });
        Protokoll.HelloAck ack = Protokoll.leseHelloAckMitName(Protokoll.paket(paket).nutzdaten());
        assertEquals("Lobby", ack.kanonischerServerName());
        assertEquals("", ack.proxyPluginVersion());
    }

    @Test
    void muteSyncRoundtrip() throws Exception {
        UUID uuid = UUID.randomUUID();
        var eintrag = new Protokoll.MuteEintrag(uuid, "Steve", "Spam", "Mod",
                1000L, 2000L, "lobby");
        byte[] paket = Protokoll.muteSync(List.of(eintrag));
        Protokoll.Paket gelesen = Protokoll.paket(paket);
        assertEquals(Protokoll.MUTE_SYNC, gelesen.typ());
        List<Protokoll.MuteEintrag> liste = Protokoll.leseMuteSync(gelesen.nutzdaten());
        assertEquals(1, liste.size());
        assertEquals(eintrag, liste.get(0));
        assertEquals(1000L, liste.get(0).verbleibend(1000L));
        assertEquals(1000L, liste.get(0).verbleibend(1000L));
        assertEquals(0L, liste.get(0).verbleibend(9999L));
    }

    @Test
    void befehlRoundtrip() throws Exception {
        UUID uuid = UUID.randomUUID();
        byte[] paket = Protokoll.befehlAusfuehren(uuid, "pardon Notch");
        Protokoll.Paket gelesen = Protokoll.paket(paket);
        assertEquals(Protokoll.BEFEHL_AUSFUEHREN, gelesen.typ());
        Protokoll.BefehlsAuftrag auftrag = Protokoll.leseBefehl(gelesen.nutzdaten());
        assertEquals(uuid, auftrag.alsUuid());
        assertEquals("pardon Notch", auftrag.zeile());
    }

    @Test
    void befehlOhneSpielerIstKonsole() throws Exception {
        byte[] paket = Protokoll.befehlAusfuehren(null, "pardon-all confirm");
        Protokoll.BefehlsAuftrag auftrag =
                Protokoll.leseBefehl(Protokoll.paket(paket).nutzdaten());
        assertNull(auftrag.alsUuid());
    }

    @Test
    void platzhalterRoundtrip() throws Exception {
        UUID absender = UUID.randomUUID();
        byte[] anfragePaket = Protokoll.platzhalterAnfrage(
                new Protokoll.PlatzhalterAnfrage(7L, absender, "Steve"));
        Protokoll.Paket gelesenAnfrage = Protokoll.paket(anfragePaket);
        assertEquals(Protokoll.PLATZHALTER_ANFRAGE, gelesenAnfrage.typ());
        Protokoll.PlatzhalterAnfrage anfrage =
                Protokoll.lesePlatzhalterAnfrage(gelesenAnfrage.nutzdaten());
        assertEquals(7L, anfrage.requestId());
        assertEquals(absender, anfrage.absenderUuid());
        assertEquals("Steve", anfrage.ziel());

        var antwortOriginal = new Protokoll.PlatzhalterAntwort(7L, true,
                true, 2000L, true, Long.MAX_VALUE, false,
                Protokoll.PlatzhalterAntwort.INAKTIV, true, 3000L, 3, 6, 2, 1, 5,
                "Spam", "Admin", 1000L, "Hacking", "Mod", 1100L,
                "", "", 0L, "AFK-Verdacht", "Admin", 1200L, 2, 7, "Beleidigung", "high");
        byte[] antwortPaket = Protokoll.platzhalterAntwort(antwortOriginal);
        Protokoll.Paket gelesenAntwort = Protokoll.paket(antwortPaket);
        assertEquals(Protokoll.PLATZHALTER_ANTWORT, gelesenAntwort.typ());
        Protokoll.PlatzhalterAntwort antwort =
                Protokoll.lesePlatzhalterAntwort(gelesenAntwort.nutzdaten());
        assertEquals(antwortOriginal, antwort);
        assertTrue(antwort.muted());
        assertTrue(antwort.banned());
        assertFalse(antwort.timeouted());
        assertTrue(antwort.frozen());
        assertEquals(3000L, antwort.freezeAblauf());
        assertEquals(3, antwort.verstossAnzahl());
        assertEquals(6, antwort.verstossPunkte());
        assertEquals(2, antwort.warns());
        assertEquals(1, antwort.reportsGegen());
        assertEquals(5, antwort.reportsOffen());
        assertEquals("Spam", antwort.muteGrund());
        assertEquals("Admin", antwort.muteVon());
        assertEquals(1000L, antwort.muteSeit());
        assertEquals("Hacking", antwort.banGrund());
        assertEquals("", antwort.timeoutGrund());
        assertEquals(0L, antwort.timeoutSeit());
        assertEquals("AFK-Verdacht", antwort.freezeGrund());
        assertEquals(2, antwort.notizen());
        assertEquals(7, antwort.historyAnzahl());
        assertEquals("Beleidigung", antwort.warnsLetzterGrund());
        assertEquals("high", antwort.verstossStufe());
    }

    @Test
    void platzhalterAltOhneDetailsBleibtLesbar() throws Exception {
        byte[] paket = Protokoll.nachricht(Protokoll.PLATZHALTER_ANTWORT, out -> {
            out.writeLong(10L);
            out.writeBoolean(true);
            out.writeBoolean(true);
            out.writeLong(2000L);
            out.writeBoolean(false);
            out.writeLong(Protokoll.PlatzhalterAntwort.INAKTIV);
            out.writeBoolean(false);
            out.writeLong(Protokoll.PlatzhalterAntwort.INAKTIV);
            out.writeBoolean(false);
            out.writeLong(Protokoll.PlatzhalterAntwort.INAKTIV);
            out.writeInt(1);
            out.writeInt(2);
            out.writeInt(0);
            out.writeInt(0);
            out.writeInt(0);
        });
        Protokoll.PlatzhalterAntwort antwort =
                Protokoll.lesePlatzhalterAntwort(Protokoll.paket(paket).nutzdaten());
        assertTrue(antwort.erfolg());
        assertTrue(antwort.muted());
        assertEquals("", antwort.muteGrund());
        assertEquals("", antwort.muteVon());
        assertEquals(0L, antwort.muteSeit());
        assertEquals(0, antwort.notizen());
        assertEquals(0, antwort.historyAnzahl());
        assertEquals("", antwort.warnsLetzterGrund());
        assertEquals("", antwort.verstossStufe());
    }

    @Test
    void platzhalterAltOhneFreezeBleibtLesbar() throws Exception {
        byte[] paket = Protokoll.nachricht(Protokoll.PLATZHALTER_ANTWORT, out -> {
            out.writeLong(9L);
            out.writeBoolean(true);
            out.writeBoolean(false);
            out.writeLong(Protokoll.PlatzhalterAntwort.INAKTIV);
            out.writeBoolean(false);
            out.writeLong(Protokoll.PlatzhalterAntwort.INAKTIV);
            out.writeBoolean(false);
            out.writeLong(Protokoll.PlatzhalterAntwort.INAKTIV);
            out.writeInt(0);
            out.writeInt(0);
            out.writeInt(0);
            out.writeInt(0);
            out.writeInt(0);
        });
        Protokoll.PlatzhalterAntwort antwort =
                Protokoll.lesePlatzhalterAntwort(Protokoll.paket(paket).nutzdaten());
        assertTrue(antwort.erfolg());
        assertFalse(antwort.frozen());
        assertEquals(Protokoll.PlatzhalterAntwort.INAKTIV, antwort.freezeAblauf());
    }

    @Test
    void platzhalterFehlerFabriken() {        var fehler = Protokoll.PlatzhalterAntwort.fehler(3L);
        assertFalse(fehler.erfolg());
        assertFalse(fehler.muted());
        var leer = Protokoll.PlatzhalterAntwort.leer(4L);
        assertTrue(leer.erfolg());
        assertEquals(0, leer.warns());
    }

    @Test
    void korruptePaketeWerdenAbgewiesen() {
        IOException nullFehler = assertThrows(IOException.class, () -> Protokoll.paket(null));
        assertEquals("Leeres Paket", nullFehler.getMessage());
        IOException leerFehler = assertThrows(IOException.class, () -> Protokoll.paket(new byte[0]));
        assertEquals("Leeres Paket", leerFehler.getMessage());

        Protokoll.Paket nurTyp = assertDoesNotThrow(
                () -> Protokoll.paket(new byte[]{Protokoll.MUTE_SYNC}));
        assertEquals(Protokoll.MUTE_SYNC, nurTyp.typ());
        assertThrows(IOException.class, () -> Protokoll.leseMuteSync(nurTyp.nutzdaten()),
                "Ein Paket ohne Nutzdaten muss als IOException abgelehnt werden.");
    }

    @Test
    void guiAnfrageAntwortRoundtrip() throws Exception {
        UUID absender = UUID.randomUUID();
        var anfrage = new Protokoll.GuiAnfrage(42L, absender, "status", "Max", "all", 1, "");
        byte[] paket = Protokoll.guiAnfrage(anfrage);
        Protokoll.Paket gelesen = Protokoll.paket(paket);
        assertEquals(Protokoll.GUI_ANFRAGE, gelesen.typ());
        Protokoll.GuiAnfrage zurück = Protokoll.leseGuiAnfrage(gelesen.nutzdaten());
        assertEquals(42L, zurück.requestId());
        assertEquals(absender, zurück.absenderUuid());
        assertEquals("STATUS", zurück.typ());
        assertEquals("Max", zurück.ziel());

        var zeilen = List.of(
                new Protokoll.GuiZeile("MUTE", "Mute [global]", "Grund: Spam", "unmute Max", "Max"),
                new Protokoll.GuiZeile("INFO", "Hinweis", "Nur Anzeige", "", ""));
        var antwort = new Protokoll.GuiAntwort(42L, true, "ok", 1, 1, 2, zeilen);
        byte[] paketAntwort = Protokoll.guiAntwort(antwort);
        Protokoll.Paket gelesenAntwort = Protokoll.paket(paketAntwort);
        assertEquals(Protokoll.GUI_ANTWORT, gelesenAntwort.typ());
        Protokoll.GuiAntwort zurückAntwort = Protokoll.leseGuiAntwort(gelesenAntwort.nutzdaten());
        assertEquals(42L, zurückAntwort.requestId());
        assertTrue(zurückAntwort.erfolg());
        assertEquals(2, zurückAntwort.eintraege().size());
        assertEquals("unmute Max", zurückAntwort.eintraege().get(0).aktion());
    }

    @Test
    void guiAntwortBegrenztZeilen() throws Exception {
        var viele = new java.util.ArrayList<Protokoll.GuiZeile>();
        for (int i = 0; i < 100; i++) {
            viele.add(new Protokoll.GuiZeile("INFO", "T" + i, "D", "", ""));
        }
        var antwort = new Protokoll.GuiAntwort(7L, true, "", 1, 1, 100, viele);
        Protokoll.GuiAntwort gelesen =
                Protokoll.leseGuiAntwort(Protokoll.paket(Protokoll.guiAntwort(antwort)).nutzdaten());
        assertTrue(gelesen.eintraege().size() <= Protokoll.MAX_GUI_ZEILEN);
    }

    @Test
    void muteEntfernenListe() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        byte[] paket = Protokoll.nachricht(Protokoll.MUTE_ENTFERNEN, out -> {
            out.writeInt(2);
            Protokoll.schreibeUuid(out, a);
            Protokoll.schreibeUuid(out, b);
        });
        Protokoll.Paket gelesen = Protokoll.paket(paket);
        assertEquals(Protokoll.MUTE_ENTFERNEN, gelesen.typ());
        assertEquals(List.of(a, b), Protokoll.leseUuidListe(gelesen.nutzdaten()));
    }

    @Test
    void freezeSyncRoundtrip() throws Exception {
        UUID uuid = UUID.randomUUID();
        var eintrag = new Protokoll.FreezeEintrag(uuid, "Steve", "Cheating-Verdacht", "Mod",
                1000L, 2000L, "lobby");
        byte[] paket = Protokoll.freezeSync(List.of(eintrag));
        Protokoll.Paket gelesen = Protokoll.paket(paket);
        assertEquals(Protokoll.FREEZE_SYNC, gelesen.typ());
        List<Protokoll.FreezeEintrag> liste = Protokoll.leseFreezeSync(gelesen.nutzdaten());
        assertEquals(1, liste.size());
        assertEquals(eintrag, liste.get(0));
        assertTrue(liste.get(0).istAktiv(1000L));
        assertFalse(liste.get(0).istAktiv(9999L));
    }

    @Test
    void permanenterFreezeBleibtAktiv() throws Exception {
        UUID uuid = UUID.randomUUID();
        var eintrag = new Protokoll.FreezeEintrag(uuid, "Steve", "Verdacht", "Mod",
                1000L, Long.MAX_VALUE, "global");
        byte[] paket = Protokoll.nachricht(Protokoll.FREEZE_SETZEN,
                out -> Protokoll.schreibeFreeze(out, eintrag));
        Protokoll.Paket gelesen = Protokoll.paket(paket);
        assertEquals(Protokoll.FREEZE_SETZEN, gelesen.typ());
        Protokoll.FreezeEintrag zurück = Protokoll.leseFreeze(gelesen.nutzdaten());
        assertEquals(eintrag, zurück);
        assertTrue(zurück.istPermanent());
        assertTrue(zurück.istAktiv(Long.MAX_VALUE - 1));
    }

    @Test
    void kuerzeTrenntKeineSurrogatePaare() {
        String emoji = new String(Character.toChars(0x1F600));
        String voll = "Hi " + emoji;
        String text = voll + "!";
        assertEquals(voll, Protokoll.kuerze(text, 5));
        assertEquals("Hi ", Protokoll.kuerze(text, 4));
        assertEquals("Hi", Protokoll.kuerze(text, 2));
    }
}
