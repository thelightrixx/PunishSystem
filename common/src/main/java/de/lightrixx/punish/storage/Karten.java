package de.lightrixx.punish.storage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class Karten {

    private Karten() {
    }

    public static Map<String, Object> abschnitt(Object roh) {
        if (roh instanceof Map<?, ?> karte) {
            Map<String, Object> ergebnis = new LinkedHashMap<>();
            for (Map.Entry<?, ?> eintrag : karte.entrySet()) {
                if (eintrag.getKey() != null) {
                    ergebnis.put(String.valueOf(eintrag.getKey()), eintrag.getValue());
                }
            }
            return ergebnis;
        }
        return null;
    }

    public static Map<String, Object> abschnitt(Map<String, Object> karte, String schluessel) {
        if (karte == null || schluessel == null) {
            return null;
        }
        return abschnitt(karte.get(schluessel));
    }

    public static String text(Map<String, Object> karte, String schluessel, String standard) {
        if (karte == null || schluessel == null) {
            return standard;
        }
        Object roh = karte.get(schluessel);
        if (roh == null) {
            return standard;
        }
        if (roh instanceof String text) {
            return text;
        }
        return String.valueOf(roh);
    }

    public static long zahl(Map<String, Object> karte, String schluessel, long standard) {
        if (karte == null || schluessel == null) {
            return standard;
        }
        Object roh = karte.get(schluessel);
        if (roh instanceof Number nummer) {
            return nummer.longValue();
        }
        if (roh instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException e) {
                return standard;
            }
        }
        return standard;
    }

    static Long zahlOderNull(Map<String, Object> karte, String schluessel) {
        if (karte == null || schluessel == null) {
            return null;
        }
        Object roh = karte.get(schluessel);
        if (roh == null) {
            return null;
        }
        if (roh instanceof Number nummer) {
            return nummer.longValue();
        }
        if (roh instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Ungültiger Zahlenwert für '" + schluessel + "': " + text, e);
            }
        }
        throw new IllegalArgumentException(
                "Ungültiger Typ für '" + schluessel + "': " + roh.getClass().getName());
    }

    public static boolean wahr(Map<String, Object> karte, String schluessel, boolean standard) {
        if (karte == null || schluessel == null) {
            return standard;
        }
        Object roh = karte.get(schluessel);
        if (roh instanceof Boolean wahr) {
            return wahr;
        }
        if (roh instanceof Number nummer) {
            return nummer.intValue() != 0;
        }
        if (roh instanceof String text) {
            String t = text.trim().toLowerCase(Locale.ROOT);
            if (t.equals("true") || t.equals("yes") || t.equals("on") || t.equals("1")) {
                return true;
            }
            if (t.equals("false") || t.equals("no") || t.equals("off") || t.equals("0")) {
                return false;
            }
        }
        return standard;
    }

    public static boolean hat(Map<String, Object> karte, String schluessel) {
        return karte != null && schluessel != null && karte.containsKey(schluessel);
    }

    public static List<Object> liste(Map<String, Object> karte, String schluessel) {
        if (karte == null || schluessel == null) {
            return List.of();
        }
        Object roh = karte.get(schluessel);
        if (roh instanceof List<?> liste) {
            return new ArrayList<>(liste);
        }
        return List.of();
    }
}
