package de.lightrixx.punish.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PunishBridgeTest {

    @Test
    void rebuildNoetig_ohneDienstImmer() {
        assertTrue(PunishBridge.rebuildNoetig("", "", false));
        assertTrue(PunishBridge.rebuildNoetig("a", "a", false));
    }

    @Test
    void rebuildNoetig_gleicherStandKeinNeubau() {
        assertFalse(PunishBridge.rebuildNoetig("abc", "abc", true));
    }

    @Test
    void rebuildNoetig_geaenderterStandNeuBau() {
        assertTrue(PunishBridge.rebuildNoetig("abc", "def", true));
        assertTrue(PunishBridge.rebuildNoetig("", "def", true));
        assertTrue(PunishBridge.rebuildNoetig(null, "def", true));
        assertTrue(PunishBridge.rebuildNoetig("abc", null, true));
    }
}
