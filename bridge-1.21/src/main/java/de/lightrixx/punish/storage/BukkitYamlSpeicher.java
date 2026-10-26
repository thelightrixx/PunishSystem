package de.lightrixx.punish.storage;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class BukkitYamlSpeicher implements YamlSpeicher {

    private final YamlUtil yamlUtil;

    public BukkitYamlSpeicher(YamlUtil yamlUtil) {
        this.yamlUtil = Objects.requireNonNull(yamlUtil, "Das YAML-Werkzeug darf nicht null sein.");
    }

    @Override
    public Map<String, Object> laden(File datei) {
        return ladenMitStatus(datei).daten();
    }

    @Override
    public LadeStatus ladenMitStatus(File datei) {
        Objects.requireNonNull(datei, "Die Datei darf nicht null sein.");
        YamlUtil.Ladung ladung = yamlUtil.ladenMitStatus(datei);
        return new LadeStatus(zuKarte(ladung.konfiguration().getValues(false)),
                ladung.dateiVorhanden(), ladung.fehler());
    }

    @Override
    public void speichern(Map<String, Object> daten, File datei) {
        Objects.requireNonNull(daten, "Die Daten dürfen nicht null sein.");
        Objects.requireNonNull(datei, "Die Datei darf nicht null sein.");
        YamlConfiguration konfiguration = new YamlConfiguration();
        for (Map.Entry<String, Object> eintrag : daten.entrySet()) {
            if (eintrag.getKey() == null) {
                continue;
            }
            setze(konfiguration, eintrag.getKey(), eintrag.getValue());
        }
        yamlUtil.speichern(konfiguration, datei);
    }

    static Map<String, Object> zuKarte(Map<String, Object> roh) {
        Map<String, Object> ergebnis = new LinkedHashMap<>();
        if (roh == null) {
            return ergebnis;
        }
        for (Map.Entry<String, Object> eintrag : roh.entrySet()) {
            if (eintrag.getKey() == null) {
                continue;
            }
            ergebnis.put(eintrag.getKey(), bereinige(eintrag.getValue()));
        }
        return ergebnis;
    }

    private static Object bereinige(Object wert) {
        if (wert instanceof ConfigurationSection section) {
            return zuKarte(section.getValues(false));
        }
        if (wert instanceof Map<?, ?> karte) {
            Map<String, Object> ergebnis = new LinkedHashMap<>();
            for (Map.Entry<?, ?> eintrag : karte.entrySet()) {
                if (eintrag.getKey() != null) {
                    ergebnis.put(String.valueOf(eintrag.getKey()), bereinige(eintrag.getValue()));
                }
            }
            return ergebnis;
        }
        if (wert instanceof List<?> liste) {
            List<Object> ergebnis = new ArrayList<>(liste.size());
            for (Object element : liste) {
                ergebnis.add(bereinige(element));
            }
            return ergebnis;
        }
        return wert;
    }

    private static void setze(FileConfiguration konfiguration, String pfad, Object wert) {
        if (wert instanceof Map<?, ?> karte) {
            for (Map.Entry<?, ?> eintrag : karte.entrySet()) {
                if (eintrag.getKey() == null) {
                    continue;
                }
                setze(konfiguration, pfad + "." + eintrag.getKey(), eintrag.getValue());
            }
            return;
        }
        if (wert instanceof List<?> liste) {
            List<Object> bereinigt = new ArrayList<>(liste.size());
            for (Object element : liste) {
                bereinigt.add(bereinige(element));
            }
            konfiguration.set(pfad, bereinigt);
            return;
        }
        konfiguration.set(pfad, wert);
    }
}
