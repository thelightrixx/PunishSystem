package de.lightrixx.punish.storage;

import java.io.File;
import java.util.Map;

public interface YamlSpeicher {

    /**
     * Ergebnis eines Ladevorgangs. {@code fehler} unterscheidet einen Leseproblemfall von einem
     * gueltigen leeren Bestand; {@code dateiVorhanden} trennt eine fehlende Datei von einer leeren.
     */
    record LadeStatus(Map<String, Object> daten, boolean dateiVorhanden, boolean fehler) {

        public LadeStatus {
            daten = daten == null ? Map.of() : daten;
        }
    }

    Map<String, Object> laden(File datei);

    /**
     * Liefert den Status eines Ladevorgangs. Faellt auf {@link #laden(File)} zurueck, wenn eine
     * Implementierung (z. B. ein Test-Double) nur diese Methode bereitstellt.
     */
    static LadeStatus status(YamlSpeicher speicher, File datei) {
        LadeStatus status = speicher.ladenMitStatus(datei);
        if (status != null) {
            return status;
        }
        return new LadeStatus(speicher.laden(datei), datei != null && datei.exists(), false);
    }

    default LadeStatus ladenMitStatus(File datei) {
        boolean vorhanden = datei != null && datei.exists();
        return new LadeStatus(laden(datei), vorhanden, false);
    }

    void speichern(Map<String, Object> daten, File datei);
}
