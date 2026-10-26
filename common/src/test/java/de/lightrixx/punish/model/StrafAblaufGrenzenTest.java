package de.lightrixx.punish.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tiefen-Audit (M9/M1): Invariante "abgelaufen heißt jetzt >= ende" gilt für jede Strafart
 * exakt an der Grenze (nicht eine Millisekunde früher oder später).
 */
class StrafAblaufGrenzenTest {

    private static final UUID UUID_A = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final long ERSTELLT = 1_700_000_000_000L;
    private static final long ABLAUF = ERSTELLT + 60_000L;

    @Test
    @DisplayName("Mute: aktiv bis exklusive Ablauf, abgelaufen ab Ablauf")
    void muteGrenze() {
        MuteRecord mute = new MuteRecord(UUID_A, "Spieler", "Grund", "Mod", ERSTELLT, ABLAUF);
        assertTrue(mute.istAktiv(ABLAUF - 1));
        assertFalse(mute.istAktiv(ABLAUF));
        assertFalse(mute.istAbgelaufen(ABLAUF - 1));
        assertTrue(mute.istAbgelaufen(ABLAUF));
        assertThrows(IllegalArgumentException.class,
                () -> new MuteRecord(UUID_A, "Spieler", "Grund", "Mod", ERSTELLT, ERSTELLT));
    }

    @Test
    @DisplayName("Timeout: aktiv bis exklusive Ablauf, abgelaufen ab Ablauf")
    void timeoutGrenze() {
        TimeoutRecord timeout = new TimeoutRecord(UUID_A, "Spieler", "Grund", "Mod", ERSTELLT, ABLAUF);
        assertTrue(timeout.istAktiv(ABLAUF - 1));
        assertFalse(timeout.istAktiv(ABLAUF));
        assertFalse(timeout.istAbgelaufen(ABLAUF - 1));
        assertTrue(timeout.istAbgelaufen(ABLAUF));
        assertThrows(IllegalArgumentException.class,
                () -> new TimeoutRecord(UUID_A, "Spieler", "Grund", "Mod", ERSTELLT, ERSTELLT));
    }

    @Test
    @DisplayName("Freeze: befristet ab Ablauf inaktiv, permanent (Long.MAX_VALUE) immer aktiv")
    void freezeGrenze() {
        FreezeRecord freeze = new FreezeRecord(UUID_A, "Spieler", "Grund", "Mod", ERSTELLT, ABLAUF);
        assertTrue(freeze.istAktiv(ABLAUF - 1));
        assertFalse(freeze.istAktiv(ABLAUF));
        assertTrue(freeze.istAbgelaufen(ABLAUF));
        assertFalse(freeze.istAbgelaufen(ABLAUF - 1));

        FreezeRecord permanent = new FreezeRecord(UUID_A, "Spieler", "Grund", "Mod",
                ERSTELLT, Long.MAX_VALUE);
        assertTrue(permanent.istPermanent());
        assertTrue(permanent.istAktiv(Long.MAX_VALUE));
        assertFalse(permanent.istAbgelaufen(Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,
                () -> new FreezeRecord(UUID_A, "Spieler", "Grund", "Mod", ERSTELLT, ERSTELLT));
    }

    @Test
    @DisplayName("IP-Strafen: gleiche Grenzsemantik wie Spieler-Strafen")
    void ipStrafenGrenzen() {
        IpBanRecord ban = new IpBanRecord("10.0.0.1", "Grund", "Mod", ERSTELLT, ABLAUF);
        assertTrue(ban.istAktiv(ABLAUF - 1));
        assertFalse(ban.istAktiv(ABLAUF));
        assertTrue(ban.istAbgelaufen(ABLAUF));
        assertFalse(ban.istAbgelaufen(ABLAUF - 1));

        IpMuteRecord mute = new IpMuteRecord("10.0.0.1", "Grund", "Mod", ERSTELLT, ABLAUF);
        assertTrue(mute.istAktiv(ABLAUF - 1));
        assertFalse(mute.istAktiv(ABLAUF));
        assertTrue(mute.istAbgelaufen(ABLAUF));
        assertFalse(mute.istAbgelaufen(ABLAUF - 1));

        IpTimeoutRecord timeout = new IpTimeoutRecord("10.0.0.1", "Grund", "Mod", ERSTELLT, ABLAUF);
        assertTrue(timeout.istAktiv(ABLAUF - 1));
        assertFalse(timeout.istAktiv(ABLAUF));
        assertTrue(timeout.istAbgelaufen(ABLAUF));
        assertFalse(timeout.istAbgelaufen(ABLAUF - 1));

        assertThrows(IllegalArgumentException.class,
                () -> new IpBanRecord("10.0.0.1", "Grund", "Mod", ERSTELLT, ERSTELLT));
        assertThrows(IllegalArgumentException.class,
                () -> new IpMuteRecord("10.0.0.1", "Grund", "Mod", ERSTELLT, ERSTELLT));
        assertThrows(IllegalArgumentException.class,
                () -> new IpTimeoutRecord("10.0.0.1", "Grund", "Mod", ERSTELLT, ERSTELLT));
    }

    @Test
    @DisplayName("Grund- und Zeitgrenzen der Konstruktoren sind exakt")
    void konstruktorGrenzen() {
        String max = "x".repeat(1024);
        assertTrue(new BanRecord(UUID_A, "Spieler", max, "Mod", 1L, null, false).grund().length() == 1024);
        assertTrue(new MuteRecord(UUID_A, "Spieler", max, "Mod", 1L, 2L).grund().length() == 1024);
        assertThrows(IllegalArgumentException.class,
                () -> new BanRecord(UUID_A, "Spieler", max + "x", "Mod", 1L, null, false));
        assertThrows(IllegalArgumentException.class,
                () -> new MuteRecord(UUID_A, "Spieler", max + "x", "Mod", 1L, 2L));
        assertThrows(IllegalArgumentException.class,
                () -> new BanRecord(UUID_A, "Spieler", "Grund", "Mod", 0L, null, false));
        assertThrows(IllegalArgumentException.class,
                () -> new MuteRecord(UUID_A, "Spieler", "Grund", "Mod", 0L, 2L));
    }

    @Test
    @DisplayName("Ban: Sentinel-Werte werden zu permanent, Grenze exakt")
    void banGrenze() {
        BanRecord ban = new BanRecord(UUID_A, "Spieler", "Grund", "Mod", ERSTELLT, ABLAUF, false);
        assertTrue(ban.istAktiv(ABLAUF - 1));
        assertFalse(ban.istAktiv(ABLAUF));
        assertTrue(ban.istAbgelaufen(ABLAUF));
        assertFalse(ban.istAbgelaufen(ABLAUF - 1));

        BanRecord sentinel = new BanRecord(UUID_A, "Spieler", "Grund", "Mod", ERSTELLT, -1L, false);
        assertTrue(sentinel.istPermanent());
        assertTrue(sentinel.istAktiv(Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,
                () -> new BanRecord(UUID_A, "Spieler", "Grund", "Mod", ERSTELLT, ERSTELLT, false));
    }
}
