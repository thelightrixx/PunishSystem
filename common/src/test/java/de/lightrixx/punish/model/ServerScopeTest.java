package de.lightrixx.punish.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ServerScopeTest {

    @Test
    void unbekanntBleibtUnbekannt_stattGlobalAnnahme() {
        assertEquals(ServerScope.unbekannt(), ServerScope.ausSpeicherwert(null));
        assertEquals(ServerScope.unbekannt(), ServerScope.ausSpeicherwert(""));
        assertEquals(ServerScope.unbekannt(), ServerScope.ausSpeicherwert("unbekannt"));
        assertEquals(ServerScope.unbekannt(), ServerScope.ausSpeicherwert("UNBEKANNT"));

        assertEquals(ServerScope.unbekannt(), ServerScope.ausSpeicherwert("!!!"));
        assertEquals(ServerScope.unbekannt(), ServerScope.ausSpeicherwert("global".toUpperCase().replace("GLOBAL", "!!!")));
    }

    @Test
    void globalWirdErkannt() {
        assertEquals(ServerScope.global(), ServerScope.ausSpeicherwert("global"));
        assertEquals(ServerScope.global(), ServerScope.ausSpeicherwert("GLOBAL"));
        assertEquals("global", ServerScope.global().speicherwert());
    }

    @Test
    void serverWirdNormalisiert() {
        assertEquals(ServerScope.server("lobby"), ServerScope.ausSpeicherwert("Lobby"));
        assertEquals(ServerScope.server("lobby"), ServerScope.ausSpeicherwert("  LOBBY  "));
        assertEquals("lobby", ServerScope.server("Lobby").speicherwert());
    }

    @Test
    void a014LegacyServerPraefixWirdAlsServerGelesen() {
        assertEquals(ServerScope.server("lobby"), ServerScope.ausSpeicherwert("server:lobby"));
        assertEquals(ServerScope.server("lobby"), ServerScope.ausSpeicherwert("SERVER:Lobby"));
        assertEquals(ServerScope.server("lobby"), ServerScope.ausSpeicherwert("  server:lobby  "));
        assertFalse(ServerScope.ausSpeicherwert("server:lobby").giltFuer("survival"),
                "A-014: server:lobby darf nicht auf survival gelten");
        assertTrue(ServerScope.ausSpeicherwert("server:lobby").giltFuer("lobby"));
        assertEquals(ServerScope.unbekannt(), ServerScope.ausSpeicherwert("server:"));
    }

    @Test
    void reservierteNamenSindKeineServer() {
        assertNull(ServerScope.normalisiereServerName("global"));
        assertNull(ServerScope.normalisiereServerName("unbekannt"));
        assertNull(ServerScope.normalisiereServerName(""));
        assertNull(ServerScope.normalisiereServerName("a b"));
        assertNotNull(ServerScope.normalisiereServerName("lobby-1"));
        assertNotNull(ServerScope.normalisiereServerName("survival_2"));
    }

    @Test
    void enforcementIstFailClosed() {

        assertTrue(ServerScope.global().giltFuer("lobby"));
        assertTrue(ServerScope.global().giltFuer("survival"));
        assertTrue(ServerScope.unbekannt().giltFuer("lobby"));
        assertTrue(ServerScope.unbekannt().giltFuer("survival"));

        assertTrue(ServerScope.server("lobby").giltFuer("lobby"));
        assertTrue(ServerScope.server("lobby").giltFuer("LOBBY"));
        assertFalse(ServerScope.server("lobby").giltFuer("survival"));

        assertTrue(ServerScope.server("lobby").giltFuer("unbekannt"));
        assertTrue(ServerScope.server("lobby").giltFuer(null));
        assertTrue(ServerScope.server("lobby").giltFuer("  "));
    }

    @Test
    void recordsDefaultenAufUnbekannt() {
        java.util.UUID uuid = java.util.UUID.randomUUID();
        long jetzt = System.currentTimeMillis();
        BanRecord ban = new BanRecord(uuid, "Steve", "G", "M", jetzt, jetzt + 60000L, false);
        assertEquals(ServerScope.unbekannt(), ban.scope());
        MuteRecord mute = new MuteRecord(uuid, "Steve", "G", "M", jetzt, jetzt + 60000L);
        assertEquals(ServerScope.unbekannt(), mute.scope());
        TimeoutRecord timeout = new TimeoutRecord(uuid, "Steve", "G", "M", jetzt, jetzt + 60000L);
        assertEquals(ServerScope.unbekannt(), timeout.scope());
        IpBanRecord ip = new IpBanRecord("1.2.3.4", "G", "M", jetzt, jetzt + 60000L);
        assertEquals(ServerScope.unbekannt(), ip.scope());
        HistoryEntry entry = new HistoryEntry(1, HistoryType.BAN, "M", "Z", "G", jetzt, null, true);
        assertEquals("unbekannt", entry.scope());
        assertEquals("unbekannt", entry.herkunft());
    }
}
