package de.lightrixx.punish.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.RangBuendel;
import de.lightrixx.punish.model.ParseResult;
import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.model.VerstossStufe;
import de.lightrixx.punish.storage.Karten;
import de.lightrixx.punish.storage.YamlSpeicher;
import de.lightrixx.punish.storage.mysql.MysqlKonfiguration;
import de.lightrixx.punish.storage.mysql.SpeicherTyp;
import de.lightrixx.punish.util.TimeParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class VelocityKonfiguration implements Konfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(VelocityKonfiguration.class);
    private static final String STANDARD_PREFIX = "<dark_gray>[<red>PunishSystem<dark_gray>]";
    private static final String STANDARD_NACHRICHT = "<red>Eine Nachricht konnte nicht geladen werden.";
    private static final String STANDARD_GRUND = "Regelverstoß";

    private static final String R_GENERAL = "/config/general.yml";
    private static final String R_MESSAGES = "/config/messages.yml";
    private static final String R_SCREENS = "/config/screens.yml";
    private static final String R_BROADCASTS = "/config/broadcasts.yml";

    private static final List<String> GENERAL_KEYS = List.of(
            "prefix", "netzwerk", "anzeige-zone", "schutz", "offline-resolution",
            "speicher", "datenschutz", "verstoesse", "warns", "limits", "bestaetigungen", "history", "mute", "tab",
            "vorschlaege", "defaults", "reports", "chatfilter", "rang-buendel", "rang-gewicht",
            "update", "config-version");

    private final ProxyServer proxy;
    private final File datenOrdner;
    private final File configOrdner;
    private final File generalDatei;
    private final File messagesDatei;
    private final File screensDatei;
    private final File broadcastsDatei;
    private final File legacyDatei;
    private final YamlSpeicher speicher;
    private volatile Map<String, Object> konfiguration = Map.of();
    private volatile String letzterLadefehler = "";

    public VelocityKonfiguration(ProxyServer proxy, File datenOrdner) {
        this.proxy = Objects.requireNonNull(proxy, "Der Proxy darf nicht null sein.");
        this.datenOrdner = Objects.requireNonNull(datenOrdner, "Der Datenordner darf nicht null sein.");
        this.configOrdner = new File(datenOrdner, "config");
        this.generalDatei = new File(configOrdner, "general.yml");
        this.messagesDatei = new File(configOrdner, "messages.yml");
        this.screensDatei = new File(configOrdner, "screens.yml");
        this.broadcastsDatei = new File(configOrdner, "broadcasts.yml");
        this.legacyDatei = new File(datenOrdner, "config.yml");
        this.speicher = new SnakeYamlSpeicher();
    }

    public void sicherstellen() {
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

    public void laden() {
        try {
            migriereLegacyFallsNoetig();
        } catch (Exception e) {
            LOG.warn("Legacy-Migration übersprungen.", e);
        }
        try {
            sicherstellen();
        } catch (Exception e) {
            LOG.warn("Standard-Config konnte nicht sichergestellt werden.", e);
        }
        Map<String, Object> zusammen = new java.util.LinkedHashMap<>();
        java.util.List<String> korrupt = new java.util.ArrayList<>();
        zusammen = ladeDateiFuerMerge(zusammen, generalDatei, korrupt);
        zusammen = ladeDateiFuerMerge(zusammen, messagesDatei, korrupt);
        zusammen = ladeDateiFuerMerge(zusammen, screensDatei, korrupt);
        zusammen = ladeDateiFuerMerge(zusammen, broadcastsDatei, korrupt);
        try {
            Map<String, Object> defaults = ladeJarDefaults();
            if (defaults != null && !defaults.isEmpty()) {
                zusammen = deepMerge(defaults, zusammen);
            }
        } catch (Exception e) {
            LOG.debug("Jar-Default-Merge übersprungen.", e);
        }
        if (!korrupt.isEmpty() && !this.konfiguration.isEmpty()) {
            letzterLadefehler = String.join(", ", korrupt)
                    + " (Datei(en) unlesbar/leer – alter Config-Stand bleibt aktiv, siehe Log)";
            LOG.error("Config-Load abgebrochen: {} – bisherige Konfiguration bleibt aktiv.", letzterLadefehler);
            LOG.info("Die Konfiguration wurde NICHT neu geladen (korrupte Datei).");
            return;
        }
        if (!korrupt.isEmpty()) {
            letzterLadefehler = String.join(", ", korrupt)
                    + " (Datei(en) unlesbar/leer – es gelten die Jar-Defaults, siehe Log)";
            LOG.error("Config-Load mit Fehlern: {} – es gelten die Jar-Defaults bis zur Korrektur.",
                    letzterLadefehler);
        } else {
            letzterLadefehler = "";
        }
        this.konfiguration = zusammen;
        try {
            RangBuendel.ladenAusAbschnitt(Karten.abschnitt(zusammen, "rang-buendel"));
        } catch (Exception e) {
            LOG.warn("Rang-Bündel konnten nicht geladen werden (nur direkte Rechte aktiv).", e);
        }
        try {
            RangBuendel.ladenGewichteAusAbschnitt(Karten.abschnitt(zusammen, "rang-gewicht"));
        } catch (Exception e) {
            LOG.debug("Rang-Gewichte konnten nicht geladen werden, nutze Standards.", e);
        }
        pruefeConfigVersion();
        validiere();
        LOG.info("Die Konfiguration wurde geladen (config/general.yml, messages.yml, screens.yml, broadcasts.yml).");
    }

    public String letzterLadefehler() {
        return letzterLadefehler;
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

    private static org.yaml.snakeyaml.Yaml safeYaml() {
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
        return zusammen.isEmpty() ? null : zusammen;
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

    @Override
    public String configVersion() {        try {
            Object roh = konfiguration.get("config-version");
            if (roh == null || String.valueOf(roh).isBlank()) {
                return Konfiguration.AKTUELLE_CONFIG_VERSION;
            }
            return String.valueOf(roh).trim();
        } catch (Exception e) {
            return Konfiguration.AKTUELLE_CONFIG_VERSION;
        }
    }

    void pruefeConfigVersion() {        try {
            Object roh = konfiguration.get("config-version");
            if (roh == null || String.valueOf(roh).isBlank()) {
                return;
            }
            String config = String.valueOf(roh).trim();
            if (Konfiguration.istDowngrade(config)) {
                LOG.warn("Downgrade-Warnung: config-version {} ist neuer als diese Jar ({}). "
                                + "Neue Optionen werden ignoriert, kein Startabbruch. Für volle Funktion updaten.",
                        config, Konfiguration.AKTUELLE_CONFIG_VERSION);
            }
        } catch (Exception e) {
            LOG.debug("Config-Versionscheck übersprungen.", e);
        }
    }

    private void validiere() {
        if (holeInt("verstoesse", "max-punkte", 6) < 1) {
            LOG.warn("verstoesse.max-punkte < 1 ist ungültig, nutze 1 als Minimum (config/general.yml).");
        }
        String autoDauer = holeText("verstoesse", "auto-bann-dauer", "7d");
        try {
            var geparst = new TimeParser().parsen(autoDauer);
            if (!(geparst instanceof ParseResult.Geltzeit)
                    && !(geparst instanceof ParseResult.Permanent)) {
                LOG.warn("verstoesse.auto-bann-dauer '{}' ist ungültig, nutze 7d-Fallback (config/general.yml).", autoDauer);
            }
        } catch (Exception e) {
            LOG.warn("verstoesse.auto-bann-dauer '{}' ist ungültig, nutze 7d-Fallback (config/general.yml).", autoDauer, e);
        }
        if (VerstossStufe.parse(holeText("verstoesse", "default-stufe", "medium")).isEmpty()) {
            LOG.warn("verstoesse.default-stufe '{}' ist ungültig (erlaubt: low, medium, high), nutze medium (config/general.yml).",
                    holeText("verstoesse", "default-stufe", "?"));
        }
        for (VerstossStufe stufe : VerstossStufe.values()) {
            Map<String, Object> stufen = Karten.abschnitt(abschnitt("verstoesse"), "stufen");
            Map<String, Object> eintrag = stufen == null
                    ? null : Karten.abschnitt(stufen, stufe.schluessel());
            String dauer = eintrag == null
                    ? stufe.standardDauerText() : Karten.text(eintrag, "dauer", stufe.standardDauerText());
            try {
                var geparst = new TimeParser().parsen(
                        dauer == null ? "" : dauer.trim());
                if (!(geparst instanceof ParseResult.Geltzeit)) {
                    LOG.warn("verstoesse.stufen.{}.dauer '{}' ist ungültig (erlaubt: Dauer wie 30d, kein perm), "
                            + "nutze {} (config/general.yml).", stufe.schluessel(), dauer, stufe.standardDauerText());
                }
            } catch (Exception e) {
                LOG.warn("verstoesse.stufen.{}.dauer '{}' ist ungültig, nutze {} (config/general.yml).",
                        stufe.schluessel(), dauer, stufe.standardDauerText(), e);
            }
        }
        try {
            Map<String, Object> eskalation = warnEskalationAbschnitt();
            if (eskalation != null && eskalation.containsKey("schwellen")) {
                List<?> roh = Karten.liste(eskalation, "schwellen");
                Map<Integer, VerstossStufe> normiert = Konfiguration.normiereWarnSchwellen(roh);
                if (!roh.isEmpty() && normiert.isEmpty()) {
                    LOG.warn("warns.eskalation.schwellen enthält keine gültige Schwelle "
                            + "(erwartet Liste wie '- { ab: 3, stufe: low }'), Eskalation löst nie aus (config/general.yml).");
                } else if (normiert.size() < roh.size()) {
                    LOG.warn("warns.eskalation.schwellen: {} von {} Einträgen ungültig (übersprungen) (config/general.yml).",
                            roh.size() - normiert.size(), roh.size());
                }
            }
        } catch (Exception e) {
            LOG.warn("warns.eskalation konnte nicht validiert werden (config/general.yml).", e);
        }
        try {
            anzeigeZone();
        } catch (Exception e) {
            LOG.warn("anzeige-zone ist ungültig, nutze System-Default (config/general.yml, Key anzeige-zone).", e);
        }
        int seite = holeInt("history", "eintraege-pro-seite", 10);
        if (seite < 1 || seite > 100) {
            LOG.warn("history.eintraege-pro-seite außerhalb 1-100, wird auf 1-100 geclampt (config/general.yml).");
        }
        validiereLimits();
        validiereReports();
    }

    private void validiereLimits() {
        validiereLimit("timeout-max-minuten", timeoutMaxMinuten(), 1440L);
        validiereLimit("mute-max-minuten", muteMaxMinuten(), 43200L);
        validiereLimit("ban-max-minuten", banMaxMinuten(), 525600L);
        Object freezeRoh = holeRoh("limits", "freeze-max-minuten");
        if (freezeRoh != null) {
            try {
                long freezeWert = Karten.zahl(abschnitt("limits"), "freeze-max-minuten", 43200L);
                if (freezeWert <= 0) {
                    LOG.warn("limits.freeze-max-minuten ({}): ungültig, es gilt mute-max-minuten "
                            + "als Fallback (config/general.yml).", freezeWert);
                }
            } catch (Exception e) {
                LOG.debug("Freeze-Limit-Validierung übersprungen.", e);
            }
        }
        Object banMinRoh = holeRoh("limits", "ban-min-minuten");
        if (banMinRoh != null) {
            try {
                long banMinWert = Karten.zahl(abschnitt("limits"), "ban-min-minuten", 1440L);
                if (banMinWert <= 0) {
                    LOG.warn("limits.ban-min-minuten ({}): ungültig, es gilt timeout-max-minuten "
                            + "als Ban-Min (config/general.yml).", banMinWert);
                }
            } catch (Exception e) {
                LOG.debug("Ban-Min-Validierung übersprungen.", e);
            }
        }
        if (ipGeteiltWarnAb() > ipGeteiltBlockAb()) {
            LOG.warn("limits.ip-geteilt-warn-ab ({}) liegt über limits.ip-geteilt-block-ab ({}) (config/general.yml).",
                    ipGeteiltWarnAb(), ipGeteiltBlockAb());
        }
    }

    private void validiereLimit(String schluessel, long effektiv, long standard) {
        Object roh = holeRoh("limits", schluessel);
        if (roh != null && effektiv == standard) {
            try {
                long gewuenscht = Karten.zahl(abschnitt("limits"), schluessel, standard);
                if (gewuenscht != standard) {
                    LOG.warn("limits.{} ({}) ungültig, nutze {} (config/general.yml).",
                            schluessel, gewuenscht, standard);
                }
            } catch (Exception e) {
                LOG.debug("Limit-Validierung für {} übersprungen.", schluessel, e);
            }
        }
    }

    private void validiereReports() {
        long cooldown = holeLang("reports", "cooldown-sekunden", 60L);
        if (cooldown < 0) {
            LOG.warn("reports.cooldown-sekunden < 0, nutze 0 (config/general.yml).");
        }
        int maxOffen = holeInt("reports", "max-offen-pro-reporter", 3);
        if (maxOffen < 1) {
            LOG.warn("reports.max-offen-pro-reporter < 1, nutze 1 (config/general.yml).");
        }
        long duplikat = holeLang("reports", "duplikat-sperre-minuten", 10L);
        if (duplikat < 0) {
            LOG.warn("reports.duplikat-sperre-minuten < 0, nutze 0 (config/general.yml).");
        }
    }

    private Map<String, Object> abschnitt(String name) {
        Map<String, Object> abschnitt = Karten.abschnitt(konfiguration, name);
        return abschnitt == null ? Map.of() : abschnitt;
    }

    private String holeText(String abschnitt, String schluessel, String standard) {
        return Karten.text(abschnitt(abschnitt), schluessel, standard);
    }

    private int holeInt(String abschnitt, String schluessel, int standard) {
        long wert = Karten.zahl(abschnitt(abschnitt), schluessel, standard);
        if (wert > Integer.MAX_VALUE || wert < Integer.MIN_VALUE) {
            LOG.warn("{}.{} ist außerhalb des int-Bereichs ({}), nutze {}.",
                    abschnitt, schluessel, wert, standard);
            return standard;
        }
        return (int) wert;
    }

    private long holeLang(String abschnitt, String schluessel, long standard) {
        return Karten.zahl(abschnitt(abschnitt), schluessel, standard);
    }

    private List<String> holeListe(String abschnitt, String schluessel) {
        List<String> ergebnis = new ArrayList<>();
        for (Object roh : Karten.liste(abschnitt(abschnitt), schluessel)) {
            if (roh != null && !String.valueOf(roh).isBlank()) {
                ergebnis.add(String.valueOf(roh));
            }
        }
        return ergebnis;
    }

    private Object holeRoh(String abschnitt, String schluessel) {
        return abschnitt(abschnitt).get(schluessel);
    }

    public boolean chatfilterAktiv() {
        return Karten.wahr(abschnitt("chatfilter"), "aktiv", false);
    }

    public boolean chatfilterTeamInfo() {
        return Karten.wahr(abschnitt("chatfilter"), "team-info", false);
    }

    public boolean chatfilterKlartextUbertragen() {
        java.util.Map<String, Object> abschnitt = abschnitt("chatfilter");
        if (!abschnitt.containsKey("klartext-uebertragen")) {
            return true;
        }
        return Karten.wahr(abschnitt, "klartext-uebertragen", true);
    }

    public boolean chatfilterModellSync() {
        Object roh = abschnitt("chatfilter").get("modell-sync");
        if (roh == null) {
            return true;
        }
        return Karten.wahr(abschnitt("chatfilter"), "modell-sync", true);
    }

    public boolean chatfilterAutoDownload() {
        Object roh = abschnitt("chatfilter").get("auto-download");
        if (roh == null) {
            return true;
        }
        return Karten.wahr(abschnitt("chatfilter"), "auto-download", true);
    }

    public String chatfilterModellUrl() {
        return Karten.text(abschnitt("chatfilter"), "modell-url", "");
    }

    public String updateHangarAutor() {
        return Karten.text(abschnitt("update"), "hangar-author", "").trim();
    }

    public String updateHangarSlug() {
        return Karten.text(abschnitt("update"), "hangar-slug", "").trim();
    }

    public String updateChannel() {
        String kanal = Karten.text(abschnitt("update"), "channel", "Release");
        return kanal == null || kanal.isBlank() ? "Release" : kanal.trim();
    }

    public boolean updateAutoCheckBeimStart() {
        Object roh = abschnitt("update").get("auto-check-beim-start");
        if (roh == null) {
            return true;
        }
        return Karten.wahr(abschnitt("update"), "auto-check-beim-start", true);
    }

    public long updateIntervallMinuten() {
        long minuten = holeLang("update", "intervall-minuten", 60L);
        return Math.min(Math.max(minuten, 0L), 60L * 24L * 7L);
    }

    @Override
    public int ipAufbewahrungTage() {
        long tage = Karten.zahl(abschnitt("datenschutz"), "ip-aufbewahrung-tage", 90L);
        if (tage < 1 || tage > 3650) {
            LOG.warn("datenschutz.ip-aufbewahrung-tage außerhalb 1-3650, nutze 90.");
            return 90;
        }
        return (int) tage;
    }

    public boolean updateBestaetigungFuerApply() {
        Object roh = abschnitt("update").get("bestaetigung-fuer-apply");
        if (roh == null) {
            return true;
        }
        return Karten.wahr(abschnitt("update"), "bestaetigung-fuer-apply", true);
    }

    public long chatfilterFensterMillis() {
        long minuten = holeLang("chatfilter", "fenster-minuten", 60L);
        if (minuten < 1L) {
            return 60L * 60L * 1000L;
        }
        return Math.min(minuten, 60L * 24L * 30L) * 60L * 1000L;
    }

    public List<ChatfilterStaffelService.Stufe> chatfilterStaffel() {
        List<ChatfilterStaffelService.Stufe> ergebnis = new ArrayList<>();
        for (Object roh : Karten.liste(abschnitt("chatfilter"), "staffel")) {
            Map<String, Object> eintrag = Karten.abschnitt(roh);
            if (eintrag == null || eintrag.isEmpty()) {
                continue;
            }
            int anzahl = (int) Karten.zahl(eintrag, "anzahl", 0L);
            ChatfilterStaffelService.Aktion aktion =
                    ChatfilterStaffelService.parseAktion(Karten.text(eintrag, "aktion", null));
            if (anzahl < 1 || aktion == null) {
                LOG.warn("chatfilter.staffel-Eintrag ungültig (anzahl>=1, aktion warn/mute), übersprungen.");
                continue;
            }
            long dauer = 0L;
            if (aktion == ChatfilterStaffelService.Aktion.MUTE) {
                dauer = ChatfilterStaffelService.parseDauerMillis(
                        Karten.text(eintrag, "dauer", "1h"), 0L);
                if (dauer <= 0L) {
                    LOG.warn("chatfilter.staffel mute ohne gültige dauer, übersprungen.");
                    continue;
                }
                long max = muteMaxMinuten() * 60L * 1000L;
                if (dauer > max) {
                    LOG.warn("chatfilter.staffel mute-dauer über mute-max, gedeckelt.");
                    dauer = max;
                }
            }
            String grund = Karten.text(eintrag, "grund", null);
            try {
                ergebnis.add(new ChatfilterStaffelService.Stufe(anzahl, aktion, dauer, grund));
            } catch (IllegalArgumentException e) {
                LOG.warn("chatfilter.staffel-Eintrag ungültig, übersprungen.", e);
            }
        }
        ergebnis.sort((a, b) -> Integer.compare(a.anzahl(), b.anzahl()));
        if (ergebnis.isEmpty()) {
            LOG.debug("chatfilter.staffel leer/ungültig, nutze Standard (5 Treffer -> Warn).");
            return List.of(new ChatfilterStaffelService.Stufe(5,
                    ChatfilterStaffelService.Aktion.WARN, 0L, null));
        }
        return List.copyOf(ergebnis);
    }

    @Override
    public String praefix() {
        String wert = Karten.text(konfiguration, "prefix", null);
        if (wert == null) {
            LOG.warn("prefix fehlt in config/general.yml, nutze Standard-Prefix.");
            return STANDARD_PREFIX;
        }
        return wert;
    }

    @Override
    public String nachricht(String schluessel) {
        Map<String, Object> messages = abschnitt("messages");
        if (!messages.containsKey(schluessel)) {
            String eingebaut = eingebauteNachricht(schluessel);
            if (eingebaut != null) {
                LOG.debug("messages-Key '{}' fehlt in config/messages.yml, nutze eingebauten Standard.", schluessel);
                return eingebaut;
            }
            LOG.warn("Fehlender messages-Key '{}' in config/messages.yml, nutze Fallback-Text.", schluessel);
            return STANDARD_NACHRICHT;
        }
        Object roh = messages.get(schluessel);
        if (roh == null) {
            String eingebaut = eingebauteNachricht(schluessel);
            return eingebaut != null ? eingebaut : STANDARD_NACHRICHT;
        }
        String wert = String.valueOf(roh);
        if (wert.isBlank()) {
            String eingebaut = eingebauteNachricht(schluessel);
            return eingebaut != null ? eingebaut : STANDARD_NACHRICHT;
        }
        return wert;
    }

    static String eingebauteNachricht(String schluessel) {
        if (schluessel == null) {
            return null;
        }
        String ausJar = nachrichtAusJarDefault(schluessel);
        if (ausJar != null && !ausJar.isBlank()) {
            return ausJar;
        }
        return switch (schluessel) {
            case "scope-unbekannt-warnung" ->
                    "<yellow>Warnung: <white>%scope% <yellow>ist kein bekannter Server – die Strafe wirkt dort ggf. nirgends (Tippfehler?). Bekannt: <white>%bekannt%";
            case "scope-abdeckend-hinweis" ->
                    "<yellow>Hinweis: <white>%spieler% <yellow>ist in einem anderen Scope weiter aktiv (<white>%scope%<yellow>). Nutze den passenden Scope (z. B. --global), siehe /punish-status bzw. /punish-history.";
            case "limit-unterschritten" ->
                    "<red>Die Dauer unterschreitet das Minimum von <white>%min% Minuten<red>. Nutze für kurze Strafen einen Timeout.";
            case "silent-ohne-recht" ->
                    "<yellow>Silent erfordert <white>punish.allow-silent-syntax<yellow> – die Strafe wurde laut ausgeführt.";
            case "alts-syntax" ->
                    "<red>Verwendung: /alts <Spieler|IP> [--global|--server|--unbekannt]";
            case "alts-kopf" ->
                    "<yellow>Alt-Accounts für <white>%spieler% <yellow>(IP <white>%ip%<yellow>, <white>%anzahl%<yellow>)%ipstatus% <gray>(Beta: IP-Heuristik, kein Beweis)";
            case "alts-eintrag" ->
                    "<gray>• <white>%spieler% <gray>| zuletzt: <white>%zuletzt% <gray>| Ban: %banstatus%";
            case "alts-ban-frei" -> "<green>kein Ban";
            case "alts-ban-aktiv" -> "<red>aktiv [%scope%] (noch %rest%)";
            case "alts-ip-frei" -> "";
            case "alts-ip-gebannt" -> " <red>[IP gebannt: %scope%]";
            case "alts-leer" ->
                    "<green>Keine Accounts mit der IP <white>%ip% <green>gefunden.";
            case "punish-version" ->
                    "<gray>PunishSystem <white>%version% <gray>by <white>lightrixx";
            case "punish-help-kopf" -> "<yellow>PunishSystem <gray>by <white>lightrixx <yellow>– Befehle:";
            case "punish-help-eintrag" -> "<gray>• <green>/%befehl% <gray>%nutzung%";
            case "punish-help-leer" -> "<gray>Keine Befehle für deine Rechte verfügbar.";
            case "punish-help-fuss" ->
                    "<gray>PunishSystem by <white>lightrixx <gray>• Tipp: <white>--global <gray>oder <white>--server <gray>anhängen, Details per TAB. <gray>Beta: <white>/chatfilter <gray>(KI), <white>/alts <gray>(Heuristik), <white>/punish-gui<gray>.";
            case "chatfilter-blockiert" ->
                    "<red>Deine Nachricht wurde blockiert (Chatfilter: %quelle%).";
            case "reload-teilweise" ->
                    "<yellow>Die Konfiguration wurde geladen, aber Teile schlugen fehl: <white>%detail% <yellow>Details in der Konsole und /punish-diagnostics.";
            default -> null;
        };
    }

    private static volatile Map<String, Object> jarDefaultCache;

    static String nachrichtAusJarDefault(String schluessel) {
        try {
            Map<String, Object> jar = jarDefaultCache;
            if (jar == null) {
                try (InputStream in = VelocityKonfiguration.class.getResourceAsStream("/config/messages.yml")) {
                    if (in == null) {
                        return null;
                    }
                    Object geladen = safeYaml().load(in);
                    if (!(geladen instanceof Map)) {
                        return null;
                    }
                    Map<String, Object> tmp = new java.util.LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : ((Map<?, ?>) geladen).entrySet()) {
                        if (e.getKey() != null) {
                            tmp.put(String.valueOf(e.getKey()), e.getValue());
                        }
                    }
                    jar = tmp;
                    jarDefaultCache = tmp;
                }
            }
            Object messages = jar.get("messages");
            if (!(messages instanceof Map)) {
                return null;
            }
            Object wert = ((Map<?, ?>) messages).get(schluessel);
            if (wert == null) {
                return null;
            }
            String text = String.valueOf(wert);
            return text.isBlank() ? null : text;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public int verstoesseMaxPunkte() {
        return Math.max(1, holeInt("verstoesse", "max-punkte", 6));
    }

    @Override
    public String verstoesseAutoBannDauer() {
        return holeText("verstoesse", "auto-bann-dauer", "7d");
    }

    @Override
    public VerstossStufe verstossStandardStufe() {
        String roh = holeText("verstoesse", "default-stufe", "medium");
        var parsed = VerstossStufe.parse(roh);
        if (parsed.isEmpty()) {
            LOG.warn("verstoesse.default-stufe '{}' ist ungültig, nutze medium.", roh);
            return VerstossStufe.standard();
        }
        return parsed.get();
    }

    private Map<String, Object> stufenAbschnitt() {
        Map<String, Object> verstoesse = abschnitt("verstoesse");
        Map<String, Object> stufen = Karten.abschnitt(verstoesse, "stufen");
        return stufen == null ? Map.of() : stufen;
    }

    @Override
    public long verstossStufenDauerMillis(VerstossStufe stufe) {
        VerstossStufe effektiv = stufe == null ? VerstossStufe.standard() : stufe;
        Map<String, Object> eintrag = Karten.abschnitt(stufenAbschnitt(), effektiv.schluessel());
        String roh = eintrag == null
                ? effektiv.standardDauerText() : Karten.text(eintrag, "dauer", effektiv.standardDauerText());
        try {
            var geparst = new TimeParser().parsen(roh == null ? "" : roh.trim());
            if (geparst instanceof ParseResult.Geltzeit(long millis) && millis > 0) {
                return millis;
            }
            LOG.warn("verstoesse.stufen.{}.dauer '{}' ist ungültig, nutze {}.",
                    effektiv.schluessel(), roh, effektiv.standardDauerText());
        } catch (Exception e) {
            LOG.warn("verstoesse.stufen.{}.dauer '{}' ist ungültig, nutze {}.",
                    effektiv.schluessel(), roh, effektiv.standardDauerText(), e);
        }
        return effektiv.standardDauerMillis();
    }

    @Override
    public int verstossStufenPunkte(VerstossStufe stufe) {
        VerstossStufe effektiv = stufe == null ? VerstossStufe.standard() : stufe;
        Map<String, Object> eintrag = Karten.abschnitt(stufenAbschnitt(), effektiv.schluessel());
        int wert = eintrag == null
                ? effektiv.standardPunkte() : (int) Karten.zahl(eintrag, "punkte", effektiv.standardPunkte());
        if (wert < 1) {
            LOG.warn("verstoesse.stufen.{}.punkte '{}' ist ungültig (min. 1), nutze {}.",
                    effektiv.schluessel(), wert, effektiv.standardPunkte());
            return effektiv.standardPunkte();
        }
        return wert;
    }

    private Map<String, Object> warnEskalationAbschnitt() {
        Map<String, Object> warns = abschnitt("warns");
        if (warns == null || warns.isEmpty()) {
            return null;
        }
        return Karten.abschnitt(warns, "eskalation");
    }

    @Override
    public boolean warnEskalationAktiv() {
        Map<String, Object> eskalation = warnEskalationAbschnitt();
        if (eskalation == null) {
            return true;
        }
        return Karten.wahr(eskalation, "aktiv", true);
    }

    @Override
    public Map<Integer, VerstossStufe> warnEskalationsSchwellen() {
        Map<String, Object> eskalation = warnEskalationAbschnitt();
        if (eskalation == null || !eskalation.containsKey("schwellen")) {
            return Konfiguration.standardWarnSchwellen();
        }
        return Konfiguration.normiereWarnSchwellen(Karten.liste(eskalation, "schwellen"));
    }

    @Override
    public LimitSnapshot limitSnapshot() {
        Map<String, Object> cfg = konfiguration;
        Map<String, Object> v = Karten.abschnitt(cfg, "verstoesse");
        int max = 6;
        String dauer = "7d";
        if (v != null) {
            max = Math.max(1, (int) Karten.zahl(v, "max-punkte", 6));
            dauer = Karten.text(v, "auto-bann-dauer", "7d");
        }
        return new LimitSnapshot(max, dauer);
    }

    @Override
    public long timeoutMaxMinuten() {
        long wert = holeLang("limits", "timeout-max-minuten", 1440L);
        if (wert <= 0) {
            return 1440L;
        }
        return Math.min(wert, Long.MAX_VALUE / 60000L);
    }

    @Override
    public long muteMaxMinuten() {
        long wert = holeLang("limits", "mute-max-minuten", 43200L);
        if (wert <= 0) {
            return 43200L;
        }
        return Math.min(wert, Long.MAX_VALUE / 60000L);
    }

    @Override
    public int ipGeteiltWarnAb() {
        return Math.max(1, holeInt("limits", "ip-geteilt-warn-ab", 4));
    }

    @Override
    public int ipGeteiltBlockAb() {
        return Math.max(2, holeInt("limits", "ip-geteilt-block-ab", 11));
    }

    @Override
    public int ipGeteiltBestaetigungAb() {
        int wert = holeInt("limits", "ip-geteilt-bestaetigung-ab", 2);
        if (wert <= 0) {
            return Integer.MAX_VALUE;
        }
        return Math.max(1, wert);
    }

    @Override
    public long freezeMaxMinuten() {
        Object roh = holeRoh("limits", "freeze-max-minuten");
        if (roh == null) {
            return muteMaxMinuten();
        }
        long wert = holeLang("limits", "freeze-max-minuten", 43200L);
        if (wert <= 0) {
            return muteMaxMinuten();
        }
        return Math.min(wert, Long.MAX_VALUE / 60000L);
    }

    @Override
    public long banMaxMinuten() {
        long wert = holeLang("limits", "ban-max-minuten", 525600L);
        if (wert <= 0) {
            return 525600L;
        }
        return Math.min(wert, Long.MAX_VALUE / 60000L);
    }

    @Override
    public long banMinMinuten() {
        long fallback = timeoutMaxMinuten();
        Object roh = holeRoh("limits", "ban-min-minuten");
        if (roh == null) {
            return Math.min(fallback, banMaxMinuten());
        }
        long wert = holeLang("limits", "ban-min-minuten", fallback);
        if (wert <= 0) {
            return Math.min(fallback, banMaxMinuten());
        }
        long gedeckelt = Math.min(wert, Long.MAX_VALUE / 60000L);
        long max = banMaxMinuten();
        if (gedeckelt > max) {
            LOG.warn("limits.ban-min-minuten ({}) > limits.ban-max-minuten ({}), deckele auf Max.",
                    gedeckelt, max);
            return max;
        }
        return Math.max(1L, gedeckelt);
    }

    @Override
    public boolean bestaetigungPardonAll() {
        return leseBestaetigung("pardon-all");
    }

    @Override
    public boolean bestaetigungKickAll() {
        return leseBestaetigung("kick-all");
    }

    @Override
    public boolean bestaetigungBanAll() {
        return leseBestaetigung("ban-all");
    }

    @Override
    public boolean bestaetigungMuteAll() {
        return leseBestaetigung("mute-all");
    }

    @Override
    public boolean bestaetigungTimeoutAll() {
        return leseBestaetigung("timeout-all");
    }

    @Override
    public boolean bestaetigungUnmuteAll() {
        return leseBestaetigung("unmute-all");
    }

    @Override
    public boolean bestaetigungUntimeoutAll() {
        return leseBestaetigung("untimeout-all");
    }

    private boolean leseBestaetigung(String schluessel) {
        Object roh = holeRoh("bestaetigungen", schluessel);
        if (roh instanceof Boolean bool) {
            return bool;
        }
        if (roh == null) {
            return true;
        }
        LOG.warn("bestaetigungen.{} ist kein Boolean (Wert: '{}'), erzwinge true (fail-closed). Erlaubt: true/false.",
                schluessel, roh);
        return true;
    }

    @Override
    public boolean schutzFailClosed() {
        Object roh = holeRoh("schutz", "fail-closed");
        if (roh instanceof Boolean bool) {
            return bool;
        }
        if (roh == null) {
            return true;
        }
        LOG.warn("schutz.fail-closed ist kein Boolean (Wert: '{}'), erzwinge true (fail-closed).", roh);
        return true;
    }

    @Override
    public boolean offlineAllowNeverSeen() {
        Object roh = holeRoh("offline-resolution", "allow-never-seen");
        if (roh instanceof Boolean bool) {
            return bool;
        }
        if (roh == null) {
            return false;
        }
        LOG.warn("offline-resolution.allow-never-seen ist kein Boolean (Wert: '{}'), nutze false.", roh);
        return false;
    }

    public SpeicherTyp speicherTyp() {
        Object roh = holeRoh("speicher", "typ");
        if (roh == null) {
            return SpeicherTyp.MYSQL;
        }
        if (!(roh instanceof String text)) {
            LOG.warn("speicher.typ ist kein Text (Wert: '{}'), nutze mysql.", roh);
            return SpeicherTyp.MYSQL;
        }
        SpeicherTyp typ;
        try {
            typ = SpeicherTyp.ausWertStrikt(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("speicher.typ ist ungültig: '" + text + "'", e);
        }
        if (typ != SpeicherTyp.MYSQL) {
            LOG.warn("speicher.typ=yaml ist deprecated (Single-Server only). Für Netzwerke MySQL nutzen.");
        }
        return typ;
    }

    public boolean speicherMigration() {
        Object roh = holeRoh("speicher", "migration-beim-start");
        if (roh instanceof Boolean bool) {
            return bool;
        }
        return roh == null;
    }

    public MysqlKonfiguration mysqlKonfiguration() {
        Map<String, Object> unter = Karten.abschnitt(abschnitt("speicher"), "mysql");
        if (unter == null) {
            unter = Map.of();
        }
        String host = Karten.text(unter, "host", "127.0.0.1");
        int port = (int) Karten.zahl(unter, "port", 3306L);
        String datenbank = Karten.text(unter, "datenbank", "punishsystem");
        String benutzer = Karten.text(unter, "benutzer", "punish");
        Object passwortRoh = unter.get("passwort");
        String passwort = passwortRoh == null ? "" : String.valueOf(passwortRoh);
        Object sslRoh = unter.get("use-ssl");
        boolean useSsl = sslRoh instanceof Boolean bool ? bool : false;
        int poolMax = (int) Karten.zahl(unter, "pool-max", 10L);
        try {
            return new MysqlKonfiguration(host, port, datenbank, benutzer, passwort, useSsl, poolMax);
        } catch (IllegalArgumentException e) {
            LOG.error("speicher.mysql ist ungültig: {}", e.getMessage());
            throw e;
        }
    }

    @Override
    public ZoneId anzeigeZone() {
        String roh = Karten.text(konfiguration, "anzeige-zone", "Europe/Berlin");
        if (roh == null || roh.isBlank()) {
            return ZoneId.of("Europe/Berlin");
        }
        try {
            return ZoneId.of(roh.trim());
        } catch (Exception e) {
            LOG.warn("Ungültige anzeige-zone '{}', nutze Europe/Berlin.", roh);
            return ZoneId.of("Europe/Berlin");
        }
    }

    @Override
    public int historyEintraegeProSeite() {
        return Math.min(100, Math.max(1, holeInt("history", "eintraege-pro-seite", 10)));
    }

    @Override
    public long reportCooldownSekunden() {
        long wert = holeLang("reports", "cooldown-sekunden", 60L);
        return Math.max(0L, Math.min(wert, 3600L));
    }

    @Override
    public int reportMaxOffenProReporter() {
        return Math.min(50, Math.max(1, holeInt("reports", "max-offen-pro-reporter", 3)));
    }

    @Override
    public long reportDuplikatSperreMinuten() {
        long wert = holeLang("reports", "duplikat-sperre-minuten", 10L);
        return Math.max(0L, Math.min(wert, 1440L));
    }

    @Override
    public List<String> tabDauerListe() {
        List<String> liste = holeListe("tab", "dauer-liste");
        return liste.isEmpty() ? List.of("10m", "1h", "6h", "1d", "7d", "30d", "perm") : liste;
    }

    @Override
    public List<String> tabGrundListe() {
        List<String> liste = holeListe("tab", "grund-liste");
        return liste.isEmpty()
                ? List.of("Beleidigung", "Spamming", "Cheating", "Teaming", "Bugusing", "Hacking")
                : liste;
    }

    @Override
    public List<String> grundListe(String befehl) {
        for (String kandidat : de.lightrixx.punish.core.Konfiguration.vorschlagBefehlKette(befehl)) {
            List<String> sauber = vorschlagListe(kandidat, "gruende");
            if (!sauber.isEmpty()) {
                return sauber;
            }
        }
        return tabGrundListe();
    }

    @Override
    public List<String> dauerListe(String befehl) {
        for (String kandidat : de.lightrixx.punish.core.Konfiguration.vorschlagBefehlKette(befehl)) {
            List<String> sauber = vorschlagListe(kandidat, "dauern");
            if (!sauber.isEmpty()) {
                return sauber;
            }
        }
        return tabDauerListe();
    }

    @Override
    public String empfohleneDauer(String befehl, String grund) {
        if (grund == null || grund.isBlank()) {
            return null;
        }
        String gesucht = grund.trim().toLowerCase(java.util.Locale.ROOT);
        for (String kandidat : de.lightrixx.punish.core.Konfiguration.vorschlagBefehlKette(befehl)) {
            for (Map<String, String> vorlage : vorlagenRoh()) {
                String vb = vorlage.getOrDefault("befehl", "");
                String vg = vorlage.getOrDefault("grund", "");
                String vd = vorlage.getOrDefault("dauer", "");
                if (!vd.isBlank() && vb.equalsIgnoreCase(kandidat) && vg.equalsIgnoreCase(gesucht)) {
                    return vd;
                }
            }
        }
        for (Map<String, String> vorlage : vorlagenRoh()) {
            String vb = vorlage.getOrDefault("befehl", "");
            String vg = vorlage.getOrDefault("grund", "");
            String vd = vorlage.getOrDefault("dauer", "");
            if (!vd.isBlank() && (vb.isBlank() || "*".equals(vb)) && vg.equalsIgnoreCase(gesucht)) {
                return vd;
            }
        }
        return null;
    }

    @Override
    public Map<String, String> vorlagenFuer(String befehl) {
        Map<String, String> ergebnis = new java.util.LinkedHashMap<>();
        for (String kandidat : de.lightrixx.punish.core.Konfiguration.vorschlagBefehlKette(befehl)) {
            for (Map<String, String> vorlage : vorlagenRoh()) {
                String vb = vorlage.getOrDefault("befehl", "");
                String vg = vorlage.getOrDefault("grund", "");
                String vd = vorlage.getOrDefault("dauer", "");
                if (!vd.isBlank() && vb.equalsIgnoreCase(kandidat) && !vg.isBlank()) {
                    ergebnis.putIfAbsent(vg.toLowerCase(java.util.Locale.ROOT), vd);
                }
            }
        }
        return Map.copyOf(ergebnis);
    }

    private List<String> vorschlagListe(String befehl, String feld) {
        Map<String, Object> vorschlaege = abschnitt("vorschlaege");
        Map<String, Object> eintrag = Karten.abschnitt(vorschlaege, befehl);
        if (eintrag == null) {
            return List.of();
        }
        List<String> ergebnis = new ArrayList<>();
        for (Object roh : Karten.liste(eintrag, feld)) {
            if (roh != null && !String.valueOf(roh).isBlank()) {
                ergebnis.add(String.valueOf(roh).trim());
            }
        }
        return List.copyOf(ergebnis);
    }

    private List<Map<String, String>> vorlagenRoh() {
        Map<String, Object> vorschlaege = abschnitt("vorschlaege");
        List<Map<String, String>> ergebnis = new ArrayList<>();
        for (Object roh : Karten.liste(vorschlaege, "vorlagen")) {
            Map<String, Object> eintrag = Karten.abschnitt(roh);
            if (eintrag == null) {
                continue;
            }
            Map<String, String> normiert = new java.util.HashMap<>();
            normiert.put("befehl", Karten.text(eintrag, "befehl", "").trim().toLowerCase(java.util.Locale.ROOT));
            normiert.put("grund", Karten.text(eintrag, "grund", "").trim().toLowerCase(java.util.Locale.ROOT));
            normiert.put("dauer", Karten.text(eintrag, "dauer", "").trim());
            ergebnis.add(normiert);
        }
        return ergebnis;
    }

    @Override
    public List<String> muteBlockierteBefehle() {
        Map<String, Object> mute = abschnitt("mute");
        if (!mute.containsKey("blockierte-befehle")) {
            return List.of(
                    "/msg", "/tell", "/w", "/whisper", "/t",
                    "/r", "/reply",
                    "/me", "/action",
                    "/tellraw", "/say", "/teammsg",
                    "/title",
                    "/pm", "/dm", "/message",
                    "/party", "/p",
                    "/guild", "/g",
                    "/plot",
                    "/channel", "/ch",
                    "/mail", "/nick", "/emsg", "/ereply", "/m");
        }
        return Karten.liste(mute, "blockierte-befehle").stream()
                .filter(e -> e != null && !String.valueOf(e).isBlank())
                .map(e -> String.valueOf(e).trim().toLowerCase(java.util.Locale.ROOT))
                .map(e -> e.startsWith("/") ? e : "/" + e)
                .distinct()
                .toList();
    }

    @Override
    public String standardGrund(String aktion) {
        Map<String, Object> defaults = abschnitt("defaults");
        if (!defaults.containsKey(aktion)) {
            LOG.warn("Fehlender defaults-Key '{}' in config/general.yml, nutze Fallback-Text.", aktion);
            return STANDARD_GRUND;
        }
        Object roh = defaults.get(aktion);
        return roh == null ? STANDARD_GRUND : String.valueOf(roh);
    }

    @Override
    public String screen(String typ) {
        Map<String, Object> screens = abschnitt("screens");
        Object roh = screens.get(typ);
        if (roh == null) {
            LOG.warn("screens.{} fehlt in config/screens.yml, nutze eingebauten Fallback-Text.", typ);
            return standardScreen(typ);
        }
        return String.valueOf(roh);
    }

    @Override
    public boolean broadcastAktiv(String typ) {
        Map<String, Object> broadcasts = abschnitt("broadcasts");
        Map<String, Object> eintrag = Karten.abschnitt(broadcasts, typ);
        if (eintrag == null) {
            return true;
        }
        return Karten.wahr(eintrag, "aktiv", true);
    }

    @Override
    public String broadcastNachricht(String typ) {
        Map<String, Object> broadcasts = abschnitt("broadcasts");
        Map<String, Object> eintrag = Karten.abschnitt(broadcasts, typ);
        if (eintrag == null) {
            LOG.warn("Kein Broadcast-Text für Typ '{}' (unbekannt), nutze Fallback-Text.", typ);
            return "<gray>[<yellow>Broadcast<gray>] <white>%spieler% <gray>%grund%";
        }
        String nachricht = Karten.text(eintrag, "nachricht", "");
        if (nachricht.isBlank()) {
            LOG.warn("Broadcast-Text für Typ '{}' ist leer, nutze Fallback-Text.", typ);
            return "<gray>[<yellow>Broadcast<gray>] <white>%spieler% <gray>%grund%";
        }
        return nachricht;
    }

    @Override
    public boolean verpassteBroadcastsAktiv() {
        Map<String, Object> verpasst = Karten.abschnitt(abschnitt("broadcasts"), "verpasst");
        if (verpasst == null) {
            return true;
        }
        return Karten.wahr(verpasst, "aktiv", true);
    }

    @Override
    public String verpassteKopf() {
        Map<String, Object> verpasst = Karten.abschnitt(abschnitt("broadcasts"), "verpasst");
        if (verpasst == null) {
            return Konfiguration.super.verpassteKopf();
        }
        String kopf = Karten.text(verpasst, "kopf", null);
        if (kopf == null || kopf.isBlank()) {
            LOG.warn("broadcasts.verpasst.kopf fehlt/leer in broadcasts.yml, nutze Standard-Kopf.");
            return Konfiguration.super.verpassteKopf();
        }
        return kopf;
    }

    @Override
    public String verpasstePrefix() {
        Map<String, Object> verpasst = Karten.abschnitt(abschnitt("broadcasts"), "verpasst");
        if (verpasst == null) {
            return Konfiguration.super.verpasstePrefix();
        }
        String prefix = Karten.text(verpasst, "prefix", null);
        if (prefix == null || prefix.isBlank()) {
            LOG.warn("broadcasts.verpasst.prefix fehlt/leer in broadcasts.yml, nutze Standard-Prefix.");
            return Konfiguration.super.verpasstePrefix();
        }
        return prefix;
    }

    private String standardScreen(String typ) {
        return switch (typ) {
            case "bann" ->
                    "<red>Du wurdest gebannt!<newline><newline><gray>Grund: <white>%grund%<newline><gray>Verbleibend: <white>%verbleibend%<newline><gray>Endet am: <white>%ablauf%<newline><gray>Von: <white>%ausfuehrer%";
            case "bann-permanent" ->
                    "<red>Du wurdest permanent gebannt!<newline><newline><gray>Grund: <white>%grund%<newline><gray>Von: <white>%ausfuehrer%";
            case "bann-auto" ->
                    "<red>Du wurdest automatisch gebannt (Verstoß-Limit erreicht).<newline><newline><gray>Grund: <white>%grund%<newline><gray>Verbleibend: <white>%verbleibend%<newline><gray>Endet am: <white>%ablauf%<newline><gray>Von: <white>%ausfuehrer%";
            case "ip-bann" ->
                    "<red>Deine IP-Adresse ist gebannt!<newline><newline><gray>Grund: <white>%grund%<newline><gray>Verbleibend: <white>%verbleibend%<newline><gray>Endet am: <white>%ablauf%<newline><gray>Von: <white>%ausfuehrer%";
            case "timeout" ->
                    "<yellow>Du befindest dich im Timeout.<newline><newline><gray>Grund: <white>%grund%<newline><gray>Verbleibend: <white>%verbleibend%<newline><gray>Endet am: <white>%ablauf%<newline><gray>Von: <white>%ausfuehrer%";
            case "ip-timeout" ->
                    "<yellow>Deine IP-Adresse befindet sich im Timeout.<newline><newline><gray>Grund: <white>%grund%<newline><gray>Verbleibend: <white>%verbleibend%<newline><gray>Endet am: <white>%ablauf%<newline><gray>Von: <white>%ausfuehrer%";
            case "kick" ->
                    "<red>Du wurdest vom Server gekickt!<newline><newline><gray>Grund: <white>%grund%<newline><gray>Von: <white>%ausfuehrer%";
            case "ip-kick" ->
                    "<red>Deine IP-Adresse wurde vom Server gekickt!<newline><newline><gray>Grund: <white>%grund%<newline><gray>Von: <white>%ausfuehrer%";
            default -> "<red>Zugriff verweigert.";
        };
    }

    @Override
    public String serverName() {
        Map<String, Object> netzwerk = abschnitt("netzwerk");
        String normiert = ServerScope.normalisiereServerName(Karten.text(netzwerk, "server-name", null));
        if (normiert != null) {
            return normiert;
        }
        return ServerScope.UNBEKANNT_WERT;
    }

    /**
     * Pro Bridge ein eigenes Secret. Der Schlüssel ist der normalisierte
     * Velocity-Servername, der Wert das nur dieser Bridge bekannte Secret.
     * Ein globales tunnel-secret wird auf dem Proxy nicht mehr verwendet.
     */
    public Map<String, String> brueckenSecrets() {
        try {
            Map<String, Object> netzwerk = abschnitt("netzwerk");
            Map<String, Object> roh = Karten.abschnitt(netzwerk, "bridge-secrets");
            Map<String, String> ergebnis = new LinkedHashMap<>();
            if (roh != null) {
                for (Map.Entry<String, Object> eintrag : roh.entrySet()) {
                    String name = ServerScope.normalisiereServerName(eintrag.getKey());
                    if (name == null) {
                        LOG.warn("netzwerk.bridge-secrets: ungültiger Servername '{}' ignoriert.",
                                eintrag.getKey());
                        continue;
                    }
                    String secret = eintrag.getValue() == null ? ""
                            : String.valueOf(eintrag.getValue()).trim();
                    if (secret.isEmpty()) {
                        continue;
                    }
                    ergebnis.put(name, secret);
                }
            }
            if (ergebnis.isEmpty()) {
                String alt = Karten.text(netzwerk, "tunnel-secret", null);
                if (alt != null && !alt.isBlank()) {
                    LOG.warn("netzwerk.tunnel-secret wird auf dem Proxy nicht mehr verwendet. "
                            + "Bitte netzwerk.bridge-secrets mit einem eigenen Secret je Bridge setzen.");
                }
            }
            return Map.copyOf(ergebnis);
        } catch (Exception e) {
            LOG.warn("netzwerk.bridge-secrets konnte nicht gelesen werden.", e);
            return Map.of();
        }
    }

    @Override
    public List<String> bekannteServer() {
        List<String> normiert = new ArrayList<>();
        for (Object roh : Karten.liste(abschnitt("netzwerk"), "server-liste")) {
            String name = ServerScope.normalisiereServerName(
                    roh == null ? null : String.valueOf(roh));
            if (name != null && !normiert.contains(name)) {
                normiert.add(name);
            }
        }

        try {
            for (var server : proxy.getAllServers()) {
                String name = ServerScope.normalisiereServerName(
                        server.getServerInfo().getName());
                if (name != null && !normiert.contains(name)) {
                    normiert.add(name);
                }
            }
        } catch (Exception e) {
            LOG.debug("Live-Serverliste nicht verfügbar, nutze Config-Fallback.", e);
        }
        String eigener = serverName();
        if (!eigener.equals(ServerScope.UNBEKANNT_WERT) && !normiert.contains(eigener)) {
            normiert.add(eigener);
        }
        normiert.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(normiert);
    }

    @Override
    public ServerScope standardScope() {
        String eigener = serverName();
        if (eigener.equals(ServerScope.UNBEKANNT_WERT)) {
            return ServerScope.unbekannt();
        }
        return ServerScope.server(eigener);
    }
}
