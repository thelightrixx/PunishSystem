package de.lightrixx.punish.bridge.gui;

import de.lightrixx.punish.core.RangBuendel;
import de.lightrixx.punish.net.Protokoll;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;

public final class GuiMenues {

    public enum Menue {
        HAUPT, SPIELER, AKTION, GRUND, DAUER, SCOPE, BESTAETIGEN, STATUS, HISTORY, REPORTS,
        REPORT_DETAIL, DOSSIER
    }

    public record GuiHolder(Menue menue) implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private static volatile String titelPrefix = "Punish";

    static final String BETA_TITEL = "Menü (Beta)";
    static final String BETA_LORE = "<yellow>Beta: IP-Heuristik, kein Beweis";

    public static void setTitelPrefix(String prefix) {
        if (prefix != null && !prefix.isBlank()) {
            titelPrefix = prefix.trim().length() > 24 ? prefix.trim().substring(0, 24) : prefix.trim();
        }
    }

    private GuiMenues() {
    }

    static Component titel(String text) {
        String sichererTitel = escapeTags(text);
        String sicheresPrefix = escapeTags(titelPrefix);
        try {
            return MM.deserialize("<dark_gray>[<red>" + sicheresPrefix + "<dark_gray>] <white>" + sichererTitel);
        } catch (Exception e) {
            return Component.text("[" + titelPrefix + "] " + text);
        }
    }

    static Component name(String mini) {
        try {
            return MM.deserialize(mini);
        } catch (Exception e) {
            return Component.text(mini);
        }
    }

