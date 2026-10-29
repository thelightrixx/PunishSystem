package de.lightrixx.punish.bridge.gui;

import de.lightrixx.punish.bridge.BridgeKonfiguration;
import de.lightrixx.punish.bridge.KanalClient;
import de.lightrixx.punish.core.RangBuendel;
import de.lightrixx.punish.net.Protokoll;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class GuiManager {

    public enum Eingabe {
        SPIELER, GRUND, DAUER
    }

    private final Plugin plugin;
    private final KanalClient kanal;
    private final GuiDatenDienst daten;

    private final Map<UUID, GuiSitzung> sitzungen = new ConcurrentHashMap<>();
    private final Map<UUID, Eingabe> wartendeEingabe = new ConcurrentHashMap<>();
    private final Map<UUID, Long> letzteAnfrage = new ConcurrentHashMap<>();
    private final Map<UUID, Long> sitzungZeit = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> listenSeiten = new ConcurrentHashMap<>();
    private final Map<UUID, Long> reportIds = new ConcurrentHashMap<>();
    private static final long SITZUNG_TTL_MILLIS = 60L * 60L * 1000L;
    private static final long EINGABE_TTL_MILLIS = 60L * 1000L;

    public GuiManager(Plugin plugin, KanalClient kanal, BridgeKonfiguration config, GuiDatenDienst daten) {
        this.plugin = Objects.requireNonNull(plugin, "Das Plugin darf nicht null sein.");
        this.kanal = Objects.requireNonNull(kanal, "Der Kanal darf nicht null sein.");
        BridgeKonfiguration konfiguration =
                Objects.requireNonNull(config, "Die Konfiguration darf nicht null sein.");
        this.daten = Objects.requireNonNull(daten, "Der Datendienst darf nicht null sein.");
        try {
            GuiMenues.setTitelPrefix(konfiguration.guiTitel());
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(GuiManager.class).trace("GUI-Titel konnte nicht gesetzt werden.", e);
        }
    }

    public GuiSitzung sitzung(Player spieler) {
        UUID uuid = spieler.getUniqueId();
        Long angelegt = sitzungZeit.get(uuid);
        long jetzt = System.currentTimeMillis();
        if (angelegt != null && jetzt - angelegt > SITZUNG_TTL_MILLIS) {
            sitzungen.remove(uuid);
            wartendeEingabe.remove(uuid);
            sitzungZeit.remove(uuid);
        }
        sitzungZeit.put(uuid, jetzt);
        return sitzungen.computeIfAbsent(uuid, k -> {
            sitzungZeit.put(k, System.currentTimeMillis());
            return new GuiSitzung();
        });
    }

    public boolean wartetAufEingabe(Player spieler) {
        return spieler != null && wartendeEingabe.containsKey(spieler.getUniqueId());
    }

    public void sitzungSchliessen(UUID uuid) {
        if (uuid == null) {
            return;
        }
        sitzungen.remove(uuid);
        wartendeEingabe.remove(uuid);
        letzteAnfrage.remove(uuid);
        sitzungZeit.remove(uuid);
        wartendeEingabeZeit.remove(uuid);
        listenSeiten.remove(uuid);
        reportIds.remove(uuid);
    }

    public boolean darfOeffnen(Player spieler) {
        return spieler != null
                && RangBuendel.gewaehrt(spieler::hasPermission, "punish.gui");
    }

    public void oeffneHaupt(Player spieler) {
        oeffneHaupt(spieler, null);
    }

    @SuppressWarnings("null")
    public void oeffneHaupt(Player spieler, String startZiel) {
        GuiSitzung sitzung = sitzung(spieler);
        if (startZiel != null) {
            if (!startZiel.isBlank()) {
                sitzung.ziel(startZiel);
                if (sitzung.aktion() == null) {
                    sitzung.schritt(GuiSitzung.Schritt.AKTION);
                }
            } else {
                sitzung.ziel("");
            }
        }
        spieler.openInventory(Objects.requireNonNull(GuiMenues.haupt(sitzung, darfAlts(spieler)), "Inventar"));
    }

    @SuppressWarnings("null")
    public void starteNeu(Player spieler, String startZiel) {
        GuiSitzung sitzung = sitzung(spieler);
        sitzung.zuruecksetzen();
        if (startZiel != null && !startZiel.isBlank()) {
            sitzung.ziel(startZiel);
            sitzung.schritt(GuiSitzung.Schritt.AKTION);
        }
        spieler.openInventory(Objects.requireNonNull(GuiMenues.haupt(sitzung, darfAlts(spieler)), "Inventar"));
    }

    @SuppressWarnings("null")
    public void oeffneSpieler(Player spieler, int seite) {
        List<String> alle = onlineNamen();
        int proSeite = 45;
        int seiten = Math.max(1, (alle.size() + proSeite - 1) / proSeite);
        int sicher = Math.min(Math.max(1, seite), seiten);
        sitzung(spieler).seite(sicher);
        int von = (sicher - 1) * proSeite;
        int bis = Math.min(von + proSeite, alle.size());
        List<String> teil = von < bis ? alle.subList(von, bis) : List.of();
        boolean mehr = sicher < seiten;
        spieler.openInventory(Objects.requireNonNull(GuiMenues.spieler(teil, sicher, mehr), "Inventar"));
    }

    @SuppressWarnings("null")
    public void oeffneAktion(Player spieler) {
        spieler.openInventory(Objects.requireNonNull(GuiMenues.aktion(spieler), "Inventar"));
    }

    @SuppressWarnings("null")
    public void oeffneGrund(Player spieler) {
        var sitzung = sitzung(spieler);
        String befehl = sitzung.aktion() == null ? "" : sitzung.aktion().befehl();
        var katalog = daten.katalogWennFrisch(befehl);
        boolean frisch = katalog != null;
        if (!frisch) {
            katalog = daten.katalogLokal(befehl);
        }
        var anzeige = katalog;
        spieler.openInventory(Objects.requireNonNull(GuiMenues.grund(anzeige.gruende(), anzeige.vorlagen()), "Inventar"));
        if (!frisch) {
            frischeKatalogNach(spieler, befehl, GuiMenues.Menue.GRUND);
        }
    }

    @SuppressWarnings("null")
    public void oeffneDauer(Player spieler) {
        var sitzung = sitzung(spieler);
        String befehl = sitzung.aktion() == null ? "" : sitzung.aktion().befehl();
        var katalog = daten.katalogWennFrisch(befehl);
        boolean frisch = katalog != null;
        if (!frisch) {
            katalog = daten.katalogLokal(befehl);
        }
        List<String> dauern = new java.util.ArrayList<>(katalog.dauern());
        String grund = sitzung.grund();
        if (grund != null && !grund.isBlank()) {
            String empfohlen = katalog.vorlagen()
                    .get(grund.trim().toLowerCase(java.util.Locale.ROOT));
            if (empfohlen != null && !empfohlen.isBlank() && dauern.remove(empfohlen.trim())) {
                dauern.add(0, empfohlen.trim());
            }
        }
        spieler.openInventory(Objects.requireNonNull(GuiMenues.dauer(sitzung, List.copyOf(dauern)), "Inventar"));
        if (!frisch) {
            frischeKatalogNach(spieler, befehl, GuiMenues.Menue.DAUER);
        }
    }

    @SuppressWarnings("null")
    public void oeffneScope(Player spieler) {
        var katalog = daten.katalogWennFrisch("");
        if (katalog == null) {
            katalog = daten.katalogLokal();
        }
        var anzeige = katalog;
        spieler.openInventory(Objects.requireNonNull(GuiMenues.scope(anzeige.server()), "Inventar"));
    }

    private void frischeKatalogNach(Player spieler, String befehl, GuiMenues.Menue menue) {
        final String befehlKopie = befehl;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                daten.katalog(spieler, befehlKopie);
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(GuiManager.class).debug("GUI-Katalog-Refresh fehlgeschlagen.", e);
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    if (Bukkit.getPlayer(spieler.getUniqueId()) == null) {
                        return;
                    }
                    var offenes = spieler.getOpenInventory().getTopInventory().getHolder();
                    if (!(offenes instanceof GuiMenues.GuiHolder holder) || holder.menue() != menue) {
                        return;
                    }
                    var katalog = daten.katalogWennFrisch(befehlKopie);
                    if (katalog == null) {
                        return;
                    }
                    if (menue == GuiMenues.Menue.GRUND) {
                        spieler.openInventory(Objects.requireNonNull(
                                GuiMenues.grund(katalog.gruende(), katalog.vorlagen()), "Inventar"));
                    } else if (menue == GuiMenues.Menue.DAUER) {
                        oeffneDauer(spieler);
                    }
                } catch (Exception e) {
                    org.slf4j.LoggerFactory.getLogger(GuiManager.class)
                            .trace("Katalog-Auffrischung konnte nicht angezeigt werden.", e);
                }
            });
        });
    }

    @SuppressWarnings("null")
    public void oeffneBestaetigen(Player spieler) {
        spieler.openInventory(Objects.requireNonNull(GuiMenues.bestaetigen(sitzung(spieler), darfSilent(spieler)), "Inventar"));
    }

    public static boolean darfSilent(Player spieler) {
        if (spieler == null) {
            return false;
        }
        return RangBuendel.gewaehrt(spieler::hasPermission, "punish.allow-silent-syntax");
    }

    public static boolean darfAlts(Player spieler) {
        if (spieler == null) {
            return false;
        }
        return RangBuendel.gewaehrt(spieler::hasPermission, "punish.alts");
    }

    public void oeffneStatus(Player spieler, int seite) {
        ladeUndOeffne(spieler, GuiMenues.Menue.STATUS, "Status", Protokoll.GUI_STATUS, seite);
    }

    public void oeffneHistory(Player spieler, int seite) {
        ladeUndOeffne(spieler, GuiMenues.Menue.HISTORY, "History", Protokoll.GUI_HISTORY, seite);
    }

    public void oeffneReports(Player spieler, int seite) {
        reportIds.remove(spieler.getUniqueId());
        ladeUndOeffne(spieler, GuiMenues.Menue.REPORTS, "Reports", Protokoll.GUI_REPORTS, seite);
    }

    public void oeffneReportInfo(Player spieler, long id) {
        if (spieler == null || id <= 0L) {
            return;
        }
        reportIds.put(spieler.getUniqueId(), id);
        ladeUndOeffne(spieler, GuiMenues.Menue.REPORT_DETAIL, "Report #" + id,
                Protokoll.GUI_REPORT_INFO, 1, String.valueOf(id));
    }

    @SuppressWarnings("null")
    private void ladeUndOeffne(Player spieler, GuiMenues.Menue menue, String kopf,
                               String typ, int seite) {
        String extra = typ.equals(Protokoll.GUI_REPORTS) ? "offen" : "";
        ladeUndOeffne(spieler, menue, kopf, typ, seite, extra);
    }

    @SuppressWarnings("null")
    private void ladeUndOeffne(Player spieler, GuiMenues.Menue menue, String kopf,
                               String typ, int seite, String extra) {
        GuiSitzung sitzung = sitzung(spieler);
        String ziel = sitzung.ziel();
        UUID uuid = spieler.getUniqueId();
        String zielKopie = ziel;
        int seiteKopie = Math.max(1, seite);
        String extraKopie = extra == null ? "" : extra;
        long detailId = 0L;
        if (menue == GuiMenues.Menue.REPORT_DETAIL) {
            try {
                detailId = Long.parseLong(extraKopie.trim());
            } catch (Exception e) {
                detailId = 0L;
            }
        }
        long detailIdKopie = detailId;
        sitzung.seite(seiteKopie);
        long anfrageId;
        try {
            anfrageId = kanal.naechsteGuiId();
        } catch (Exception e) {
            return;
        }
        letzteAnfrage.put(uuid, anfrageId);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Protokoll.GuiAntwort antwort;
            try {
                var anfrage = new Protokoll.GuiAnfrage(anfrageId, uuid, typ, zielKopie, "", seiteKopie, extraKopie);
                antwort = kanal.frageGuiAn(anfrage, 4000L);
            } catch (Exception e) {
                antwort = null;
            }
            Protokoll.GuiAntwort versand = antwort;
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    Long aktuell = letzteAnfrage.get(uuid);
                    if (aktuell == null || aktuell != anfrageId) {
                        return;
                    }
                    Player online = Bukkit.getPlayer(uuid);
                    if (online == null) {
                        return;
                    }
                    try {
                        if (versand != null) {
                            listenSeiten.put(uuid, Math.max(1, versand.seiten()));
                        }
                    } catch (Exception e) {
                        org.slf4j.LoggerFactory.getLogger(GuiManager.class).trace("Listen-Seiten konnten nicht gespeichert werden.", e);
                    }
                    var offenes = online.getOpenInventory().getTopInventory().getHolder();
                    if (!(offenes instanceof GuiMenues.GuiHolder holder) || holder.menue() != menue) {
                        return;
                    }
                    Inventory inv = menue == GuiMenues.Menue.REPORT_DETAIL
                            ? GuiMenues.reportDetail(versand, detailIdKopie)
                            : GuiMenues.liste(menue, kopf + " " + (zielKopie.isBlank() ? "" : zielKopie), versand);
                    online.openInventory(Objects.requireNonNull(inv, "Inventar"));
                } catch (Exception e) {
                    org.slf4j.LoggerFactory.getLogger(GuiManager.class).trace("GUI-Liste konnte nicht geöffnet werden.", e);
                }
            });
        });
        try {
            Inventory ladeInv = menue == GuiMenues.Menue.REPORT_DETAIL
                    ? GuiMenues.reportDetail(new Protokoll.GuiAntwort(1L, true, "", 1, 1, 0, List.of(
                            new Protokoll.GuiZeile("INFO", "Lade vom Proxy …", "Bitte kurz warten.", "", ""))), detailIdKopie)
                    : GuiMenues.liste(menue, kopf + " lädt …",
                            new Protokoll.GuiAntwort(1L, true, "", 1, 1, 0, List.of(
                                    new Protokoll.GuiZeile("INFO", "Lade vom Proxy …", "Bitte kurz warten.", "", ""))));
            spieler.openInventory(Objects.requireNonNull(ladeInv, "Inventar"));
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(GuiManager.class).trace("Lade-Menü konnte nicht geöffnet werden.", e);
        }
    }

    private boolean verweigertOhneGuiRecht(Player spieler) {
        boolean darf;
        try {
            darf = spieler != null && RangBuendel.gewaehrt(spieler::hasPermission, "punish.gui");
        } catch (Exception e) {
            darf = false;
        }
        if (!darf) {
            try {
                spieler.closeInventory();
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(GuiManager.class).trace("GUI konnte nicht geschlossen werden.", e);
            }
            try {
                spieler.sendMessage(prefixHinweis("Dazu hast du keine Berechtigung (punish.gui).", NamedTextColor.RED));
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(GuiManager.class).trace("GUI-Hinweis konnte nicht gesendet werden.", e);
            }
            return true;
        }
        return false;
    }

    public boolean klick(Player spieler, GuiMenues.GuiHolder holder, int slot) {
        if (spieler == null || holder == null) {
            return false;
        }
        GuiSitzung sitzung = sitzung(spieler);
        return switch (holder.menue()) {
            case HAUPT -> klickHaupt(spieler, sitzung, slot);
            case SPIELER -> klickSpieler(spieler, sitzung, slot);
            case AKTION -> klickAktion(spieler, sitzung, slot);
            case GRUND -> klickGrund(spieler, sitzung, slot);
            case DAUER -> klickDauer(spieler, sitzung, slot);
            case SCOPE -> klickScope(spieler, sitzung, slot);
            case BESTAETIGEN -> klickBestaetigen(spieler, sitzung, slot);
            case STATUS -> klickListe(spieler, sitzung, slot, GuiMenues.Menue.STATUS);
            case HISTORY -> klickListe(spieler, sitzung, slot, GuiMenues.Menue.HISTORY);
            case REPORTS -> klickListe(spieler, sitzung, slot, GuiMenues.Menue.REPORTS);
            case REPORT_DETAIL -> klickReportDetail(spieler, sitzung, slot);
        };
    }

    private boolean klickHaupt(Player spieler, GuiSitzung sitzung, int slot) {
        if (verweigertOhneGuiRecht(spieler)) {
            return true;
        }
        switch (slot) {
            case 10 -> oeffneSpieler(spieler, 1);
            case 11 -> oeffneAktion(spieler);
            case 12 -> oeffneGrund(spieler);
            case 13 -> oeffneDauer(spieler);
            case 14 -> oeffneScope(spieler);
            case 15 -> {
                if (sitzung.bereitZumSenden()) {
                    oeffneBestaetigen(spieler);
                } else {
                    spieler.sendMessage(prefixHinweis("Noch nicht komplett – wähle Spieler, Aktion, Grund und Dauer.", NamedTextColor.YELLOW));
                }
            }
            case 20 -> oeffneStatus(spieler, 1);
            case 21 -> {
                if (!darfAlts(spieler)) {
                    spieler.sendMessage(prefixHinweis("Dazu hast du keine Berechtigung (punish.alts).", NamedTextColor.RED));
                } else if (sitzung.ziel().isBlank()) {
                    spieler.sendMessage(prefixHinweis("Erst einen Spieler wählen.", NamedTextColor.YELLOW));
                } else {
                    try {
                        kanal.sendeBefehl(spieler.getUniqueId(), "alts " + sitzung.ziel().trim());
                        spieler.closeInventory();
                        spieler.sendMessage(prefixHinweis(Component.text().append(Component.text("Alts für ", NamedTextColor.GREEN))
                                .append(Component.text(sitzung.ziel().trim(), NamedTextColor.WHITE))
                                .append(Component.text(" (Antwort kommt über den Proxy-Chat)", NamedTextColor.GRAY)).build()));
                    } catch (Exception e) {
                        spieler.sendMessage(prefixHinweis("Proxy nicht erreichbar (mind. 1 Spieler online nötig).", NamedTextColor.RED));
                    }
                }
            }
            case 22 -> oeffneHistory(spieler, 1);
            case 24 -> oeffneReports(spieler, 1);
            case 26 -> {
                sitzungSchliessen(spieler.getUniqueId());
                spieler.closeInventory();
            }
            default -> {
            }
        }
        return true;
    }

    private boolean klickSpieler(Player spieler, GuiSitzung sitzung, int slot) {
        var inv = spieler.getOpenInventory().getTopInventory();
        if (slot >= 0 && slot < 45) {
            try {
                var stack = inv.getItem(slot);
                if (stack != null && stack.getItemMeta() != null) {
                    String name = klartext(stack.getItemMeta().displayName());
                    if (name != null && !name.isBlank()) {
                        sitzung.ziel(name.trim());
                        sitzung.schritt(GuiSitzung.Schritt.AKTION);
                        oeffneHaupt(spieler);
                        return true;
                    }
                }
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(GuiManager.class).trace("Spieler-Klick übersprungen.", e);
            }
            return true;
        }
        if (slot == 45) {
            int seite = sitzung.seite();
            if (seite > 1) {
                oeffneSpieler(spieler, seite - 1);
            } else {
                oeffneHaupt(spieler);
            }
        } else if (slot == 48) {
            warteAufChat(spieler, Eingabe.SPIELER);
        } else if (slot == 49) {
            oeffneHaupt(spieler);
        } else if (slot == 53) {
            try {
                if (inv.getItem(53) == null) {
                    return true;
                }
            } catch (Exception e) {
                return true;
            }
            oeffneSpieler(spieler, sitzung.seite() + 1);
        }
        return true;
    }

    private boolean klickAktion(Player spieler, GuiSitzung sitzung, int slot) {
        if (verweigertOhneGuiRecht(spieler)) {
            return true;
        }
        if (slot == 22) {
            oeffneHaupt(spieler);
            return true;
        }
        GuiSitzung.Aktion[] alle = GuiSitzung.Aktion.values();
        int index = slot - 7;
        if (index < 0 || index >= alle.length) {
            return true;
        }
        var aktion = alle[index];
        if (!RangBuendel.gewaehrt(spieler::hasPermission, aktion.permission())) {
            spieler.sendMessage(prefixHinweis(
                    "Dazu fehlt dir das Recht " + aktion.permission() + ".", NamedTextColor.RED));
            return true;
        }
        sitzung.aktion(aktion);
        sitzung.schritt(aktion.brauchtGrund() ? GuiSitzung.Schritt.GRUND
                : aktion.brauchtDauer() ? GuiSitzung.Schritt.DAUER : GuiSitzung.Schritt.SCOPE);
        if (aktion.brauchtGrund()) {
            oeffneGrund(spieler);
        } else if (aktion.brauchtDauer()) {
            oeffneDauer(spieler);
        } else {
            oeffneScope(spieler);
        }
        return true;
    }

    private boolean klickGrund(Player spieler, GuiSitzung sitzung, int slot) {
        if (slot == 45) {
            oeffneHaupt(spieler);
            return true;
        }
        if (slot == 49) {
            warteAufChat(spieler, Eingabe.GRUND);
            return true;
        }
        var inv = spieler.getOpenInventory().getTopInventory();
        try {
            var stack = inv.getItem(slot);
            if (stack != null && stack.getItemMeta() != null) {
                String text = klartext(stack.getItemMeta().displayName());
                if (text != null && !text.isBlank()) {
                    sitzung.grund(text.trim());
                    var aktion = sitzung.aktion();
                    if (aktion != null && aktion.brauchtDauer()) {
                        oeffneDauer(spieler);
                    } else if (aktion == GuiSitzung.Aktion.VERSTOSS) {
                        oeffneDauer(spieler);
                    } else {
                        oeffneScope(spieler);
                    }
                    return true;
                }
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(GuiManager.class).trace("Grund-Klick übersprungen.", e);
        }
        return true;
    }

    private boolean klickDauer(Player spieler, GuiSitzung sitzung, int slot) {
        if (slot == 22) {
            oeffneHaupt(spieler);
            return true;
        }
        if (sitzung.aktion() == GuiSitzung.Aktion.VERSTOSS) {
            String stufe = switch (slot) {
                case 11 -> "low";
                case 13 -> "medium";
                case 15 -> "high";
                default -> null;
            };
            if (stufe != null) {
                sitzung.stufe(stufe);
                oeffneBestaetigen(spieler);
            }
            return true;
        }
        if (slot == 13) {
            warteAufChat(spieler, Eingabe.DAUER);
            return true;
        }
        var inv = spieler.getOpenInventory().getTopInventory();
        try {
            var stack = inv.getItem(slot);
            if (stack != null && stack.getItemMeta() != null) {
                String text = klartext(stack.getItemMeta().displayName());
                if (text != null && !text.isBlank()) {
                    sitzung.dauer(text.trim());
                    oeffneScope(spieler);
                    return true;
                }
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(GuiManager.class).trace("Dauer-Klick übersprungen.", e);
        }
        return true;
    }

    private boolean klickScope(Player spieler, GuiSitzung sitzung, int slot) {
        if (slot == 22) {
            oeffneHaupt(spieler);
            return true;
        }
        var inv = spieler.getOpenInventory().getTopInventory();
        try {
            var stack = inv.getItem(slot);
            if (stack != null && stack.getItemMeta() != null) {
                String text = klartext(stack.getItemMeta().displayName());
                if (text != null && !text.isBlank()) {
                    String kandidat = text.trim();
                    if (istGueltigerScope(kandidat, spieler)) {
                        sitzung.scope(kandidat);
                    } else {
                        sitzung.scope("--global");
                    }
                    oeffneBestaetigen(spieler);
                    return true;
                }
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(GuiManager.class).trace("Scope-Klick übersprungen.", e);
        }
        return true;
    }

    private boolean istGueltigerScope(String kandidat, Player spieler) {
        if (kandidat == null || kandidat.isBlank()) {
            return false;
        }
        String norm = kandidat.trim();
        if (norm.equalsIgnoreCase("--global") || norm.equalsIgnoreCase("--unbekannt")) {
            return true;
        }
        if (norm.toLowerCase(java.util.Locale.ROOT).startsWith("--server=")) {
            String name = norm.substring("--server=".length()).trim();
            return de.lightrixx.punish.model.ServerScope.normalisiereServerName(name) != null;
        }
        if (norm.startsWith("--")) {
            try {
                var katalog = daten.katalogWennFrisch("");
                if (katalog == null) {
                    katalog = daten.katalogLokal();
                }
                if (katalog != null && katalog.server() != null) {
                    for (String server : katalog.server()) {
                        if (server != null && norm.equalsIgnoreCase("--" + server.trim())) {
                            return true;
                        }
                    }
                }
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(GuiManager.class).trace("Scope-Validierung übersprungen.", e);
            }
            return false;
        }
        return false;
    }

    private boolean klickBestaetigen(Player spieler, GuiSitzung sitzung, int slot) {
        if (verweigertOhneGuiRecht(spieler)) {
            return true;
        }
        if (slot == 22) {
            oeffneHaupt(spieler);
            return true;
        }
        if (slot == 13) {
            if (!darfSilent(spieler)) {
                spieler.sendMessage(prefixHinweis("Silent erfordert punish.allow-silent-syntax.", NamedTextColor.RED));
                return true;
            }
            sitzung.wechselSilent();
            oeffneBestaetigen(spieler);
            return true;
        }
        if (slot == 15) {
            if (!sitzung.bereitZumSenden()) {
                spieler.sendMessage(prefixHinweis("Noch nicht komplett.", NamedTextColor.RED));
                return true;
            }
            var pruefAktion = sitzung.aktion();
            if (pruefAktion != null
                    && !RangBuendel.gewaehrt(spieler::hasPermission, pruefAktion.permission())) {
                spieler.sendMessage(prefixHinweis(
                        "Dazu fehlt dir das Recht " + pruefAktion.permission() + ".", NamedTextColor.RED));
                return true;
            }
            if (!darfSilent(spieler)) {
                sitzung.silent(false);
            }
            String zeile = sitzung.baueZeile();
            try {
                kanal.sendeBefehl(spieler.getUniqueId(), zeile);
                sitzungSchliessen(spieler.getUniqueId());
                spieler.closeInventory();
                spieler.sendMessage(prefixHinweis(Component.text().append(Component.text("Gesendet: ", NamedTextColor.GREEN))
                        .append(Component.text("/" + zeile, NamedTextColor.WHITE))
                        .append(Component.text(" (Antwort kommt über den Proxy-Chat)", NamedTextColor.GRAY)).build()));
            } catch (Exception e) {
                spieler.sendMessage(prefixHinweis("Proxy nicht erreichbar (mind. 1 Spieler online nötig).", NamedTextColor.RED));
            }
            return true;
        }
        return true;
    }

    private boolean klickListe(Player spieler, GuiSitzung sitzung, int slot, GuiMenues.Menue menue) {
        if (verweigertOhneGuiRecht(spieler)) {
            return true;
        }
        if (slot == 45) {
            oeffneHaupt(spieler);
            return true;
        }
        if (slot == 49) {
            int seite = sitzung.seite();
            if (menue == GuiMenues.Menue.STATUS) {
                oeffneStatus(spieler, seite);
            } else if (menue == GuiMenues.Menue.HISTORY) {
                oeffneHistory(spieler, seite);
            } else {
                oeffneReports(spieler, seite);
            }
            return true;
        }
        if (slot == 48 || slot == 50) {
            int delta = slot == 50 ? 1 : -1;
            int aktuell = sitzung.seite();
            if (delta < 0 && aktuell <= 1) {
                return true;
            }
            if (delta > 0) {
                Integer bekannt = listenSeiten.get(spieler.getUniqueId());
                if (bekannt != null && aktuell >= Math.max(1, bekannt)) {
                    return true;
                }
            }
            int neu = Math.max(1, aktuell + delta);
            sitzung.seite(neu);
            if (menue == GuiMenues.Menue.STATUS) {
                oeffneStatus(spieler, neu);
            } else if (menue == GuiMenues.Menue.HISTORY) {
                oeffneHistory(spieler, neu);
            } else {
                oeffneReports(spieler, neu);
            }
            return true;
        }
        if (slot >= 0 && slot < 45) {
            String aktion = aktionAusLore(spieler, slot);
            if (aktion != null && !aktion.isBlank()) {
                if (menue == GuiMenues.Menue.REPORTS && aktion.startsWith("reports info ")) {
                    String idRoh = aktion.substring("reports info ".length()).trim();
                    try {
                        oeffneReportInfo(spieler, Long.parseLong(idRoh));
                    } catch (Exception e) {
                        spieler.sendMessage(prefixHinweis("Ungültige Report-ID.", NamedTextColor.RED));
                    }
                    return true;
                }
                try {
                    kanal.sendeBefehl(spieler.getUniqueId(), aktion);
                    spieler.sendMessage(prefixHinweis(Component.text().append(Component.text("Gesendet: ", NamedTextColor.GREEN))
                            .append(Component.text("/" + aktion, NamedTextColor.WHITE)).build()));
                    if (menue == GuiMenues.Menue.STATUS) {
                        oeffneStatus(spieler, sitzung.seite());
                    } else if (menue == GuiMenues.Menue.HISTORY) {
                        oeffneHistory(spieler, sitzung.seite());
                    } else if (menue == GuiMenues.Menue.REPORTS) {
                        oeffneReports(spieler, sitzung.seite());
                    }
                } catch (Exception e) {
                    spieler.sendMessage(prefixHinweis("Proxy nicht erreichbar.", NamedTextColor.RED));
                }
            }
            return true;
        }
        return true;
    }

    private boolean klickReportDetail(Player spieler, GuiSitzung sitzung, int slot) {
        if (verweigertOhneGuiRecht(spieler)) {
            return true;
        }
        Long id = reportIds.get(spieler.getUniqueId());
        if (slot == 22) {
            reportIds.remove(spieler.getUniqueId());
            oeffneReports(spieler, 1);
            return true;
        }
        if (slot == 26) {
            if (id != null) {
                oeffneReportInfo(spieler, id);
            }
            return true;
        }
        String aktion = aktionAusLore(spieler, slot);
        if (aktion == null || aktion.isBlank()) {
            return true;
        }
        try {
            kanal.sendeBefehl(spieler.getUniqueId(), aktion);
            spieler.sendMessage(prefixHinweis(Component.text().append(Component.text("Gesendet: ", NamedTextColor.GREEN))
                    .append(Component.text("/" + aktion, NamedTextColor.WHITE)).build()));
            if (id != null) {
                oeffneReportInfo(spieler, id);
            }
        } catch (Exception e) {
            spieler.sendMessage(prefixHinweis("Proxy nicht erreichbar.", NamedTextColor.RED));
        }
        return true;
    }

    private String aktionAusLore(Player spieler, int slot) {
        var inv = spieler.getOpenInventory().getTopInventory();
        try {
            var stack = inv.getItem(slot);
            if (stack == null || stack.getItemMeta() == null || stack.getItemMeta().lore() == null) {
                return null;
            }
            for (var zeile : stack.getItemMeta().lore()) {
                String klar = klartext(zeile);
                if (klar != null && klar.startsWith("Klick: /")) {
                    return klar.substring("Klick: /".length()).trim();
                }
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(GuiManager.class).trace("Klick-Aktion konnte nicht gelesen werden.", e);
        }
        return null;
    }

    private final Map<UUID, Long> wartendeEingabeZeit = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> closeUeberspringen = ConcurrentHashMap.newKeySet();

    private void warteAufChat(Player spieler, Eingabe eingabe) {
        wartendeEingabe.put(spieler.getUniqueId(), eingabe);
        wartendeEingabeZeit.put(spieler.getUniqueId(), System.currentTimeMillis());
        closeUeberspringen.add(spieler.getUniqueId());
        spieler.closeInventory();
        Component hinweis = switch (eingabe) {
            case SPIELER -> Component.text("Schreibe den Spielernamen in den Chat ( Abbrechen mit 'abbrechen' ).", NamedTextColor.YELLOW);
            case GRUND -> Component.text("Schreibe den Grund in den Chat ( Abbrechen mit 'abbrechen' ).", NamedTextColor.YELLOW);
            case DAUER -> Component.text("Schreibe die Dauer in den Chat, z. B. 10m, 1h, 7d, perm ( Abbrechen mit 'abbrechen' ).", NamedTextColor.YELLOW);
        };
        spieler.sendMessage(prefixHinweis(hinweis));
    }

    // Räumt eine wartende Chat-Eingabe auf, wenn das Inventar ohne den internen
    // warteAufChat-Close geschlossen wird (sonst fängt sie 60 s lang Chat ab).
    public void inventarGeschlossen(Player spieler) {
        if (spieler == null) {
            return;
        }
        UUID uuid = spieler.getUniqueId();
        if (closeUeberspringen.remove(uuid)) {
            return;
        }
        wartendeEingabe.remove(uuid);
        wartendeEingabeZeit.remove(uuid);
    }

    public boolean chatEingabe(Player spieler, String nachricht) {
        UUID uuid = spieler.getUniqueId();
        Long seit = wartendeEingabeZeit.get(uuid);
        Eingabe eingabe = wartendeEingabe.remove(uuid);
        wartendeEingabeZeit.remove(uuid);
        if (eingabe == null) {
            return false;
        }
        if (seit != null && System.currentTimeMillis() - seit > EINGABE_TTL_MILLIS) {
            spieler.sendMessage(prefixHinweis("Eingabe abgelaufen (60s).", NamedTextColor.RED));
            return true;
        }
        String text = nachricht == null ? "" : nachricht.trim();
        if (text.equalsIgnoreCase("abbrechen") || text.equalsIgnoreCase("cancel")) {
            Bukkit.getScheduler().runTask(plugin, () -> oeffneHaupt(spieler));
            return true;
        }
        GuiSitzung sitzung = sitzung(spieler);
        switch (eingabe) {
            case SPIELER -> {
                if (text.isBlank() || text.length() > 16
                        || !text.matches("[A-Za-z0-9_.]{1,16}")) {
                    spieler.sendMessage(prefixHinweis("Ungültiger Name.", NamedTextColor.RED));
                    Bukkit.getScheduler().runTask(plugin, () -> oeffneSpieler(spieler, 1));
                    return true;
                }
                sitzung.ziel(text);
            }
            case GRUND -> {
                if (text.isBlank()) {
                    spieler.sendMessage(prefixHinweis("Leerer Grund.", NamedTextColor.RED));
                    Bukkit.getScheduler().runTask(plugin, () -> oeffneGrund(spieler));
                    return true;
                }
                sitzung.grund(text.length() > 120 ? text.substring(0, 120) : text);
            }
            case DAUER -> {
                if (text.isBlank()) {
                    spieler.sendMessage(prefixHinweis("Leere Dauer.", NamedTextColor.RED));
                    Bukkit.getScheduler().runTask(plugin, () -> oeffneDauer(spieler));
                    return true;
                }
                sitzung.dauer(text.length() > 32 ? text.substring(0, 32) : text);
            }
        }
        Bukkit.getScheduler().runTask(plugin, () -> oeffneHaupt(spieler));
        return true;
    }

    public void schliessen(Player spieler) {
        if (spieler == null) {
            return;
        }
        sitzungSchliessen(spieler.getUniqueId());
        wartendeEingabeZeit.remove(spieler.getUniqueId());
    }

    private List<String> onlineNamen() {
        List<String> namen = new ArrayList<>();
        try {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online != null && online.getName() != null) {
                    namen.add(online.getName());
                }
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(GuiManager.class).trace("Online-Namen konnten nicht gelesen werden.", e);
        }
        namen.sort(String.CASE_INSENSITIVE_ORDER);
        return namen;
    }

    private static Component prefixHinweis(String text, NamedTextColor farbe) {
        return Component.text().append(Component.text("[PunishSystem] ", NamedTextColor.RED))
                .append(Component.text(text, farbe)).build();
    }

    private static Component prefixHinweis(Component inhalt) {
        return Component.text().append(Component.text("[PunishSystem] ", NamedTextColor.RED))
                .append(inhalt).build();
    }

    static String klartext(net.kyori.adventure.text.Component komponente) {        if (komponente == null) {
            return "";
        }
        try {
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                    .plainText().serialize(komponente);
        } catch (Exception e) {
            try {
                return komponente.toString();
            } catch (Exception ignored) {
                return "";
            }
        }
    }
}
