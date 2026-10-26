package de.lightrixx.punish.bridge.gui;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GuiBetaTest {

    @Test
    void hauptTitel_traegtBetaMarker() {
        String text = MiniMessage.miniMessage().serialize(GuiMenues.titel(GuiMenues.BETA_TITEL + "  •  mute Max"));
        assertTrue(text.contains("(Beta)"), "GUI-Titel ohne Beta-Marker: " + text);
    }

    @Test
    void altsLore_weistAufHeuristikHin() {
        assertTrue(GuiMenues.BETA_LORE.contains("Beta"), "Alts-Lore ohne Beta-Marker: " + GuiMenues.BETA_LORE);
        String text = MiniMessage.miniMessage().serialize(GuiMenues.name(GuiMenues.BETA_LORE));
        assertTrue(text.contains("Heuristik"), "Alts-Lore ohne Heuristik-Hinweis: " + text);
    }
}
