package de.lightrixx.punish.service;

import de.lightrixx.punish.core.OfflineVerzeichnis;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.hook.LuckPermsZugang;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SchutzNieGesehenTest {

    private static ProtectionService schutzMit(
            OfflineVerzeichnis.ExternSpieler extern, LuckPermsZugang zugang) {
        SpielerVerzeichnis verzeichnis = mock(SpielerVerzeichnis.class);
        when(verzeichnis.onlineSpieler(any(UUID.class))).thenReturn(Optional.empty());
        OfflineVerzeichnis offline = mock(OfflineVerzeichnis.class);
        if (extern == null) {
            when(offline.findeNachUuid(any(UUID.class))).thenReturn(Optional.empty());
        } else {
            when(offline.findeNachUuid(extern.uuid())).thenReturn(Optional.of(extern));
        }
        when(offline.istMainThread()).thenReturn(true);
        return new ProtectionService(verzeichnis, offline, zugang);
    }

    private static LuckPermsZugang zugangUnbekannt() {
        LuckPermsZugang zugang = mock(LuckPermsZugang.class);
        when(zugang.isAvailable()).thenReturn(true);
        when(zugang.offlineStatusCachedOnly(any(UUID.class), anyString()))
                .thenReturn(LuckPermsZugang.OfflineStatus.UNBEKANNT);
        when(zugang.offlineStatusFuerMehrere(any(UUID.class), anyList()))
                .thenAnswer(inv -> {
                    java.util.List<String> nodes = inv.getArgument(1);
                    return java.util.Collections.nCopies(nodes.size(),
                            LuckPermsZugang.OfflineStatus.UNBEKANNT);
                });
        return zugang;
    }

    @Test
    void nieGesehenGiltAlsNichtGeschuetzt() {
        UUID uuid = UUID.randomUUID();
        var extern = new OfflineVerzeichnis.ExternSpieler(
                uuid, "Neu", false, false, System.currentTimeMillis(), true);
        LuckPermsZugang zugang = zugangUnbekannt();

        ProtectionService schutz = schutzMit(extern, zugang);
        assertEquals(ProtectionService.SchutzErgebnis.NICHT_GESCHUETZT,
                schutz.pruefeSchutz(uuid));
        assertEquals(Optional.of(ProtectionService.SchutzErgebnis.NICHT_GESCHUETZT),
                schutz.pruefeSchutzSofort(uuid));

        verify(zugang, never()).offlineStatusCachedOnly(any(UUID.class), anyString());
    }

    @Test
    void opSchlaegtNieGesehen() {
        UUID uuid = UUID.randomUUID();
        var extern = new OfflineVerzeichnis.ExternSpieler(
                uuid, "Chef", false, false, System.currentTimeMillis(), true, true);
        LuckPermsZugang zugang = mock(LuckPermsZugang.class);

        ProtectionService schutz = schutzMit(extern, zugang);
        assertEquals(ProtectionService.SchutzErgebnis.NICHT_GESCHUETZT,
                schutz.pruefeSchutz(uuid));
        assertEquals(Optional.of(ProtectionService.SchutzErgebnis.NICHT_GESCHUETZT),
                schutz.pruefeSchutzSofort(uuid));
    }

    @Test
    void gespieltOhneCacheBleibtUnsicher() {
        UUID uuid = UUID.randomUUID();
        var extern = new OfflineVerzeichnis.ExternSpieler(
                uuid, "Alt", true, false, System.currentTimeMillis(), true);
        LuckPermsZugang zugang = zugangUnbekannt();

        ProtectionService schutz = schutzMit(extern, zugang);
        assertEquals(ProtectionService.SchutzErgebnis.UNSICHER,
                schutz.pruefeSchutz(uuid));
        assertEquals(Optional.empty(), schutz.pruefeSchutzSofort(uuid));
    }

    @Test
    void ohneLuckPermsGespieltBleibtUnsicher() {
        UUID uuid = UUID.randomUUID();
        var extern = new OfflineVerzeichnis.ExternSpieler(
                uuid, "Alt", true, false, System.currentTimeMillis(), true);
        LuckPermsZugang zugang = mock(LuckPermsZugang.class);
        when(zugang.isAvailable()).thenReturn(false);

        ProtectionService schutz = schutzMit(extern, zugang);
        assertEquals(ProtectionService.SchutzErgebnis.UNSICHER,
                schutz.pruefeSchutz(uuid));
        assertEquals(Optional.of(ProtectionService.SchutzErgebnis.UNSICHER),
                schutz.pruefeSchutzSofort(uuid));
        assertEquals(true, schutz.istGeschuetzt(uuid),
                "fail-closed muss bei UNSICHER blockieren");
    }

    @Test
    void ohneLuckPermsNieGesehenBleibtFrei() {
        UUID uuid = UUID.randomUUID();
        var extern = new OfflineVerzeichnis.ExternSpieler(
                uuid, "Neu", false, false, System.currentTimeMillis(), true);
        LuckPermsZugang zugang = mock(LuckPermsZugang.class);
        when(zugang.isAvailable()).thenReturn(false);

        ProtectionService schutz = schutzMit(extern, zugang);
        assertEquals(ProtectionService.SchutzErgebnis.NICHT_GESCHUETZT,
                schutz.pruefeSchutz(uuid));
    }
}
