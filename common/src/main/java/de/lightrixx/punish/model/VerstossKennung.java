package de.lightrixx.punish.model;

import java.util.Objects;

public final class VerstossKennung {

    private VerstossKennung() {
    }

    public static String kennung(VerstossRecord verstoss) {
        Objects.requireNonNull(verstoss, "Der Verstoß darf nicht null sein.");
        String grund = verstoss.grund() == null || verstoss.grund().isBlank()
                ? "Unbekannt" : verstoss.grund().trim();
        String zeit = de.lightrixx.punish.util.ZeitFormat.formatZeitpunkt(verstoss.erstelltAm());
        String ausfuehrer = verstoss.ausfuehrer() == null || verstoss.ausfuehrer().isBlank()
                ? "Unbekannt" : verstoss.ausfuehrer().trim();
        return grund + "_" + zeit + "_" + ausfuehrer + "#" + verstoss.nummer();
    }
}
