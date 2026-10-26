package de.lightrixx.punish.bridge;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportTabSchutzTest {

    private static Player spieler(boolean op, boolean ignore) {
        Player spieler = mock(Player.class);
        when(spieler.isOp()).thenReturn(op);
        when(spieler.hasPermission("punish.ignore.*")).thenReturn(ignore);
        when(spieler.hasPermission(org.mockito.ArgumentMatchers.startsWith("punish.ignore.")))
                .thenReturn(ignore);
        return spieler;
    }

    @Test
    void opGeschuetzt() {
        assertTrue(ReportWeiterleitung.istGeschuetzt(spieler(true, false)));
    }

    @Test
    void ignoreGeschuetzt() {
        assertTrue(ReportWeiterleitung.istGeschuetzt(spieler(false, true)));
    }

    @Test
    void normalNichtGeschuetzt() {
        assertFalse(ReportWeiterleitung.istGeschuetzt(spieler(false, false)),
                "Normale Spieler bleiben im /report-Tab sichtbar");
    }

    @Test
    void nullGeschuetzt() {
        assertTrue(ReportWeiterleitung.istGeschuetzt(null));
    }
}
