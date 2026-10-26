package de.lightrixx.punish.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RangBuendel {

    private static final Logger LOG = LoggerFactory.getLogger(RangBuendel.class);

    public static final String PREFIX = "punish.";

    private static volatile Map<String, Set<String>> buendel = Map.of();

    private RangBuendel() {
    }

    public static void laden(Map<String, ? extends Collection<String>> rohdaten) {
        if (rohdaten == null || rohdaten.isEmpty()) {
            buendel = Map.of();
            return;
        }
        Map<String, Set<String>> neu = new LinkedHashMap<>();
        for (Map.Entry<String, ? extends Collection<String>> eintrag : rohdaten.entrySet()) {
            if (eintrag.getKey() == null || eintrag.getValue() == null) {
                continue;
            }
            String gruppe = eintrag.getKey().trim().toLowerCase(Locale.ROOT);
            if (gruppe.isEmpty()) {
                continue;
            }
            Set<String> knoten = new LinkedHashSet<>();
            for (String roh : eintrag.getValue()) {
                if (roh != null && !roh.isBlank()) {
                    knoten.add(roh.trim().toLowerCase(Locale.ROOT));
                }
            }
            if (!knoten.isEmpty()) {
                neu.put(gruppe, Set.copyOf(knoten));
            }
        }
        buendel = Map.copyOf(neu);
    }

    public static void ladenAusAbschnitt(Map<String, Object> abschnitt) {
        if (abschnitt == null || abschnitt.isEmpty()) {
            buendel = Map.of();
            return;
        }
        Map<String, Collection<String>> roh = new LinkedHashMap<>();
        for (Map.Entry<String, Object> eintrag : abschnitt.entrySet()) {
            if (eintrag.getKey() == null || !(eintrag.getValue() instanceof Collection<?> liste)) {
                continue;
            }
            List<String> knoten = new ArrayList<>();
            for (Object wert : liste) {
                if (wert != null && !String.valueOf(wert).isBlank()) {
                    knoten.add(String.valueOf(wert));
                }
            }
            roh.put(eintrag.getKey(), knoten);
        }
        laden(roh);
    }

    public static boolean gewaehrt(Predicate<String> hat, String knoten) {
        if (hat == null || knoten == null || knoten.isBlank()) {
            return false;
        }
        String normiert = knoten.trim().toLowerCase(Locale.ROOT);
        try {
            if (hat.test(knoten)) {
                return true;
            }
        } catch (Exception e) {
            return false;
        }
        if (!normiert.startsWith(PREFIX)) {
            return false;
        }
        try {
            if (hat.test("punish.*") || hat.test("punish.all")) {
                return true;
            }
        } catch (Exception e) {
            return false;
        }
        Map<String, Set<String>> stand = buendel;
        if (stand.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, Set<String>> eintrag : stand.entrySet()) {
            Set<String> enthalten = eintrag.getValue();
            if (!enthalten.contains(normiert) && !enthalten.contains(PREFIX + "*")) {
                continue;
            }
            try {
                if (hat.test(PREFIX + eintrag.getKey())) {
                    return true;
                }
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    static Map<String, Set<String>> stand() {
        return buendel;
    }

    private static volatile Map<String, Integer> gewichte = Map.of();

    static final Map<String, Integer> STANDARD_GEWICHTE = Map.ofEntries(
            Map.entry("default", 0),
            Map.entry("iron", 0),
            Map.entry("gold", 0),
            Map.entry("diamond", 0),
            Map.entry("builder", 0),
            Map.entry("builderplus", 0),
            Map.entry("creator", 0),
            Map.entry("developer", 0),
            Map.entry("test-sup", 10),
            Map.entry("supporter", 20),
            Map.entry("community", 20),
            Map.entry("moderator", 50),
            Map.entry("manager", 50),
            Map.entry("richter", 60),
            Map.entry("admin", 80),
            Map.entry("p-admin", 80),
            Map.entry("vize", 90),
            Map.entry("präsident", 100));

    public static void ladenGewichte(Map<String, Integer> rohdaten) {
        if (rohdaten == null || rohdaten.isEmpty()) {
            gewichte = Map.of();
            return;
        }
        Map<String, Integer> neu = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> eintrag : rohdaten.entrySet()) {
            if (eintrag.getKey() == null || eintrag.getValue() == null) {
                continue;
            }
            String gruppe = eintrag.getKey().trim().toLowerCase(Locale.ROOT);
            if (gruppe.isEmpty()) {
                continue;
            }
            neu.put(gruppe, Math.max(0, eintrag.getValue()));
        }
        gewichte = Map.copyOf(neu);
    }

    public static void ladenGewichteAusAbschnitt(Map<String, Object> abschnitt) {
        if (abschnitt == null || abschnitt.isEmpty()) {
            gewichte = Map.of();
            return;
        }
        Map<String, Integer> roh = new LinkedHashMap<>();
        for (Map.Entry<String, Object> eintrag : abschnitt.entrySet()) {
            if (eintrag.getKey() == null || eintrag.getValue() == null) {
                continue;
            }
            try {
                long wert;
                Object v = eintrag.getValue();
                if (v instanceof Number n) {
                    wert = n.longValue();
                } else {
                    wert = Long.parseLong(String.valueOf(v).trim());
                }
                if (wert < 0 || wert > 1_000_000) {
                    continue;
                }
                roh.put(eintrag.getKey(), (int) wert);
            } catch (Exception e) {
                continue;
            }
        }
        ladenGewichte(roh);
    }

    public static int gewichtFuerGruppe(String gruppe) {
        if (gruppe == null || gruppe.isBlank()) {
            return 0;
        }
        String normiert = gruppe.trim().toLowerCase(Locale.ROOT);
        Integer konfiguriert = gewichte.get(normiert);
        if (konfiguriert != null) {
            return konfiguriert;
        }
        return STANDARD_GEWICHTE.getOrDefault(normiert, 0);
    }

    public static int maxGewichtFuer(Predicate<String> hat) {
        if (hat == null) {
            return 0;
        }
        int max = 0;
        Map<String, Integer> g = gewichte.isEmpty() ? STANDARD_GEWICHTE : gewichte;
        for (Map.Entry<String, Integer> eintrag : g.entrySet()) {
            if (eintrag.getKey() == null || eintrag.getValue() == null) {
                continue;
            }
            try {
                if (hat.test(PREFIX + eintrag.getKey())) {
                    max = Math.max(max, eintrag.getValue());
                }
            } catch (Exception e) {
                LOG.debug("Rechteprüfung für Bündel '{}' fehlgeschlagen.", eintrag.getKey(), e);
            }
        }
        try {
            if (hat.test("punish.*") || hat.test("punish.all")) {
                max = Math.max(max, 100);
            }
        } catch (Exception e) {
            LOG.debug("Wildcard-Rechteprüfung fehlgeschlagen.", e);
        }
        return max;
    }

    static Map<String, Integer> gewichtStand() {
        return gewichte;
    }
}
