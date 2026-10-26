package de.lightrixx.punish.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VerstossKennungTest {

    @Test
    void kennungFolgtFormatGrundZeitAusfuehrer() {
        long zeitpunkt = 1_725_469_200_000L;
        VerstossRecord verstoss = new VerstossRecord(3, "Steve", "Beleidigung", "Moderator", zeitpunkt);
        String erwartet = "Beleidigung_"
                + de.lightrixx.punish.util.ZeitFormat.formatZeitpunkt(zeitpunkt)
                + "_Moderator#3";
        assertEquals(erwartet, VerstossKennung.kennung(verstoss));
    }

    @Test
    void kennungLehntNullAb() {
        assertThrows(NullPointerException.class, () -> VerstossKennung.kennung(null));
    }
}
