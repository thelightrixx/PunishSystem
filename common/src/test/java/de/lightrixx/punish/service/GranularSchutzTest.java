package de.lightrixx.punish.service;

import de.lightrixx.punish.core.OfflineVerzeichnis;
import de.lightrixx.punish.core.OnlineSpieler;
import de.lightrixx.punish.core.Permissions;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.hook.LuckPermsZugang;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GranularSchutzTest {

    private static ProtectionService schutzMitRechten(UUID uuid, String name, boolean wildcard, String... nodes) {
        var online = new OnlineSpieler(uuid, name, "1.2.3.4", false);
        SpielerVerzeichnis verzeichnis = mock(SpielerVerzeichnis.class);
        when(verzeichnis.onlineSpieler(uuid)).thenReturn(Optional.of(online));
        when(verzeichnis.hatRecht(eq(uuid), eq(Permissions.IGNORE_STAR.node()))).thenReturn(wildcard);
        for (var typ : ProtectionService.IgnoreTyp.values()) {
            boolean hat = false;
            for (String node : nodes) {
                if (typ.node().equals(node)) {
                    hat = true;
                    break;
                }
            }
            when(verzeichnis.hatRecht(eq(uuid), eq(typ.node()))).thenReturn(hat);
        }
        OfflineVerzeichnis offline = mock(OfflineVerzeichnis.class);
        when(offline.findeNachUuid(any(UUID.class))).thenReturn(Optional.empty());
        LuckPermsZugang zugang = mock(LuckPermsZugang.class);
        return new ProtectionService(verzeichnis, offline, zugang);
    }

    @Test
    void knotenNamenSindAsciiUndErwartet() {
        assertEquals("punish.ignore.*", Permissions.IGNORE_STAR.node());
        assertEquals("punish.ignore.ban", ProtectionService.IgnoreTyp.BAN.node());
        assertEquals("punish.ignore.mute", ProtectionService.IgnoreTyp.MUTE.node());
        assertEquals("punish.ignore.timeout", ProtectionService.IgnoreTyp.TIMEOUT.node());
        assertEquals("punish.ignore.kick", ProtectionService.IgnoreTyp.KICK.node());
        assertEquals("punish.ignore.warn", ProtectionService.IgnoreTyp.WARN.node());
        assertEquals("punish.ignore.verstoss", ProtectionService.IgnoreTyp.VERSTOSS.node());
        assertEquals("punish.ignore.note", ProtectionService.IgnoreTyp.NOTE.node());
        assertEquals("punish.ignore.alts", ProtectionService.IgnoreTyp.ALTS.node());
        assertEquals("punish.ignore.report", ProtectionService.IgnoreTyp.REPORT.node());
    }

    @Test
    void nurPassenderTypSchuetzt() {
        UUID uuid = UUID.randomUUID();
        ProtectionService schutz = schutzMitRechten(uuid, "Ziel", false, "punish.ignore.ban");
        assertTrue(schutz.istGeschuetzt(uuid, ProtectionService.IgnoreTyp.BAN));
        assertFalse(schutz.istGeschuetzt(uuid, ProtectionService.IgnoreTyp.MUTE));
        assertFalse(schutz.istGeschuetzt(uuid, ProtectionService.IgnoreTyp.KICK));
    }

    @Test
    void wildcardSchuetztAlleTypen() {
        UUID uuid = UUID.randomUUID();
        ProtectionService schutz = schutzMitRechten(uuid, "Ziel", true);
        for (var typ : ProtectionService.IgnoreTyp.values()) {
            assertTrue(schutz.istGeschuetzt(uuid, typ), "Wildcard muss " + typ + " schützen");
        }
    }

    @Test
    void altesGlobalesIgnoreSchuetztNichtMehr() {
        UUID uuid = UUID.randomUUID();
        var online = new OnlineSpieler(uuid, "Ziel", "1.2.3.4", false);
        SpielerVerzeichnis verzeichnis = mock(SpielerVerzeichnis.class);
        when(verzeichnis.onlineSpieler(uuid)).thenReturn(Optional.of(online));
        when(verzeichnis.hatRecht(eq(uuid), anyString())).thenAnswer(inv -> {
            String knoten = inv.getArgument(1);
            return "punish.ignore".equals(knoten);
        });
        OfflineVerzeichnis offline = mock(OfflineVerzeichnis.class);
        when(offline.findeNachUuid(any(UUID.class))).thenReturn(Optional.empty());
        LuckPermsZugang zugang = mock(LuckPermsZugang.class);
        ProtectionService schutz = new ProtectionService(verzeichnis, offline, zugang);
        assertFalse(schutz.istGeschuetzt(uuid, ProtectionService.IgnoreTyp.BAN));
        assertFalse(schutz.istGeschuetzt(uuid));
    }

    @Test
    void offlineWildcardOderTypSchuetzt() {
        UUID uuid = UUID.randomUUID();
        var extern = new OfflineVerzeichnis.ExternSpieler(
                uuid, "Alt", true, false, System.currentTimeMillis(), true);
        SpielerVerzeichnis verzeichnis = mock(SpielerVerzeichnis.class);
        when(verzeichnis.onlineSpieler(uuid)).thenReturn(Optional.empty());
        OfflineVerzeichnis offline = mock(OfflineVerzeichnis.class);
        when(offline.findeNachUuid(uuid)).thenReturn(Optional.of(extern));
        when(offline.istMainThread()).thenReturn(false);
        LuckPermsZugang zugang = mock(LuckPermsZugang.class);
        when(zugang.isAvailable()).thenReturn(true);
        when(zugang.offlineStatusBlocking(eq(uuid), anyString()))
                .thenReturn(LuckPermsZugang.OfflineStatus.NICHT_GESCHUETZT);
        when(zugang.offlineStatusBlocking(eq(uuid), eq("punish.ignore.*")))
                .thenReturn(LuckPermsZugang.OfflineStatus.NICHT_GESCHUETZT);
        when(zugang.offlineStatusBlocking(eq(uuid), eq("punish.ignore.mute")))
                .thenReturn(LuckPermsZugang.OfflineStatus.GESCHUETZT);
        ProtectionService schutz = new ProtectionService(verzeichnis, offline, zugang);
        assertEquals(ProtectionService.SchutzErgebnis.GESCHUETZT,
                schutz.pruefeSchutz(uuid, ProtectionService.IgnoreTyp.MUTE));
        assertEquals(ProtectionService.SchutzErgebnis.NICHT_GESCHUETZT,
                schutz.pruefeSchutz(uuid, ProtectionService.IgnoreTyp.BAN));
    }
}
