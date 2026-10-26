package de.lightrixx.punish.chatfilter;

public interface ToxizitaetsModell {

    /**
     * Bewertet einen Chat-Text. Gibt einen Wert zwischen 0 (unbedenklich) und 1 (toxisch) zurück.
     * Bei Timeout oder Modellfehler wird {@link ChatfilterAusfall} geworfen (Fail-Open beim Aufrufer).
     */
    double bewerte(String text);
}
