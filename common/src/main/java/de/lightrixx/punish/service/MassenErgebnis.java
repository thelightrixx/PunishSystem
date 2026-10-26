package de.lightrixx.punish.service;

public record MassenErgebnis(int anzahl, int gekuerzt) {

    public MassenErgebnis {
        if (anzahl < 0) {
            anzahl = 0;
        }
        if (gekuerzt < 0) {
            gekuerzt = 0;
        }
    }

    public static MassenErgebnis leer() {
        return new MassenErgebnis(0, 0);
    }
}
