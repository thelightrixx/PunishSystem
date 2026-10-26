package de.lightrixx.punish.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PunishBridgeChatfilterSchalterTest {

    @Test
    void dateiAn_bautImmer() {
        assertTrue(PunishBridge.sollChatfilterGebautWerden(true, null, null));
        assertTrue(PunishBridge.sollChatfilterGebautWerden(true, Boolean.FALSE, null));
        assertTrue(PunishBridge.sollChatfilterGebautWerden(true, Boolean.TRUE, null));
    }

    @Test
    void dateiAus_laufzeitAn_bautTrotzdem() {
        assertTrue(PunishBridge.sollChatfilterGebautWerden(false, Boolean.TRUE, null));
        assertTrue(PunishBridge.sollChatfilterGebautWerden(false, Boolean.TRUE, Boolean.FALSE));
    }

    @Test
    void dateiAus_ohneSchalter_bautNicht() {
        assertFalse(PunishBridge.sollChatfilterGebautWerden(false, null, null));
        assertFalse(PunishBridge.sollChatfilterGebautWerden(false, null, Boolean.FALSE));
        assertFalse(PunishBridge.sollChatfilterGebautWerden(false, Boolean.FALSE, null));
    }

    @Test
    void laufzeitAn_bleibtUeberReloadErhalten() {
        assertTrue(PunishBridge.sollChatfilterGebautWerden(false, null, Boolean.TRUE));
    }

    @Test
    void explizitAus_schlaegtAltenSchalter() {
        assertFalse(PunishBridge.sollChatfilterGebautWerden(false, Boolean.FALSE, Boolean.TRUE));
    }
}