    static ItemStack item(Material material, String anzeigename, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(name(anzeigename));
            if (lore != null && !lore.isEmpty()) {
                List<Component> zeilen = new ArrayList<>();
                for (String eintrag : lore) {
                    zeilen.add(name(eintrag));
                }
                meta.lore(zeilen);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    static ItemStack kopf(String spielerName, String anzeigename, List<String> lore) {
        ItemStack stack = new ItemStack(Material.PLAYER_HEAD);
        try {
            SkullMeta meta = (SkullMeta) stack.getItemMeta();
            if (meta != null) {
                meta.displayName(name(anzeigename));
                if (lore != null && !lore.isEmpty()) {
                    List<Component> zeilen = new ArrayList<>();
                    for (String eintrag : lore) {
                        zeilen.add(name(eintrag));
                    }
                    meta.lore(zeilen);
                }
                try {
                    var offline = Bukkit.getOfflinePlayer(spielerName);
                    meta.setOwningPlayer(offline);
                } catch (Exception e) {
                    org.slf4j.LoggerFactory.getLogger(GuiMenues.class).debug("Spielerkopf konnte nicht gesetzt werden.", e);
                }
                stack.setItemMeta(meta);
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(GuiMenues.class).debug("Spielerkopf konnte nicht gebaut werden.", e);
        }
        return stack;
    }

    public static Inventory haupt(GuiSitzung sitzung) {
        return haupt(sitzung, true);
    }

    public static Inventory haupt(GuiSitzung sitzung, boolean darfAlts) {
        Inventory inv = Bukkit.createInventory(new GuiHolder(Menue.HAUPT), 27, titel(BETA_TITEL + "  •  " + sitzung.vorschau()));
        String ziel = sitzung.ziel().isBlank() ? "<gray>nicht gewählt" : "<white>" + escape(sitzung.ziel());
        String aktion = sitzung.aktion() == null ? "<gray>nicht gewählt" : "<white>" + sitzung.aktion().befehl();
        inv.setItem(10, item(Material.PLAYER_HEAD, "<yellow>1. Spieler: " + ziel,
                List.of("<gray>Klick: Auswahl", "<gray>Aktuell: " + ziel)));
        inv.setItem(11, item(Material.IRON_SWORD, "<yellow>2. Aktion: " + aktion,
                List.of("<gray>ban, mute, kick, timeout, freeze …", "<gray>Aktuell: " + aktion)));
        inv.setItem(12, item(Material.PAPER, "<yellow>3. Grund: <white>" + escape(sitzung.grund().isBlank() ? "-" : sitzung.grund()),
                List.of("<gray>Klick: Auswahl")));
        inv.setItem(13, item(Material.CLOCK, "<yellow>4. Dauer/Stufe: <white>" + escape(dauerText(sitzung)),
                List.of("<gray>Klick: Auswahl")));
        inv.setItem(14, item(Material.COMPASS, "<yellow>5. Scope: <white>" + escape(sitzung.scope()),
                List.of("<gray>--global oder Server")));
        inv.setItem(15, item(sitzung.bereitZumSenden() ? Material.GREEN_CONCRETE : Material.RED_CONCRETE,
                "<yellow>6. Senden: <white>" + escape(sitzung.vorschau()),
                sitzung.bereitZumSenden()
                        ? List.of("<green>Klick: an Proxy senden")
                        : List.of("<red>Noch nicht komplett", "<gray>Spieler+Aktion+Grund+Dauer")));
        inv.setItem(20, item(Material.ENDER_EYE, "<aqua>Status ansehen",
                List.of("<gray>Live vom Proxy")));
        inv.setItem(21, darfAlts
                ? item(Material.SPYGLASS, "<aqua>Alts ansehen",
                        sitzung.ziel().isBlank()
                                ? List.of("<red>Erst Spieler wählen", BETA_LORE)
                                : List.of("<gray>IP-Abgleich + Ban-Status", BETA_LORE,
                                        "<gray>Klick: /alts " + escape(sitzung.ziel())))
                : item(Material.GRAY_STAINED_GLASS, "<gray>Alts ansehen",
                        List.of("<red>Keine Berechtigung: punish.alts")));
        inv.setItem(22, item(Material.BOOK, "<aqua>History ansehen",
                List.of("<gray>Live vom Proxy")));
        inv.setItem(24, item(Material.WRITABLE_BOOK, "<aqua>Reports",
                List.of("<gray>Offene Meldungen")));
        inv.setItem(26, item(Material.BARRIER, "<red>Schließen", List.of()));
        return inv;
    }

    private static String dauerText(GuiSitzung sitzung) {
        if (sitzung.aktion() == GuiSitzung.Aktion.VERSTOSS) {
            return sitzung.stufe();
        }
        return sitzung.dauer().isBlank() ? "-" : sitzung.dauer();
    }

    public static Inventory spieler(List<String> namen, int seite, boolean mehr) {
        Inventory inv = Bukkit.createInventory(new GuiHolder(Menue.SPIELER), 54, titel("Spieler  •  Seite " + seite));
        int slot = 0;
        for (String n : namen) {
            if (slot >= 45) {
                break;
            }
            inv.setItem(slot++, kopf(n, "<white>" + escape(n), List.of("<gray>Klick: wählen")));
        }
        inv.setItem(45, item(Material.ARROW,
                seite > 1 ? "<gray>Seite -" : "<gray>Zurück",
                seite > 1 ? List.of("<gray>Eine Seite zurück") : List.of()));
        inv.setItem(48, item(Material.OAK_SIGN, "<yellow>Name eintippen",
                List.of("<gray>Klick, dann Namen in Chat schreiben")));
        inv.setItem(49, item(Material.BARRIER, "<red>Hauptmenü", List.of()));
        if (mehr) {
            inv.setItem(53, item(Material.ARROW, "<gray>Weiter", List.of()));
        }
        return inv;
    }

    public static Inventory aktion(Player sender) {
        Inventory inv = Bukkit.createInventory(new GuiHolder(Menue.AKTION), 27, titel("Aktion wählen"));
        GuiSitzung.Aktion[] alle = GuiSitzung.Aktion.values();
        Material[] icons = {Material.IRON_SWORD, Material.NETHERITE_SWORD, Material.PAPER,
                Material.STICK, Material.CLOCK, Material.ICE, Material.BOOK, Material.WRITABLE_BOOK,
                Material.MAP, Material.MILK_BUCKET, Material.GREEN_DYE, Material.LIME_DYE, Material.TORCH};
        for (int i = 0; i < alle.length && i < 13; i++) {
            var a = alle[i];
            boolean darf = sender == null
                    || RangBuendel.gewaehrt(sender::hasPermission, a.permission());
            inv.setItem(7 + i > 26 ? i : 7 + i,
                    item(darf ? icons[i % icons.length] : Material.GRAY_STAINED_GLASS,
                            (darf ? "<white>" : "<gray>") + a.befehl(),
                            darf ? List.of("<gray>Klick: wählen") : List.of("<red>Keine Berechtigung: " + a.permission())));
        }
        inv.setItem(22, item(Material.ARROW, "<gray>Zurück", List.of()));
        return inv;
    }

    public static Inventory grund(List<String> gruende) {
        return grund(gruende, java.util.Map.of());
    }

    public static Inventory grund(List<String> gruende, java.util.Map<String, String> vorlagen) {
        Inventory inv = Bukkit.createInventory(new GuiHolder(Menue.GRUND), 54, titel("Grund wählen"));
        int slot = 0;
        for (String g : gruende) {
            if (slot >= 44) {
                break;
            }
            if (g == null || g.isBlank()) {
                continue;
            }
            String empfehlung = "";
            if (vorlagen != null) {
                String treffer = vorlagen.get(g.trim().toLowerCase(java.util.Locale.ROOT));
                if (treffer != null && !treffer.isBlank()) {
                    empfehlung = treffer.trim();
                }
            }
            List<String> lore = empfehlung.isEmpty()
                    ? List.of("<gray>Klick: wählen")
                    : List.of("<gray>Klick: wählen", "<yellow>Empf. Dauer: <white>" + escape(empfehlung));
            inv.setItem(slot++, item(Material.PAPER, "<white>" + escape(g), lore));
        }
        inv.setItem(45, item(Material.ARROW, "<gray>Zurück", List.of()));
        inv.setItem(49, item(Material.OAK_SIGN, "<yellow>Custom-Grund eintippen",
                List.of("<gray>Klick, dann Grund in Chat schreiben")));
        return inv;
    }

    public static Inventory dauer(GuiSitzung sitzung, List<String> dauern) {
        boolean verstoss = sitzung.aktion() == GuiSitzung.Aktion.VERSTOSS;
        Inventory inv = Bukkit.createInventory(new GuiHolder(Menue.DAUER), 27,
                titel(verstoss ? "Stufe wählen" : "Dauer wählen"));
        if (verstoss) {
            inv.setItem(11, item(Material.GREEN_CONCRETE, "<white>low <gray>(1 Punkt)", List.of("<gray>Klick: wählen")));
            inv.setItem(13, item(Material.YELLOW_CONCRETE, "<white>medium <gray>(2 Punkte)", List.of("<gray>Klick: wählen")));
            inv.setItem(15, item(Material.RED_CONCRETE, "<white>high <gray>(3 Punkte)", List.of("<gray>Klick: wählen")));
        } else {
            int slot = 10;
            for (String d : dauern) {
                if (slot > 16) {
                    break;
                }
                if (slot == 13) {
                    slot++;
                }
                inv.setItem(slot++, item(Material.CLOCK, "<white>" + escape(d), List.of("<gray>Klick: wählen")));
            }
            inv.setItem(13, item(Material.OAK_SIGN, "<yellow>Custom-Dauer eintippen",
                    List.of("<gray>z. B. 1h30m, 7d, perm", "<gray>Klick, dann in Chat schreiben")));
        }
        inv.setItem(22, item(Material.ARROW, "<gray>Zurück", List.of()));
        return inv;
    }

    public static Inventory scope(List<String> server) {
        Inventory inv = Bukkit.createInventory(new GuiHolder(Menue.SCOPE), 27, titel("Scope wählen"));
        inv.setItem(11, item(Material.ENDER_EYE, "<white>--global", List.of("<gray>Netzwerkweit", "<gray>Klick: wählen")));
        int slot = 12;
        for (String s : server) {
            if (slot > 15) {
                break;
            }
            inv.setItem(slot++, item(Material.COMPASS, "<white>--" + escape(s), List.of("<gray>Nur Server " + escape(s))));
        }
        inv.setItem(22, item(Material.ARROW, "<gray>Zurück", List.of()));
        return inv;
    }

    public static Inventory bestaetigen(GuiSitzung sitzung) {
        return bestaetigen(sitzung, true);
    }

    public static Inventory bestaetigen(GuiSitzung sitzung, boolean darfSilent) {
        Inventory inv = Bukkit.createInventory(new GuiHolder(Menue.BESTAETIGEN), 27, titel("Bestätigen"));
        inv.setItem(11, item(Material.PAPER, "<white>" + escape(sitzung.vorschau()),
                List.of("<gray>Ziel: " + escape(sitzung.ziel()), "<gray>Aktion: " + (sitzung.aktion() == null ? "-" : sitzung.aktion().befehl()),
                        "<gray>Grund: " + escape(sitzung.grund()), "<gray>Dauer: " + escape(dauerText(sitzung)),
                        "<gray>Scope: " + escape(sitzung.scope()))));
        if (darfSilent) {
            inv.setItem(13, item(sitzung.silent() ? Material.GREEN_CONCRETE : Material.GRAY_CONCRETE,
                    sitzung.silent() ? "<green>Silent: AN (-s)" : "<gray>Silent: AUS",
                    List.of("<gray>Klick: umschalten", "<gray>Kein Team-Broadcast")));
        }
        inv.setItem(15, item(sitzung.bereitZumSenden() ? Material.GREEN_CONCRETE : Material.RED_CONCRETE,
                sitzung.bereitZumSenden() ? "<green>Bestätigen & senden" : "<red>Nicht komplett",
                List.of("<gray>" + escape(sitzung.vorschau()))));
        inv.setItem(22, item(Material.ARROW, "<gray>Zurück", List.of()));
        return inv;
    }

    public static Inventory liste(Menue menue, String kopf, Protokoll.GuiAntwort antwort) {
        int seiten = antwort == null ? 1 : Math.max(1, antwort.seiten());
        int seite = antwort == null ? 1 : Math.max(1, antwort.seite());
        Inventory inv = Bukkit.createInventory(new GuiHolder(menue), 54, titel(kopf + "  •  " + seite + "/" + seiten));
        if (antwort == null || !antwort.erfolg()) {
            String titelText = antwort == null
                    ? "<red>Proxy antwortet nicht"
                    : "<red>" + escape(antwort.nachricht());
            inv.setItem(22, item(Material.RED_CONCRETE,
                    titelText,
                    List.of("<gray>" + escape(antwort == null ? "Timeout (4s)." : antwort.nachricht()),
                            "<gray>Nutze Chat-Befehl als Fallback")));
            inv.setItem(49, item(Material.ARROW, "<gray>Zurück", List.of()));
            return inv;
        }
        int slot = 0;
        for (Protokoll.GuiZeile zeile : antwort.eintraege()) {
            if (slot >= 45) {
                break;
            }
            if (zeile == null) {
                continue;
            }
            Material icon = switch (zeile.typ()) {
                case "BAN", "PERMABANN" -> Material.IRON_SWORD;
                case "MUTE" -> Material.PAPER;
                case "TIMEOUT" -> Material.CLOCK;
                case "FREEZE" -> Material.ICE;
                case "KICK" -> Material.STICK;
                case "VERSTOSS" -> Material.REDSTONE;
                case "WARN" -> Material.BOOK;
                case "REPORT" -> Material.WRITABLE_BOOK;
                case "DOSSIER_STATUS" -> Material.NETHER_STAR;
                case "DOSSIER_AKTION" -> Material.LIME_DYE;
                default -> Material.MAP;
            };
            List<String> lore = new ArrayList<>();
            if (!zeile.details().isBlank()) {
                for (String teil : zeile.details().split("\n")) {
                    lore.add("<gray>" + escape(teil));
                }
            }
            if (!zeile.aktion().isBlank()) {
                if (zeile.aktion().startsWith("gui:")) {
                    lore.add("<yellow>Klick: Auswahl öffnen");
                } else {
                    lore.add("<yellow>Klick: /" + escape(zeile.aktion()));
                }
            }
            inv.setItem(slot++, item(icon, "<white>" + escape(zeile.titel()), lore));
        }
        if (slot == 0) {
            inv.setItem(22, item(Material.GREEN_CONCRETE, "<green>Keine Einträge", List.of()));
        }
        inv.setItem(45, item(Material.ARROW, "<gray>Zurück", List.of()));
        inv.setItem(48, item(Material.ARROW, "<gray>Seite -", List.of()));
        inv.setItem(49, item(Material.SUNFLOWER, "<yellow>Aktualisieren", List.of("<gray>Klick: neu laden")));
        inv.setItem(50, item(Material.ARROW, "<gray>Seite +", List.of()));
        return inv;
    }

    public static Inventory reportDetail(Protokoll.GuiAntwort antwort, long id) {
        Inventory inv = Bukkit.createInventory(new GuiHolder(Menue.REPORT_DETAIL), 27,
                titel("Report #" + id));
        if (antwort == null || !antwort.erfolg()) {
            inv.setItem(13, item(Material.RED_CONCRETE, "<red>Report nicht verfügbar",
                    List.of("<gray>" + escape(antwort == null ? "Timeout (4s)." : antwort.nachricht()))));
            inv.setItem(22, item(Material.ARROW, "<gray>Zurück zur Liste", List.of()));
            return inv;
        }
        Protokoll.GuiZeile kopf = null;
        List<Protokoll.GuiZeile> aktionen = new ArrayList<>();
        if (antwort.eintraege() != null) {
            for (Protokoll.GuiZeile zeile : antwort.eintraege()) {
                if (zeile == null) {
                    continue;
                }
                if ("REPORT_AKTION".equals(zeile.typ())) {
                    aktionen.add(zeile);
                } else if (kopf == null) {
                    kopf = zeile;
                }
            }
        }
        if (kopf != null) {
            List<String> lore = new ArrayList<>();
            if (!kopf.details().isBlank()) {
                for (String teil : kopf.details().split("\n")) {
                    lore.add("<gray>" + escape(teil));
                }
            }
            inv.setItem(4, item(Material.WRITABLE_BOOK, "<white>" + escape(kopf.titel()), lore));
        }
        Material[] icons = {Material.LIME_CONCRETE, Material.RED_CONCRETE, Material.GRAY_CONCRETE};
        int[] slots = {11, 13, 15};
        for (int i = 0; i < aktionen.size() && i < slots.length; i++) {
            Protokoll.GuiZeile zeile = aktionen.get(i);
            List<String> lore = new ArrayList<>();
            if (!zeile.details().isBlank()) {
                lore.add("<gray>" + escape(zeile.details()));
            }
            if (!zeile.aktion().isBlank()) {
                lore.add("<yellow>Klick: /" + escape(zeile.aktion()));
            }
            inv.setItem(slots[i], item(icons[i], "<white>" + escape(zeile.titel()), lore));
        }
        if (aktionen.isEmpty()) {
            inv.setItem(13, item(Material.GRAY_CONCRETE, "<gray>Keine Aktionen", List.of()));
        }
        inv.setItem(22, item(Material.ARROW, "<gray>Zurück zur Liste", List.of()));
        inv.setItem(26, item(Material.SUNFLOWER, "<yellow>Aktualisieren",
                List.of("<gray>Klick: neu laden")));
        return inv;
    }

    static String escape(String text) {
        return escapeTags(text);
    }

    static String escapeTags(String text) {
        if (text == null) {
            return "";
        }
        String entwaffnet;
        try {
            entwaffnet = MM.escapeTags(text);
        } catch (Exception e) {
            entwaffnet = text.replace("<", "").replace(">", "");
        }
        return entwaffnet.replace("\n", " ").replace("\r", " ");
    }
}
