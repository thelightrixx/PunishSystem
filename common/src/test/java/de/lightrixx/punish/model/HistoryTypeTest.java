package de.lightrixx.punish.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HistoryTypeTest {

    @Test
    void editTypenGeltenAlsAufhebung() {
        assertTrue(HistoryType.BAN_EDIT.istAufhebung());
        assertTrue(HistoryType.MUTE_EDIT.istAufhebung());
        assertTrue(HistoryType.TIMEOUT_EDIT.istAufhebung());
        assertTrue(HistoryType.IP_BAN_EDIT.istAufhebung());
    }

    @Test
    void klassischeAufhebungenBleibenAufhebungen() {
        assertTrue(HistoryType.PARDON.istAufhebung());
        assertTrue(HistoryType.PARDON_ALL.istAufhebung());
        assertTrue(HistoryType.UNMUTE.istAufhebung());
        assertTrue(HistoryType.UNTIMEOUT.istAufhebung());
        assertTrue(HistoryType.UNBAN_IP.istAufhebung());
        assertTrue(HistoryType.AUTO_PARDON.istAufhebung());
        assertTrue(HistoryType.AUTO_BANN_EXPIRED.istAufhebung());
        assertTrue(HistoryType.RESET.istAufhebung());
    }

    @Test
    void aktiveStrafenSindKeineAufhebungen() {
        assertFalse(HistoryType.BAN.istAufhebung());
        assertFalse(HistoryType.PERMABANN.istAufhebung());
        assertFalse(HistoryType.MUTE.istAufhebung());
        assertFalse(HistoryType.TIMEOUT.istAufhebung());
        assertFalse(HistoryType.KICK.istAufhebung());
        assertFalse(HistoryType.BAN_IP.istAufhebung());
        assertFalse(HistoryType.AUTO_BANN.istAufhebung());
    }

    @Test
    void istEditNurFuerEditTypen() {
        assertTrue(HistoryType.BAN_EDIT.istEdit());
        assertTrue(HistoryType.MUTE_EDIT.istEdit());
        assertTrue(HistoryType.TIMEOUT_EDIT.istEdit());
        assertTrue(HistoryType.IP_BAN_EDIT.istEdit());
        assertFalse(HistoryType.BAN.istEdit());
        assertFalse(HistoryType.PARDON.istEdit());
        assertFalse(HistoryType.UNMUTE.istEdit());
    }
}
