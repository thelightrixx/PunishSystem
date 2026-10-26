package de.lightrixx.punish.bridge.gui;

import de.lightrixx.punish.bridge.BridgeKonfiguration;
import de.lightrixx.punish.bridge.KanalClient;
import de.lightrixx.punish.net.Protokoll;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holt GUI-Daten live vom Proxy (mit Timeout + lokalem Fallback).
 * Katalog wird 5 Minuten gecacht, damit der Builder flüssig bleibt.
 */
public final class GuiDatenDienst {

    private static final long TIMEOUT_MILLIS = 4000L;
    private static final long KATALOG_TTL_MILLIS = 5L * 60L * 1000L;

    private final KanalClient kanal;
    private final BridgeKonfiguration config;

    private volatile Katalog katalogCache;
    private volatile long katalogZeit;
    private final ConcurrentHashMap<String, KatalogCacheEintrag> befehlKataloge =
            new ConcurrentHashMap<>();

    private record KatalogCacheEintrag(Katalog katalog, long zeit) {
    }

    public record Katalog(List<String> gruende, List<String> dauern,
                          List<String> server, List<String> stufen,
                          java.util.Map<String, String> vorlagen) {
        public Katalog(List<String> gruende, List<String> dauern,
                       List<String> server, List<String> stufen) {
            this(gruende, dauern, server, stufen, java.util.Map.of());
        }
    }

    public GuiDatenDienst(KanalClient kanal, BridgeKonfiguration config) {
        this.kanal = Objects.requireNonNull(kanal, "Der Kanal darf nicht null sein.");
        this.config = Objects.requireNonNull(config, "Die Konfiguration darf nicht null sein.");
    }

    public Protokoll.GuiAntwort frageAn(Player spieler, String typ, String ziel,
                                        String filter, int seite, String extra) {
        UUID alsUuid = spieler == null ? null : spieler.getUniqueId();
        long id = kanal.naechsteGuiId();
        var anfrage = new Protokoll.GuiAnfrage(id, alsUuid, typ, ziel, filter, seite, extra);
        return kanal.frageGuiAn(anfrage, TIMEOUT_MILLIS);
    }

    public Katalog katalog(Player spieler) {
        Katalog gecacht = katalogCache;
        if (gecacht != null && System.currentTimeMillis() - katalogZeit < KATALOG_TTL_MILLIS) {
            return gecacht;
        }
        KatalogErgebnis frisch = ladeKatalog(spieler, "");
        if (frisch.vomProxy()) {
            katalogCache = frisch.katalog();
            katalogZeit = System.currentTimeMillis();
        }
        return frisch.katalog();
    }

    /**
     * Nur-Cache-Zugriff ohne Netzwerk: null, wenn kein frischer Katalog vorliegt.
     * Für Aufrufe auf dem Main-Thread (GUI-Klicks) gedacht.
     */
    public Katalog katalogWennFrisch() {
        Katalog gecacht = katalogCache;
        if (gecacht != null && System.currentTimeMillis() - katalogZeit < KATALOG_TTL_MILLIS) {
            return gecacht;
        }
        return null;
    }

    /**
     * Lokaler Fallback-Katalog ohne Netzwerkzugriff (Config-Listen + Defaults).
     * Sofort verfügbar; kein Warten auf den Proxy.
     */
    public Katalog katalogLokal() {
        return baueLokalenKatalog("");
    }

    public Katalog katalog(Player spieler, String befehl) {
        String normiert = befehl == null ? "" : befehl.trim().toLowerCase(java.util.Locale.ROOT);
        if (normiert.isEmpty()) {
            return katalog(spieler);
        }
        KatalogCacheEintrag gecacht = befehlKataloge.get(normiert);
        if (gecacht != null && System.currentTimeMillis() - gecacht.zeit() < KATALOG_TTL_MILLIS) {
            return gecacht.katalog();
        }
        KatalogErgebnis frisch = ladeKatalog(spieler, normiert);
        if (frisch.vomProxy()) {
            befehlKataloge.put(normiert, new KatalogCacheEintrag(frisch.katalog(), System.currentTimeMillis()));
        }
        return frisch.katalog();
    }

    public Katalog katalogWennFrisch(String befehl) {
        String normiert = befehl == null ? "" : befehl.trim().toLowerCase(java.util.Locale.ROOT);
        if (normiert.isEmpty()) {
            return katalogWennFrisch();
        }
        KatalogCacheEintrag gecacht = befehlKataloge.get(normiert);
        if (gecacht != null && System.currentTimeMillis() - gecacht.zeit() < KATALOG_TTL_MILLIS) {
            return gecacht.katalog();
        }
        return null;
    }

    public Katalog katalogLokal(String befehl) {
        String normiert = befehl == null ? "" : befehl.trim().toLowerCase(java.util.Locale.ROOT);
        return baueLokalenKatalog(normiert);
    }

