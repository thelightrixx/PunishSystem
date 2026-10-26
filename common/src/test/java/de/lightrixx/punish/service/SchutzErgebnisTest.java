package de.lightrixx.punish.service;

import de.lightrixx.punish.hook.LuckPermsZugang;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SchutzErgebnisTest {

    @Test
    void lpStatusWirdKorrektAbgebildet() {
        assertEquals(ProtectionService.SchutzErgebnis.GESCHUETZT,
                ProtectionService.entscheideOfflineSchutz(LuckPermsZugang.OfflineStatus.GESCHUETZT));
        assertEquals(ProtectionService.SchutzErgebnis.NICHT_GESCHUETZT,
                ProtectionService.entscheideOfflineSchutz(LuckPermsZugang.OfflineStatus.NICHT_GESCHUETZT));
        assertEquals(ProtectionService.SchutzErgebnis.UNSICHER,
                ProtectionService.entscheideOfflineSchutz(LuckPermsZugang.OfflineStatus.UNBEKANNT));
        assertEquals(ProtectionService.SchutzErgebnis.UNSICHER,
                ProtectionService.entscheideOfflineSchutz(null));
    }

    @Test
    void bestaetigtGeschuetzteBlockenImmer() {
        assertTrue(ProtectionService.blockiertBei(ProtectionService.SchutzErgebnis.GESCHUETZT, true));
        assertTrue(ProtectionService.blockiertBei(ProtectionService.SchutzErgebnis.GESCHUETZT, false));
    }

    @Test
    void unsichereBlockenNurFailClosed() {
        assertTrue(ProtectionService.blockiertBei(ProtectionService.SchutzErgebnis.UNSICHER, true),
                "Team-Server: Unsicherheit muss blocken");
        assertFalse(ProtectionService.blockiertBei(ProtectionService.SchutzErgebnis.UNSICHER, false),
                "Kleine Server: Unsicherheit darf Offline-Banns nicht verhindern");
    }

    @Test
    void ungeschuetzteBlockenNie() {
        assertFalse(ProtectionService.blockiertBei(ProtectionService.SchutzErgebnis.NICHT_GESCHUETZT, true));
        assertFalse(ProtectionService.blockiertBei(ProtectionService.SchutzErgebnis.NICHT_GESCHUETZT, false));
        assertFalse(ProtectionService.blockiertBei(null, true));
    }
}
