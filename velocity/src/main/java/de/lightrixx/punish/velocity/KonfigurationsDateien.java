package de.lightrixx.punish.velocity;

import de.lightrixx.punish.storage.YamlSpeicher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class KonfigurationsDateien {

    private static final Logger LOG = LoggerFactory.getLogger(KonfigurationsDateien.class);

    private static final String R_GENERAL = "/config/general.yml";
    private static final String R_MESSAGES = "/config/messages.yml";
    private static final String R_SCREENS = "/config/screens.yml";
    private static final String R_BROADCASTS = "/config/broadcasts.yml";

    private static final List<String> GENERAL_KEYS = List.of(
            "prefix", "netzwerk", "anzeige-zone", "schutz", "offline-resolution",
            "speicher", "datenschutz", "verstoesse", "warns", "limits", "bestaetigungen", "history", "mute", "tab",
            "vorschlaege", "defaults", "reports", "chatfilter", "alt-wache", "bann-unban-antrag",
            "rang-buendel", "rang-gewicht", "update", "config-version");

    private final File datenOrdner;
    private final File configOrdner;
    private final File generalDatei;
    private final File messagesDatei;
    private final File screensDatei;
    private final File broadcastsDatei;
    private final File legacyDatei;
    private final YamlSpeicher speicher;

    KonfigurationsDateien(File datenOrdner) {
        this.datenOrdner = Objects.requireNonNull(datenOrdner, "Der Datenordner darf nicht null sein.");
        this.configOrdner = new File(datenOrdner, "config");
        this.generalDatei = new File(configOrdner, "general.yml");
        this.messagesDatei = new File(configOrdner, "messages.yml");
        this.screensDatei = new File(configOrdner, "screens.yml");
        this.broadcastsDatei = new File(configOrdner, "broadcasts.yml");
        this.legacyDatei = new File(datenOrdner, "config.yml");
        this.speicher = new SnakeYamlSpeicher();
    }

    Map<String, Object> laden(java.util.List<String> korrupt) {
        Map<String, Object> zusammen = new java.util.LinkedHashMap<>();
        zusammen = ladeDateiFuerMerge(zusammen, generalDatei, korrupt);
        zusammen = ladeDateiFuerMerge(zusammen, messagesDatei, korrupt);
        zusammen = ladeDateiFuerMerge(zusammen, screensDatei, korrupt);
        zusammen = ladeDateiFuerMerge(zusammen, broadcastsDatei, korrupt);
        try {
            Map<String, Object> defaults = ladeJarDefaults();
            if (!defaults.isEmpty()) {
                zusammen = deepMerge(defaults, zusammen);
            }
        } catch (Exception e) {
            LOG.debug("Jar-Default-Merge übersprungen.", e);
        }
        return zusammen;
    }

    void sicherstellen() {
        if (!configOrdner.exists() && !configOrdner.mkdirs() && !configOrdner.exists()) {
            LOG.warn("Der Config-Ordner {} konnte nicht angelegt werden.", configOrdner.getAbsolutePath());
        }
        sichereRessource(R_GENERAL, generalDatei);
        sichereRessource(R_MESSAGES, messagesDatei);
        sichereRessource(R_SCREENS, screensDatei);
        sichereRessource(R_BROADCASTS, broadcastsDatei);
    }

    private void sichereRessource(String resourcePfad, File ziel) {
        if (ziel.exists()) {
            return;
        }
        try {
            File eltern = ziel.getParentFile();
            if (eltern != null && !eltern.exists()) {
                eltern.mkdirs();
            }
            try (InputStream in = getClass().getResourceAsStream(resourcePfad)) {
                if (in != null) {
                    Files.copy(in, ziel.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } else {
                    LOG.warn("Jar-Default {} nicht gefunden, Datei {} nicht angelegt.", resourcePfad, ziel.getName());
                }
            }
        } catch (Exception e) {
            LOG.warn("Die Standard-Datei {} konnte nicht angelegt werden.", ziel.getName(), e);
        }
    }

    private Map<String, Object> ladeDateiFuerMerge(Map<String, Object> ziel, File datei,
                                                  java.util.List<String> korrupt) {
        var status = de.lightrixx.punish.storage.YamlSpeicher.status(speicher, datei);
        if (status.fehler()) {
            korrupt.add(datei.getName());
        }
        return deepMerge(ziel, status.daten());
    }

    void migriereLegacyFallsNoetig() {
        if (!legacyDatei.isFile()) {
            return;
        }
        Map<String, Object> alt;
        try {
            alt = speicher.laden(legacyDatei);
        } catch (Exception e) {
            LOG.warn("Alte config.yml konnte nicht gelesen werden, Migration übersprungen.", e);
            return;
        }
        if (alt == null || alt.isEmpty()) {
            sichereLegacyAlsBackup();
            return;
        }
        boolean alleNeuExistieren = generalDatei.isFile() && generalDatei.length() > 0
                && messagesDatei.isFile() && messagesDatei.length() > 0
                && screensDatei.isFile() && screensDatei.length() > 0
                && broadcastsDatei.isFile() && broadcastsDatei.length() > 0;
        if (alleNeuExistieren) {
            LOG.info("Alte config.yml gefunden, aber config/*.yml existieren bereits – "
                    + "Legacy wird nur als Backup gesichert, neue Dateien bleiben unberührt.");
            sichereLegacyAlsBackup();
            return;
        }
        LOG.info("Migriere alte config.yml nach config/general.yml, messages.yml, screens.yml, broadcasts.yml ...");
        try {
            if (!configOrdner.exists()) {
                configOrdner.mkdirs();
            }
        } catch (Exception e) {
            LOG.warn("Config-Ordner konnte nicht angelegt werden.", e);
        }
        schreibeTeilDatei(generalDatei, alt, GENERAL_KEYS);
        schreibeTeilDatei(messagesDatei, alt, List.of("messages"));
        schreibeTeilDatei(screensDatei, alt, List.of("screens"));
        schreibeTeilDatei(broadcastsDatei, alt, List.of("broadcasts"));
        sichereLegacyAlsBackup();
        LOG.info("Migration abgeschlossen – alte config.yml als Backup gesichert.");
    }

    private void schreibeTeilDatei(File ziel, Map<String, Object> quelle, List<String> topKeys) {
        if (ziel.isFile() && ziel.length() > 0) {
            try {
                Map<String, Object> bestehend = speicher.laden(ziel);
                boolean geaendert = false;
                for (String key : topKeys) {
                    if (!bestehend.containsKey(key) && quelle.containsKey(key)) {
                        bestehend.put(key, quelle.get(key));
                        geaendert = true;
                    }
                }
                if (geaendert) {
                    speicher.speichern(bestehend, ziel);
                }
                return;
            } catch (Exception e) {
                LOG.warn("Teil-Datei {} konnte nicht ergänzt werden.", ziel.getName(), e);
                return;
            }
        }
        try {
            Map<String, Object> neu = new java.util.LinkedHashMap<>();
            for (String key : topKeys) {
                if (quelle.containsKey(key)) {
                    neu.put(key, quelle.get(key));
                }
            }
            if (!neu.isEmpty()) {
                File eltern = ziel.getParentFile();
                if (eltern != null && !eltern.exists()) {
                    eltern.mkdirs();
                }
                speicher.speichern(neu, ziel);
            }
        } catch (Exception e) {
            LOG.warn("Teil-Datei {} konnte nicht aus Legacy erzeugt werden.", ziel.getName(), e);
        }
    }

    private void sichereLegacyAlsBackup() {
        try {
            File backup = new File(datenOrdner, "config.yml.vor-config-split.bak");
            if (backup.exists()) {
                backup = new File(datenOrdner,
                        "config.yml.vor-config-split-" + System.currentTimeMillis() + ".bak");
            }
            Files.move(legacyDatei.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            LOG.info("Alte config.yml nach {} gesichert.", backup.getName());
        } catch (Exception e) {
            LOG.warn("Alte config.yml konnte nicht als Backup gesichert werden.", e);
        }
    }

    static org.yaml.snakeyaml.Yaml safeYaml() {
        org.yaml.snakeyaml.LoaderOptions optionen = new org.yaml.snakeyaml.LoaderOptions();
        optionen.setAllowDuplicateKeys(false);
        optionen.setMaxAliasesForCollections(50);
        optionen.setCodePointLimit(32 * 1024 * 1024);
        return new org.yaml.snakeyaml.Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(optionen));
    }

    private Map<String, Object> ladeJarDefaults() {
        Map<String, Object> zusammen = new java.util.LinkedHashMap<>();
        for (String pfad : List.of(R_GENERAL, R_MESSAGES, R_SCREENS, R_BROADCASTS)) {
            try (InputStream in = getClass().getResourceAsStream(pfad)) {
                if (in == null) {
                    continue;
                }
                Object geladen = safeYaml().load(in);
                if (!(geladen instanceof Map)) {
                    continue;
                }
                Map<String, Object> teil = new java.util.LinkedHashMap<>();
                for (Map.Entry<?, ?> e : ((Map<?, ?>) geladen).entrySet()) {
                    if (e.getKey() != null) {
                        teil.put(String.valueOf(e.getKey()), e.getValue());
                    }
                }
                zusammen = deepMerge(zusammen, teil);
            } catch (Exception e) {
                LOG.debug("Jar-Default {} konnte nicht geladen werden.", pfad, e);
            }
        }
        return zusammen;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepMerge(Map<String, Object> defaults, Map<String, Object> aktuell) {
        Map<String, Object> ergebnis = new java.util.LinkedHashMap<>(defaults);
        if (aktuell == null) {
            return ergebnis;
        }
        for (Map.Entry<String, Object> e : aktuell.entrySet()) {
            if (e.getKey() == null) {
                continue;
            }
            Object defWert = ergebnis.get(e.getKey());
            Object aktWert = e.getValue();
            if (defWert instanceof Map && aktWert instanceof Map) {
                Map<String, Object> defMap = (Map<String, Object>) defWert;
                Map<String, Object> aktMap = new java.util.LinkedHashMap<>();
                for (Map.Entry<?, ?> ae : ((Map<?, ?>) aktWert).entrySet()) {
                    if (ae.getKey() != null) {
                        aktMap.put(String.valueOf(ae.getKey()), ae.getValue());
                    }
                }
                ergebnis.put(e.getKey(), deepMerge(defMap, aktMap));
            } else {
                ergebnis.put(e.getKey(), aktWert);
            }
        }
        return ergebnis;
    }
}