    private record KatalogErgebnis(Katalog katalog, boolean vomProxy) {
    }

    private KatalogErgebnis ladeKatalog(Player spieler, String befehl) {
        Katalog lokal = baueLokalenKatalog(befehl);
        boolean vomProxy = false;
        List<String> gruende = new ArrayList<>(lokal.gruende());
        List<String> dauern = new ArrayList<>(lokal.dauern());
        List<String> server = new ArrayList<>(lokal.server());
        java.util.Map<String, String> vorlagen = new java.util.LinkedHashMap<>(lokal.vorlagen());
        boolean gefiltert = befehl != null && !befehl.isBlank();
        try {
            List<String> g = new ArrayList<>();
            List<String> d = new ArrayList<>();
            List<String> s = new ArrayList<>();
            java.util.Map<String, String> v = new java.util.LinkedHashMap<>();
            for (int seite = 1; seite <= 20; seite++) {
                Protokoll.GuiAntwort antwort = frageAn(spieler, Protokoll.GUI_KATALOG, "", "", seite,
                        gefiltert ? befehl : "");
                if (antwort == null || !antwort.erfolg()) {
                    break;
                }
                vomProxy = true;
                if (seite == 20 && antwort.seiten() > 20) {
                    org.slf4j.LoggerFactory.getLogger(GuiDatenDienst.class).warn(
                            "GUI-Katalog hat mehr als 20 Seiten ({}), Rest wird abgeschnitten.",
                            antwort.seiten());
                }
                for (Protokoll.GuiZeile zeile : antwort.eintraege()) {
                    if (zeile == null) {
                        continue;
                    }
                    switch (zeile.typ()) {
                        case "GRUND" -> {
                            if (!zeile.titel().isBlank()) {
                                g.add(zeile.titel());
                            }
                        }
                        case "DAUER" -> {
                            if (!zeile.titel().isBlank()) {
                                d.add(zeile.titel());
                            }
                        }
                        case "SERVER" -> {
                            if (!zeile.titel().isBlank()) {
                                s.add(zeile.titel());
                            }
                        }
                        case "VORLAGE" -> {
                            if (!zeile.titel().isBlank() && !zeile.details().isBlank()) {
                                v.putIfAbsent(zeile.titel().trim().toLowerCase(java.util.Locale.ROOT),
                                        zeile.details().trim());
                            }
                        }
                        default -> {
                        }
                    }
                }
                if (seite >= Math.max(1, antwort.seiten())) {
                    break;
                }
            }
            if (!g.isEmpty()) {
                gruende = g;
            }
            if (!d.isEmpty()) {
                dauern = d;
            }
            if (!s.isEmpty()) {
                server = s;
            }
            if (!v.isEmpty()) {
                vorlagen = v;
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(GuiDatenDienst.class).warn("GUI-Katalog konnte nicht vom Proxy geladen werden (Fallback aktiv).", e);
        }
        return new KatalogErgebnis(new Katalog(List.copyOf(gruende), List.copyOf(dauern),
                List.copyOf(server), List.of("low", "medium", "high"), java.util.Map.copyOf(vorlagen)),
                vomProxy);
    }

    private Katalog baueLokalenKatalog(String befehl) {
        boolean gefiltert = befehl != null && !befehl.isBlank();
        List<String> gruende = new ArrayList<>(
                gefiltert ? config.grundListe(befehl) : config.tabGrundListe());
        List<String> dauern = new ArrayList<>(
                gefiltert ? config.dauerListe(befehl) : config.tabDauerListe());
        List<String> server = new ArrayList<>(config.bekannteServer());
        java.util.Map<String, String> vorlagen = new java.util.LinkedHashMap<>(
                gefiltert ? config.vorlagenFuer(befehl) : java.util.Map.of());
        if (server.isEmpty()) {
            server = List.of("lobby", "survival");
        }
        if (gruende.isEmpty()) {
            try {
                gruende = new ArrayList<>(config.guiFallbackGruende());
            } catch (Exception e) {
                gruende = List.of("Beleidigung", "Spamming", "Cheating", "Teaming", "Bugusing", "Hacking");
            }
        }
        if (dauern.isEmpty()) {
            try {
                dauern = new ArrayList<>(config.guiFallbackDauern());
            } catch (Exception e) {
                dauern = List.of("10m", "1h", "6h", "1d", "7d", "30d", "perm");
            }
        }
        return new Katalog(List.copyOf(gruende), List.copyOf(dauern),
                List.copyOf(server), List.of("low", "medium", "high"), java.util.Map.copyOf(vorlagen));
    }

    public void invalideKatalog() {
        katalogZeit = 0L;
        befehlKataloge.clear();
    }
}
