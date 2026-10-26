package de.lightrixx.punish.chatfilter;

import java.util.Locale;

public enum FilterKanal {
    CHAT("Chat"),
    PRIVATNACHRICHT("Privatnachricht"),
    SCHILD("Schild"),
    BUCH("Buch"),
    AMBOSS("Amboss");

    private final String anzeige;

    FilterKanal(String anzeige) {
        this.anzeige = anzeige;
    }

    public String anzeige() {
        return anzeige;
    }

    public static String anzeigeFuer(String roh) {
        if (roh == null || roh.isBlank()) {
            return CHAT.anzeige;
        }
        String getrimmt = roh.trim();
        String normalisiert = getrimmt.toUpperCase(Locale.ROOT);
        for (FilterKanal kanal : values()) {
            if (kanal.name().equals(normalisiert)) {
                return kanal.anzeige;
            }
        }
        for (FilterKanal kanal : values()) {
            if (kanal.anzeige.equalsIgnoreCase(getrimmt)) {
                return kanal.anzeige;
            }
        }
        return getrimmt;
    }
}
