package de.lightrixx.punish.velocity;

import de.lightrixx.punish.chatfilter.FilterKanal;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.model.ParseResult;
import de.lightrixx.punish.net.Protokoll;
import de.lightrixx.punish.service.BroadcastService;
import de.lightrixx.punish.service.MuteService;
import de.lightrixx.punish.service.WarnService;
import de.lightrixx.punish.util.TimeParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ChatfilterStaffelService {

    private static final Logger LOG = LoggerFactory.getLogger(ChatfilterStaffelService.class);

    private static final int MAX_SPIELER = 10000;
    private static final int MAX_TEXTBREITE = 200;
    private static final String AUSFUEHRER = "Chatfilter";

    public enum Aktion {
        WARN,
        MUTE
    }

    public record Stufe(int anzahl, Aktion aktion, long dauerMillis, String grund) {
        public Stufe {
            if (anzahl < 1) {
                throw new IllegalArgumentException("Die Anzahl muss mindestens 1 sein.");
            }
            Objects.requireNonNull(aktion, "Die Aktion darf nicht null sein.");
            if (aktion == Aktion.MUTE && dauerMillis <= 0) {
                throw new IllegalArgumentException("Mute braucht eine positive Dauer.");
            }
            if (grund == null || grund.isBlank()) {
                grund = "Chatfilter: wiederholte Regelverstöße (%anzahl% Treffer)";
            }
        }
    }

    private record ZaehlTreffer(long id, long zeit) {
    }

    private record Fenster(Deque<ZaehlTreffer> zeiten, Set<Integer> angewendet) {
    }

    private final VelocityKonfiguration konfiguration;
    private final WarnService warnService;
    private final MuteService muteService;
    private final BroadcastService broadcastService;
    private final ConcurrentHashMap<UUID, Fenster> fenster = new ConcurrentHashMap<>();
    private volatile ModerationsDienst moderation;
    private final java.util.concurrent.atomic.AtomicLong lokalerZaehler = new java.util.concurrent.atomic.AtomicLong();

    public void mitPruefung(ModerationsDienst moderation) {
        this.moderation = Objects.requireNonNull(moderation);
    }

    public void entferneTreffer(UUID uuid, long id) {
        var aktuell = fenster.get(uuid);
        if (aktuell != null) {
            synchronized (aktuell) {
                aktuell.zeiten.removeIf(t -> t.id() == id);
                var stufen = konfiguration.chatfilterStaffel();
                aktuell.angewendet.removeIf(index -> index >= stufen.size()
                        || stufen.get(index).anzahl() > aktuell.zeiten.size());
            }
        }
    }

    public ChatfilterStaffelService(VelocityKonfiguration konfiguration, WarnService warnService,
                                    MuteService muteService, BroadcastService broadcastService) {
        this.konfiguration = Objects.requireNonNull(konfiguration, "Die Konfiguration darf nicht null sein.");
        this.warnService = Objects.requireNonNull(warnService, "Der WarnService darf nicht null sein.");
        this.muteService = Objects.requireNonNull(muteService, "Der MuteService darf nicht null sein.");
        this.broadcastService = Objects.requireNonNull(broadcastService, "Der BroadcastService darf nicht null sein.");
    }

    public void melde(UUID uuid, String name, Protokoll.ChatfilterTreffer treffer) {
        ModerationsDienst pruefung = moderation;
        if (pruefung == null) {
            meldeIntern(uuid, name, treffer);
        } else {
            synchronized (pruefung) {
                meldeIntern(uuid, name, treffer);
            }
        }
    }

    private void meldeIntern(UUID uuid, String name, Protokoll.ChatfilterTreffer treffer) {
        Objects.requireNonNull(uuid, "Die UUID darf nicht null sein.");
        Objects.requireNonNull(treffer, "Der Treffer darf nicht null sein.");
        if (!konfiguration.chatfilterAktiv()) {
            return;
        }
        String spielerName = name == null || name.isBlank() ? treffer.name() : name;
        ModerationsDienst pruefung = moderation;
        long trefferId = pruefung == null ? lokalerZaehler.incrementAndGet()
                : pruefung.treffer(uuid, spielerName, treffer).id();
        if (konfiguration.chatfilterTeamInfo()) {
            sendeTeamInfo(spielerName, treffer);
        }
        long jetzt = System.currentTimeMillis();
        if (konfiguration.chatfilterBeobachten() || treffer.beobachtung()) {
            return;
        }
        long fensterMillis = konfiguration.chatfilterFensterMillis();
        var stufen = konfiguration.chatfilterStaffel();
        if (stufen.isEmpty()) {
            return;
        }
        if (fenster.get(uuid) == null) {
            raeumeFenster(jetzt, fensterMillis);
        }
        int[] anzahl = {0};
        java.util.List<Integer> neu = new java.util.ArrayList<>();
        fenster.compute(uuid, (id, vorhanden) -> {
            Fenster aktuell = vorhanden != null
                    ? vorhanden
                    : new Fenster(new ArrayDeque<>(), new HashSet<>());
            synchronized (aktuell) {
                while (!aktuell.zeiten.isEmpty()
                        && jetzt - aktuell.zeiten.peekFirst().zeit() > fensterMillis) {
                    aktuell.zeiten.pollFirst();
                }
                aktuell.zeiten.addLast(new ZaehlTreffer(trefferId, jetzt));
                anzahl[0] = aktuell.zeiten.size();
                aktuell.angewendet.removeIf(index -> index >= stufen.size()
                        || stufen.get(index).anzahl() > anzahl[0]);
                for (int i = 0; i < stufen.size(); i++) {
                    if (anzahl[0] >= stufen.get(i).anzahl() && aktuell.angewendet.add(i)) {
                        neu.add(i);
                    }
                }
            }
            return aktuell;
        });
        for (int index : neu) {
            java.util.List<Long> belege;
            var aktuell = fenster.get(uuid);
            synchronized (aktuell) {
                belege = aktuell.zeiten.stream().map(ZaehlTreffer::id).toList();
            }
            wendeAn(uuid, spielerName, anzahl[0], stufen.get(index), trefferId, belege);
        }
    }

    private void raeumeFenster(long jetzt, long fensterMillis) {
        if (fenster.size() < MAX_SPIELER) {
            return;
        }
        fenster.entrySet().removeIf(e -> istFensterAbgelaufen(e.getValue(), jetzt, fensterMillis));
        while (fenster.size() >= MAX_SPIELER) {
            UUID aeltester = null;
            Fenster aeltestes = null;
            long aeltesterZeit = Long.MAX_VALUE;
            for (var eintrag : fenster.entrySet()) {
                ZaehlTreffer zuletzt;
                synchronized (eintrag.getValue()) {
                    zuletzt = eintrag.getValue().zeiten.peekLast();
                }
                long zeit = zuletzt == null ? Long.MIN_VALUE : zuletzt.zeit();
                if (zeit < aeltesterZeit) {
                    aeltesterZeit = zeit;
                    aeltester = eintrag.getKey();
                    aeltestes = eintrag.getValue();
                }
            }
            if (aeltester == null || !fenster.remove(aeltester, aeltestes)) {
                return;
            }
            LOG.warn("Chatfilter-Fenster hat das Limit {} erreicht – ältestes Fenster ({}) verworfen.",
                    MAX_SPIELER, aeltester);
        }
    }

    private static boolean istFensterAbgelaufen(Fenster fenster, long jetzt, long fensterMillis) {
        synchronized (fenster) {
            ZaehlTreffer zuletzt = fenster.zeiten.peekLast();
            return zuletzt == null || jetzt - zuletzt.zeit() > fensterMillis;
        }
    }

    int fensterGroesse() {
        return fenster.size();
    }

    private void sendeTeamInfo(String name, Protokoll.ChatfilterTreffer treffer) {
        try {
            boolean klartext = true;
            try {
                klartext = konfiguration.chatfilterKlartextUbertragen();
            } catch (Exception e) {
                klartext = true;
            }
            String text;
            if (klartext) {
                text = treffer.text() == null ? "" : treffer.text().replaceAll("\\s+", " ").trim();
                if (text.length() > MAX_TEXTBREITE) {
                    text = text.substring(0, MAX_TEXTBREITE) + "…";
                }
                if (text.isBlank()) {
                    text = "Hash " + kurzHash(treffer.textHash());
                }
            } else {
                text = "Hash " + kurzHash(treffer.textHash());
            }
            String score = Math.round(treffer.bewertung() * 100.0d) + "%";
            broadcastService.senden(MessageKeys.BROADCAST_CHATFILTER_TREFFER, Map.of(
                    "spieler", name,
                    "quelle", treffer.quelle(),
                    "kanal", FilterKanal.anzeigeFuer(treffer.kanal()),
                    "score", score,
                    "text", text));
        } catch (Exception e) {
            LOG.debug("Chatfilter-Team-Info fehlgeschlagen.", e);
        }
    }

    static String kurzHash(String hash) {
        if (hash == null || hash.isBlank()) {
            return "unbekannt";
        }
        String getrimmt = hash.trim();
        return getrimmt.length() <= 12 ? getrimmt : getrimmt.substring(0, 12);
    }

    private void wendeAn(UUID uuid, String name, int anzahl, Stufe stufe, long trefferId, java.util.List<Long> belege) {
        String grund = stufe.grund().replace("%anzahl%", String.valueOf(anzahl));
        try {
            Map<String, Object> zielDaten = new java.util.LinkedHashMap<>();
            Runnable auftrag = () -> {
                switch (stufe.aktion()) {
                    case WARN -> {
                        var warn = warnService.hinzufuegen(uuid, name, grund, AUSFUEHRER);
                        if (warn != null) {
                            zielDaten.put("warn-nummer", warn.nummer());
                            zielDaten.put("erstellt", warn.erstelltAm());
                        }
                    }
                    case MUTE -> {
                        boolean ok = muteService.muten(uuid, name, stufe.dauerMillis(), grund, AUSFUEHRER);
                        if (!ok) {
                            LOG.debug("Chatfilter-Mute für {} nicht wirksam (bereits gemutet?).", name);
                        } else {
                            muteService.finden(uuid, de.lightrixx.punish.model.ServerScope.unbekannt())
                                    .ifPresent(m -> zielDaten.put("erstellt", m.erstelltAm()));
                        }
                    }
                }
            };
            ModerationsDienst pruefung = moderation;
            if (pruefung == null) {
                auftrag.run();
            } else {
                var ereignisse = pruefung.erfasse(auftrag);
                pruefung.aktion(trefferId, stufe.anzahl(), konfiguration.chatfilterFensterMillis(), belege, zielDaten, ereignisse);
                if (!ereignisse.isEmpty()) {
                    var fall = pruefung.erstellen(uuid, name, "Chatfilter-Aktion", AUSFUEHRER);
                    pruefung.eintrag(fall.id(), AUSFUEHRER, "PRUEFUNG", trefferId, "Chatfilter-Treffer #" + trefferId);
                    for (var ereignis : ereignisse) {
                        if (uuid.equals(ereignis.uuid())) {
                            pruefung.historyVerknuepfen(fall.id(), ereignis);
                        }
                    }
                }
            }
            LOG.info("Chatfilter-Staffel: {} Treffer für {} -> {} ({}).", anzahl, name,
                    stufe.aktion(), grund);
        } catch (IllegalStateException e) {
            LOG.debug("Chatfilter-Staffel für {} abgewiesen (Schutz?).", name, e);
        } catch (Exception e) {
            LOG.warn("Chatfilter-Staffel für {} fehlgeschlagen.", name, e);
        }
    }

    public static long parseDauerMillis(String roh, long fallback) {
        if (roh == null || roh.isBlank()) {
            return fallback;
        }
        try {
            var geparst = new TimeParser().parsen(roh.trim());
            if (geparst instanceof ParseResult.Geltzeit(long millis)) {
                return millis;
            }
        } catch (Exception e) {
            LOG.debug("Chatfilter-Dauer '{}' ungültig.", roh, e);
        }
        return fallback;
    }

    public static Aktion parseAktion(String roh) {
        if (roh == null) {
            return null;
        }
        try {
            return Aktion.valueOf(roh.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
