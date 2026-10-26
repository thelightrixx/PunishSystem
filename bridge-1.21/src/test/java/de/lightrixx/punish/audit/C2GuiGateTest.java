package de.lightrixx.punish.audit;

import de.lightrixx.punish.bridge.BridgeKonfiguration;
import de.lightrixx.punish.bridge.KanalClient;
import de.lightrixx.punish.bridge.gui.GuiDatenDienst;
import de.lightrixx.punish.bridge.gui.GuiManager;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class C2GuiGateTest {

    private static GuiManager manager() {
        return new GuiManager(Mockito.mock(Plugin.class),
                Mockito.mock(KanalClient.class),
                Mockito.mock(BridgeKonfiguration.class),
                Mockito.mock(GuiDatenDienst.class));
    }

    @Test
    @DisplayName("F2: Spieler ohne punish.gui darf die GUI nicht öffnen")
    void spielerOhneGuiRechtWirdAbgewiesen() {
        Player spieler = Mockito.mock(Player.class);
        Mockito.when(spieler.hasPermission("punish.gui")).thenReturn(false);
        assertFalse(manager().darfOeffnen(spieler),
                "punish.gui (default: op) muss die GUI für Unberechtigte sperren");
    }

    @Test
    @DisplayName("F2: Spieler mit punish.gui darf die GUI öffnen")
    void spielerMitGuiRechtDarfOeffnen() {
        Player spieler = Mockito.mock(Player.class);
        Mockito.when(spieler.hasPermission("punish.gui")).thenReturn(true);
        assertTrue(manager().darfOeffnen(spieler));
    }

    @Test
    @DisplayName("F2: ohne Absender kein Öffnen")
    void ohneSpielerKeinOeffnen() {
        assertFalse(manager().darfOeffnen(null));
    }
}
