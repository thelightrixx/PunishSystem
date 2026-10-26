package de.lightrixx.punish.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class IpBanRecordTest {

    @Test
    void kanonisierenNormalisiertIpv4() {
        assertEquals("127.0.0.1", IpBanRecord.kanonisieren("127.0.0.1"));
        assertEquals("127.0.0.1", IpBanRecord.kanonisieren("  127.0.0.1  "));
    }

    @Test
    void kanonisierenNormalisiertIpv6Loopback() {
        String kanonisch = IpBanRecord.kanonisieren("::1");
        assertTrue(kanonisch.equals("::1") || kanonisch.equals("0:0:0:0:0:0:0:1"),
                "Unerwartete Kanonisierung: " + kanonisch);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "abc", "999.999.999.999", "192.168.1.1/24", "10.0.0.0/8"})
    void kanonisierenLehntUngueltigAb(String eingabe) {
        assertThrows(IllegalArgumentException.class, () -> IpBanRecord.kanonisieren(eingabe));
    }

    @Test
    void kanonisierenLehntNullAb() {
        assertThrows(IllegalArgumentException.class, () -> IpBanRecord.kanonisieren(null));
    }

    @Test
    void permanenterBannIstImmerAktiv() {
        IpBanRecord record = new IpBanRecord("1.2.3.4", "Grund", "Konsole", System.currentTimeMillis());
        assertTrue(record.istPermanent());
        assertTrue(record.istAktiv(System.currentTimeMillis() + 1_000_000_000L));
        assertFalse(record.istAbgelaufen(System.currentTimeMillis() + 1_000_000_000L));
    }

    @Test
    void temporaererBannLaeuftAb() {
        long jetzt = System.currentTimeMillis();
        IpBanRecord record = new IpBanRecord("5.6.7.8", "Grund", "Konsole", jetzt, jetzt + 60_000L);
        assertFalse(record.istPermanent());
        assertTrue(record.istAktiv(jetzt + 1_000L));
        assertFalse(record.istAktiv(jetzt + 61_000L));
        assertTrue(record.istAbgelaufen(jetzt + 61_000L));
    }

    @Test
    void ablaufVorErstellungWirft() {
        long jetzt = System.currentTimeMillis();
        assertThrows(IllegalArgumentException.class,
                () -> new IpBanRecord("9.9.9.9", "Grund", "Konsole", jetzt, jetzt - 1));
    }

    @Test
    void recordKanonisiertIpImKonstruktor() {
        IpBanRecord record = new IpBanRecord("  8.8.8.8 ", "Grund", "Konsole", System.currentTimeMillis());
        assertEquals("8.8.8.8", record.ip());
    }

    @Test
    void sentinelAblaufWerteGeltenAlsPermanent() {
        long jetzt = System.currentTimeMillis();
        assertTrue(new IpBanRecord("1.2.3.4", "Grund", "Konsole", jetzt, -1L).istPermanent(),
                "-1 muss als permanenter Bann gelten, nicht als ungültiger Wert.");
        assertTrue(new IpBanRecord("1.2.3.4", "Grund", "Konsole", jetzt, Long.MAX_VALUE)
                        .istPermanent(),
                "Long.MAX_VALUE muss als permanenter Bann gelten.");
    }

    @Test
    void scopeWirdNormalisiertUebernommen() {
        IpBanRecord record = new IpBanRecord("  1.2.3.4 ", "Grund", "Konsole",
                System.currentTimeMillis(), null, ServerScope.server("  Lobby "));
        assertEquals("1.2.3.4", record.ip());
        assertEquals(ServerScope.server("lobby"), record.scope());
    }

    @Test
    void fehlenderScopeWirdAbgelehnt() {
        assertThrows(IllegalArgumentException.class,
                () -> new IpBanRecord("1.2.3.4", "Grund", "Konsole",
                        System.currentTimeMillis(), null, null));
    }
}
