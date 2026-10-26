package de.lightrixx.punish.core;

public interface AuftragsPlaner {

    GeplanteAufgabe aufgabe(Runnable auftrag);

    GeplanteAufgabe aufgabeAsync(Runnable auftrag);

    GeplanteAufgabe spaeter(Runnable auftrag, long verzoegerungTicks);

    GeplanteAufgabe spaeterAsync(Runnable auftrag, long verzoegerungTicks);

    GeplanteAufgabe intervallAsync(Runnable auftrag, long startTicks, long periodeTicks);

    void alleAbbrechen();

    boolean istGeschlossen();
}
