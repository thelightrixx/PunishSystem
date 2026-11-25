package de.lightrixx.punish.velocity;

import de.lightrixx.punish.model.ParseResult;
import de.lightrixx.punish.storage.Karten;
import de.lightrixx.punish.util.TimeParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class Strafkatalog {

    private static final Logger LOG = LoggerFactory.getLogger(Strafkatalog.class);

    public record Stufe(String befehl, String dauer, long millis) {
        public String recht() {
            return "punish." + befehl;
        }

        public String anzeige() {
            return befehl + (dauer.isBlank() ? "" : " " + dauer);
        }
    }

    public record Regel(String id, String grund, long fensterMillis, List<Stufe> stufen) {
        public Regel {
            stufen = List.copyOf(stufen);
            if (stufen.isEmpty()) {
                throw new IllegalArgumentException("Die Strafregel enthält keine Stufen.");
            }
        }

        public int stufenNummer(long vorherige) {
            return (int) Math.min(vorherige + 1, stufen.size());
        }
    }

    private Strafkatalog() {
    }

    public static Map<String, Regel> lesen(Map<String, Object> config) {
        Map<String, Object> regeln = Karten.abschnitt(config, "regeln");
        if (regeln == null) {
            return Map.of();
        }
        Map<String, Regel> ergebnis = new LinkedHashMap<>();
        for (var e : regeln.entrySet()) {
            try {
                String id = e.getKey().toLowerCase(Locale.ROOT);
                if (!id.matches("[a-z0-9_-]{1,40}")) {
                    throw new IllegalArgumentException("Die Regel-ID ist ungültig.");
                }
                Map<String, Object> roh = Karten.abschnitt(e.getValue());
                String grund = ModerationsDienst.text(Karten.text(roh, "grund", id), 256);
                long fenster = Math.clamp(Karten.zahl(roh, "fenster-tage", 30), 1, 3650) * 86_400_000L;
                List<Stufe> stufen = new ArrayList<>();
                for (Object element : Karten.liste(roh, "stufen")) {
                    Map<String, Object> stufe = Karten.abschnitt(element);
                    String befehl = Karten.text(stufe, "befehl", "").toLowerCase(Locale.ROOT);
                    if (!List.of("warn", "mute", "timeout", "ban", "permabann", "freeze").contains(befehl)) {
                        throw new IllegalArgumentException("Unbekannte Strafart: " + befehl);
                    }
                    String dauer = Karten.text(stufe, "dauer", "");
                    long millis = 0;
                    if (!List.of("warn", "permabann").contains(befehl)) {
                        if (!(new TimeParser().parsen(dauer) instanceof ParseResult.Geltzeit(long wert)) || wert <= 0) {
                            throw new IllegalArgumentException("Ungültige Strafdauer.");
                        }
                        millis = wert;
                    }
                    stufen.add(new Stufe(befehl, dauer, millis));
                }
                ergebnis.put(id, new Regel(id, grund, fenster, stufen));
            } catch (IllegalArgumentException e1) {
                LOG.warn("Strafkatalog-Regel '{}' ist ungültig und bleibt deaktiviert: {}", e.getKey(), e1.getMessage());
            }
        }
        return Map.copyOf(ergebnis);
    }
}
