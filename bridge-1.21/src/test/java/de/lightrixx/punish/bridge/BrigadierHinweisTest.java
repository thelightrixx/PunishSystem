package de.lightrixx.punish.bridge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BrigadierHinweisTest {

    private BridgeBefehlWeiterleitung tunnelFuer(String ziel) {
        KanalClient kanal = mock(KanalClient.class);
        return new BridgeBefehlWeiterleitung(kanal, ziel, "punish.test", true, null);
    }

    @Test
    @DisplayName("Ban-Hinweise folgen der Argument-Position")
    void banHinweiseNachPosition() {
        BridgeBefehlWeiterleitung ban = tunnelFuer("ban");
        assertEquals("Spieler", ban.hinweisFuer("Notch", new String[]{"No"}));
        assertEquals("Dauer", ban.hinweisFuer("7d", new String[]{"Notch", "7"}));
        assertEquals("Grund", ban.hinweisFuer("Cheating", new String[]{"Notch", "7d", "Che"}));
        assertEquals("Gültigkeit: ganzes Netzwerk",
                ban.hinweisFuer("--global", new String[]{"Notch", "7d", "Cheating", ""}));
        assertEquals("Still: keine Broadcasts",
                ban.hinweisFuer("-s", new String[]{"Notch", "7d", "Cheating", ""}));
    }

    @Test
    @DisplayName("Entstrafe-Hinweise benennen die aktive Sperre")
    void entstrafeHinweise() {
        assertEquals("Aktive Sperre", tunnelFuer("pardon").hinweisFuer("Notch", new String[]{""}));
        assertEquals("Aktive Stummschaltung",
                tunnelFuer("unmute").hinweisFuer("Notch", new String[]{""}));
        assertEquals("Aktive IP-Sperre",
                tunnelFuer("unban-ip").hinweisFuer("1.2.3.4", new String[]{""}));
    }

    @Test
    @DisplayName("Massen- und Sonderbefehle haben eigene Hinweise")
    void massenUndSonderHinweise() {
        assertEquals("Dauer", tunnelFuer("ban-all").hinweisFuer("7d", new String[]{"7"}));
        assertEquals("Bestätigung erforderlich",
                tunnelFuer("ban-all").hinweisFuer("confirm", new String[]{"7d", "con"}));
        assertEquals("Hinzufügen",
                tunnelFuer("verstoss").hinweisFuer("add", new String[]{"a"}));
        assertEquals("Spieler",
                tunnelFuer("verstoss").hinweisFuer("Notch", new String[]{"add", "No"}));
        assertEquals("Eigene Daten",
                tunnelFuer("punish-status").hinweisFuer("self", new String[]{"s"}));
        assertEquals("Spieler oder IP",
                tunnelFuer("punish-delete-ip").hinweisFuer("Notch", new String[]{"No"}));
    }

    @Test
    @DisplayName("zuArgs teilt Brigadier-Rest robust")
    void zuArgsRobust() {
        assertArrayEquals(new String[0], BridgeBrigadierBefehle.zuArgs(null));
        assertArrayEquals(new String[0], BridgeBrigadierBefehle.zuArgs("   "));
        assertArrayEquals(new String[]{"Notch", "7d", "Cheating"},
                BridgeBrigadierBefehle.zuArgs("Notch  7d Cheating "));
    }
}
