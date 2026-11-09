package de.lightrixx.punish.bridge.gui;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void klickLaeuftErstImNaechstenTickUndNurImSelbenInventar(boolean inventarNochOffen) {
        var plugin = mock(Plugin.class);
        var scheduler = mock(BukkitScheduler.class);
        var spieler = mock(Player.class);
        var ansicht = mock(InventoryView.class);
        var inventar = mock(Inventory.class);
        var holder = new GuiMenues.GuiHolder(GuiMenues.Menue.BESTAETIGEN);
        var event = mock(InventoryClickEvent.class);
        when(event.getView()).thenReturn(ansicht);
        when(ansicht.getTopInventory()).thenReturn(inventar);
        when(inventar.getHolder()).thenReturn(holder);
        when(event.getClickedInventory()).thenReturn(inventar);
        when(event.getWhoClicked()).thenReturn(spieler);
        when(event.getSlot()).thenReturn(15);
        when(spieler.isOnline()).thenReturn(true);
        when(spieler.getOpenInventory()).thenReturn(ansicht);

        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            var manager = spy(new GuiManager(plugin, mock(de.lightrixx.punish.bridge.KanalClient.class),
                    mock(de.lightrixx.punish.bridge.BridgeKonfiguration.class), mock(GuiDatenDienst.class)));
            doReturn(true).when(manager).klick(spieler, holder, 15);

            new GuiListener(manager).beiKlick(event);

            verify(event).setCancelled(true);
            verify(manager, never()).klick(spieler, holder, 15);
            var aufgabe = ArgumentCaptor.forClass(Runnable.class);
            verify(scheduler).runTask(eq(plugin), aufgabe.capture());
            if (!inventarNochOffen) {
                when(ansicht.getTopInventory()).thenReturn(mock(Inventory.class));
            }
            aufgabe.getValue().run();
            verify(manager, times(inventarNochOffen ? 1 : 0)).klick(spieler, holder, 15);
        }
    }
}
