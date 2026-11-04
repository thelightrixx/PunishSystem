package de.lightrixx.punish.bridge;

import de.lightrixx.punish.net.Protokoll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IpMuteCacheKanonischTest {

    @Test
    void setzeMitIpv6VariantenBleibtEinEintrag() {
        IpMuteCache cache = new IpMuteCache();
        cache.setze(new Protokoll.IpMuteEintrag(
                "2001:0DB8:0000:0000:0000:0000:0000:0001", "Spam", "Admin", 1000L, 2000L, "global"));
        assertEquals(1, cache.groesse());

        cache.setze(new Protokoll.IpMuteEintrag(
                "2001:db8::1", "Spam", "Admin", 1000L, 3000L, "global"));
        assertEquals(1, cache.groesse(), "Kanonische Varianten müssen auf denselben Key laufen");
        assertEquals(3000L, cache.findeAktiv("2001:db8::1", "lobby", 1500L).orElseThrow().ablaufAm());
    }

    @Test
    void findeAktivFindetKanonisierteVariante() {
        IpMuteCache cache = new IpMuteCache();
        cache.ersetzeAlle(java.util.List.of(new Protokoll.IpMuteEintrag(
                "2001:0DB8:0000:0000:0000:0000:0000:0001", "Werbung", "Admin", 1000L, 5000L, "global")));

        var treffer = cache.findeAktiv("2001:db8::1", "lobby", 2000L);
        assertTrue(treffer.isPresent(), "IPv6-Kurzform muss den Langform-Eintrag treffen");
        assertTrue(cache.findeAktiv("10.0.0.1", "lobby", 2000L).isEmpty());
    }

    @Test
    void abgelaufenerEintragWirdNichtGeliefert() {
        IpMuteCache cache = new IpMuteCache();
        cache.setze(new Protokoll.IpMuteEintrag("1.2.3.4", "x", "Admin", 1000L, 2000L, "global"));
        assertTrue(cache.findeAktiv("1.2.3.4", "lobby", 3000L).isEmpty());
    }

    @Test
    void entfernenTrifftKanonischeVariante() {
        IpMuteCache cache = new IpMuteCache();
        cache.setze(new Protokoll.IpMuteEintrag(
                "2001:0DB8:0000:0000:0000:0000:0000:0001", "Spam", "Admin", 1000L, 9000L, "global"));
        assertEquals(1, cache.groesse());
        cache.entferne(java.util.List.of("2001:db8::1"));
        assertEquals(0, cache.groesse(), "Unmute mit Kurzform muss den Langform-Eintrag entfernen");
        assertTrue(cache.findeAktiv("2001:0DB8:0000:0000:0000:0000:0000:0001", "lobby", 2000L).isEmpty());
    }

    @Test
    void mehrereScopesKoexistieren() {
        IpMuteCache cache = new IpMuteCache();
        cache.ersetzeAlle(java.util.List.of(
                new Protokoll.IpMuteEintrag("1.2.3.4", "Spam", "Admin", 1000L, 9000L, "lobby"),
                new Protokoll.IpMuteEintrag("1.2.3.4", "Spam", "Admin", 1000L, 9000L, "survival")));
        assertEquals(2, cache.groesse(), "Server-Scopes dürfen sich nicht überschreiben");
        assertTrue(cache.findeAktiv("1.2.3.4", "lobby", 2000L).isPresent());
        assertTrue(cache.findeAktiv("1.2.3.4", "survival", 2000L).isPresent());
        assertTrue(cache.findeAktiv("1.2.3.4", "citybuild", 2000L).isEmpty());
    }
}
