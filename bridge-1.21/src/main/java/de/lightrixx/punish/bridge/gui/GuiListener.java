package de.lightrixx.punish.bridge.gui;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Objects;

public final class GuiListener implements Listener {

    private final GuiManager manager;

    public GuiListener(GuiManager manager) {
        this.manager = Objects.requireNonNull(manager, "Der Manager darf nicht null sein.");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void beiKlick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof GuiMenues.GuiHolder holder)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player spieler)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() == null
                || !event.getClickedInventory().equals(event.getView().getTopInventory())) {
            return;
        }
        try {
            manager.klick(spieler, holder, event.getSlot());
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(GuiListener.class).debug("GUI-Klick konnte nicht verarbeitet werden.", e);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void beiZiehen(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof GuiMenues.GuiHolder)) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void beiQuit(PlayerQuitEvent event) {
        try {
            manager.schliessen(event.getPlayer());
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(GuiListener.class).debug("GUI-Sitzung konnte nicht geschlossen werden.", e);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void beiChat(AsyncChatEvent event) {
        Player spieler = event.getPlayer();
        String klar;
        try {
            klar = GuiManager.klartext(event.message());
        } catch (Exception e) {
            return;
        }
        boolean wartete = manager.wartetAufEingabe(spieler);
        boolean verbraucht;
        try {
            verbraucht = manager.chatEingabe(spieler, klar);
        } catch (Exception e) {
            if (wartete) {
                event.setCancelled(true);
            }
            return;
        }
        if (verbraucht) {
            event.setCancelled(true);
        }
    }

}
