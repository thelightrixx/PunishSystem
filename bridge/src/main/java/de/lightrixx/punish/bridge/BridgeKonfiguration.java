package de.lightrixx.punish.bridge;

import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.RangBuendel;
import de.lightrixx.punish.model.ParseResult;
import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.model.VerstossStufe;
import de.lightrixx.punish.storage.mysql.MysqlKonfiguration;
import de.lightrixx.punish.storage.mysql.SpeicherTyp;
import de.lightrixx.punish.util.TimeParser;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class BridgeKonfiguration implements Konfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(BridgeKonfiguration.class);
    private static final String STANDARD_PREFIX = "<dark_gray>[<red>PunishSystem<dark_gray>]";
    private static final String STANDARD_NACHRICHT = "<red>Eine Nachricht konnte nicht geladen werden.";
    private static final String STANDARD_GRUND = "Regelverstoß";

    private static final String R_GENERAL = "config/general.yml";
    private static final String R_MESSAGES = "config/messages.yml";
    private static final String R_SCREENS = "config/screens.yml";
    private static final String R_BROADCASTS = "config/broadcasts.yml";
    private static final String R_GUI = "config/gui.yml";

    private static final List<String> GENERAL_KEYS = List.of(
            "prefix", "netzwerk", "anzeige-zone", "schutz", "offline-resolution",
            "speicher", "verstoesse", "limits", "bestaetigungen", "history", "mute", "tab",
            "vorschlaege", "defaults", "reports", "rang-buendel", "rang-gewicht",
            "config-version");

    private final JavaPlugin plugin;
    private final de.lightrixx.punish.storage.YamlUtil yamlUtil;
    private final File configOrdner;
    private final File generalDatei;
    private final File messagesDatei;
    private final File screensDatei;
    private final File broadcastsDatei;
    private final File guiDatei;
    private final File legacyDatei;
    private volatile FileConfiguration konfiguration = new YamlConfiguration();

    public File syncOrdner() {
        try {
            return new File(plugin.getDataFolder(), TextEmpfaenger.SYNC_ORDNER);
        } catch (Exception e) {
            return new File("plugins/PunishSystemBridge/config-sync");
        }
    }

    public BridgeKonfiguration(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "Das Plugin darf nicht null sein.");
        this.yamlUtil = new de.lightrixx.punish.storage.YamlUtil();
        this.configOrdner = new File(plugin.getDataFolder(), "config");
        this.generalDatei = new File(configOrdner, "general.yml");
        this.messagesDatei = new File(configOrdner, "messages.yml");
        this.screensDatei = new File(configOrdner, "screens.yml");
        this.broadcastsDatei = new File(configOrdner, "broadcasts.yml");
        this.guiDatei = new File(configOrdner, "gui.yml");
        this.legacyDatei = new File(plugin.getDataFolder(), "config.yml");
    }

    public void sicherstellen() {
        if (!configOrdner.exists() && !configOrdner.mkdirs() && !configOrdner.exists()) {
            LOG.warn("Der Config-Ordner {} konnte nicht angelegt werden.", configOrdner.getAbsolutePath());
        }
        sichereRessource(R_GENERAL, generalDatei);
        sichereRessource(R_MESSAGES, messagesDatei);
        sichereRessource(R_SCREENS, screensDatei);
        sichereRessource(R_BROADCASTS, broadcastsDatei);
        sichereRessource(R_GUI, guiDatei);
    }

    @SuppressWarnings("null")
    private void sichereRessource(String resourcePfad, File ziel) {
        if (ziel.exists()) {
            return;
        }
        try {
            File eltern = ziel.getParentFile();
            if (eltern != null && !eltern.exists()) {
                eltern.mkdirs();
            }
            plugin.saveResource(Objects.requireNonNull(resourcePfad, "resourcePfad"), false);
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
        FileConfiguration zusammen = new YamlConfiguration();
        merge(zusammen, yamlUtil.laden(generalDatei));
        merge(zusammen, yamlUtil.laden(messagesDatei));
        merge(zusammen, yamlUtil.laden(screensDatei));
        merge(zusammen, yamlUtil.laden(broadcastsDatei));
        merge(zusammen, yamlUtil.laden(guiDatei));
        mergeFallbackAusJar(zusammen);
        mergeProxyCacheFallsVorhanden(zusammen);
        this.konfiguration = zusammen;
        try {
            ladeRangBuendel(zusammen);
        } catch (Exception e) {
            LOG.warn("Rang-Bündel konnten nicht geladen werden (nur direkte Rechte aktiv).", e);
        }

        String name = serverName();
        if (name.equals(ServerScope.UNBEKANNT_WERT)) {
            LOG.warn("netzwerk.server-name ist nicht gesetzt (config/general.yml)! Mute-/Scope-Filter laufen "
                    + "fail-closed (alles wirksam). Für korrekte Scopes unbedingt setzen.");
        } else {
            LOG.info("Bridge-Servername: {}", name);
        }
        if (verstoesseMaxPunkte() < 1) {
            LOG.warn("verstoesse.max-punkte < 1 ist ungültig, nutze 1 als Minimum (config/general.yml).");
        }
        if (verstossStandardStufe() == null) {
            LOG.warn("verstoesse.default-stufe ist ungültig, nutze medium (config/general.yml).");
        }
        for (VerstossStufe stufe : VerstossStufe.values()) {
            String dauer = konfiguration.getString(
                    "verstoesse.stufen." + stufe.schluessel() + ".dauer", stufe.standardDauerText());
            try {
                var geparst = new TimeParser().parsen(
                        dauer == null ? "" : dauer.trim());
                if (!(geparst instanceof de.lightrixx.punish.model.ParseResult.Geltzeit)) {
                    LOG.warn("verstoesse.stufen.{}.dauer '{}' ist ungültig (erlaubt: Dauer wie 30d, kein perm), "
                            + "nutze {} (config/general.yml).", stufe.schluessel(), dauer, stufe.standardDauerText());
                }
            } catch (Exception e) {
                LOG.warn("verstoesse.stufen.{}.dauer '{}' ist ungültig, nutze {} (config/general.yml).",
                        stufe.schluessel(), dauer, stufe.standardDauerText(), e);
            }
        }
        LOG.info("Die Konfiguration wurde geladen (config/general.yml, messages.yml, screens.yml, broadcasts.yml, gui.yml).");
    }

    private static void ladeRangBuendel(FileConfiguration zusammen) {
        var abschnitt = zusammen.getConfigurationSection("rang-buendel");
        if (abschnitt == null) {
            RangBuendel.laden(Map.of());
        } else {
            Map<String, Collection<String>> roh = new LinkedHashMap<>();
            for (String gruppe : abschnitt.getKeys(false)) {
                if (gruppe != null) {
                    roh.put(gruppe, abschnitt.getStringList(gruppe));
                }
            }
            RangBuendel.laden(roh);
        }
        try {
            var gewichtAbschnitt = zusammen.getConfigurationSection("rang-gewicht");
            if (gewichtAbschnitt == null) {
                RangBuendel.ladenGewichte(Map.of());
            } else {
                Map<String, Integer> gewichte = new LinkedHashMap<>();
                for (String gruppe : gewichtAbschnitt.getKeys(false)) {
                    try {
                        gewichte.put(gruppe, gewichtAbschnitt.getInt(gruppe, 0));
                    } catch (Exception e) {
                        continue;
                    }
                }
                RangBuendel.ladenGewichte(gewichte);
            }
        } catch (Exception e) {
            LOG.debug("Rang-Gewichte konnten nicht geladen werden.", e);
        }
        try {
            String config = zusammen.getString("config-version", "");
            if (config != null && !config.isBlank()
                    && Konfiguration.istDowngrade(config.trim())) {
                LOG.warn("Downgrade-Warnung: config-version {} ist neuer als diese Jar ({}). Kein Abbruch.",
                        config.trim(), Konfiguration.AKTUELLE_CONFIG_VERSION);
            }
        } catch (Exception e) {
            LOG.debug("Config-Versionscheck übersprungen.", e);
        }
    }

    private static void merge(FileConfiguration ziel, FileConfiguration quelle) {
        if (quelle == null) {
            return;
        }
        try {
            for (String key : quelle.getKeys(false)) {
                ziel.set(key, quelle.get(key));
            }
        } catch (Exception e) {
            LOG.warn("Config-Teil konnte nicht zusammengeführt werden.", e);
        }
    }

    private void mergeProxyCacheFallsVorhanden(FileConfiguration zusammen) {
        Object lokalerServerName = zusammen.get("netzwerk.server-name");
        Object lokalesTunnelSecret = zusammen.get("netzwerk.tunnel-secret");
        File syncOrdner = new File(plugin.getDataFolder(), TextEmpfaenger.SYNC_ORDNER);
        String[] dateien = {"general.yml", "messages.yml", "screens.yml", "broadcasts.yml", "gui.yml"};
        boolean angewendet = false;
        for (String name : dateien) {
            try {
                File cache = new File(syncOrdner, name);
                if (!cache.isFile()) {
                    continue;
                }
                FileConfiguration proxy = yamlUtil.laden(cache);
                if (proxy == null) {
                    continue;
                }
                for (String key : proxy.getKeys(true)) {
                    try {
                        if (proxy.isConfigurationSection(key)) {
                            continue;
                        }
                        zusammen.set(key, proxy.get(key));
                    } catch (Exception e) {
                        LOG.debug("Proxy-Text-Key {} konnte nicht übernommen werden.", key, e);
                    }
                }
                angewendet = true;
            } catch (Exception e) {
                LOG.debug("Proxy-Cache {} konnte nicht gelesen werden.", name, e);
            }
        }
        if (angewendet) {
            if (lokalerServerName != null) {
                zusammen.set("netzwerk.server-name", lokalerServerName);
            }
            if (lokalesTunnelSecret != null) {
                zusammen.set("netzwerk.tunnel-secret", lokalesTunnelSecret);
            }
            LOG.info("Proxy-Texte angewendet (config-sync, server-name und tunnel-secret bleiben lokal).");
        }
    }

    private void mergeFallbackAusJar(FileConfiguration zusammen) {
        ladeDefaultInFallsFehlend(zusammen, R_GENERAL);
        ladeDefaultInFallsFehlend(zusammen, R_MESSAGES);
        ladeDefaultInFallsFehlend(zusammen, R_SCREENS);
        ladeDefaultInFallsFehlend(zusammen, R_BROADCASTS);
        ladeDefaultInFallsFehlend(zusammen, R_GUI);
    }

    @SuppressWarnings("null")
    private void ladeDefaultInFallsFehlend(FileConfiguration zusammen, String resourcePfad) {
        java.io.InputStream in = null;
        try {
            in = plugin.getResource(Objects.requireNonNull(resourcePfad, "resourcePfad"));
        } catch (Exception e) {
            LOG.debug("Jar-Default {} nicht lesbar.", resourcePfad, e);
            return;
        }
        if (in == null) {
            return;
        }
        try (java.io.InputStreamReader reader = new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)) {
            FileConfiguration def = YamlConfiguration.loadConfiguration(reader);
            for (String key : def.getKeys(true)) {
                try {
                    if (def.isConfigurationSection(key)) {
                        continue;
                    }
                    if (!zusammen.contains(key)) {
                        zusammen.set(key, def.get(key));
                    }
                } catch (Exception e) {
                    LOG.debug("Jar-Default-Key {} konnte nicht gemerged werden.", key, e);
                }
            }
        } catch (Exception e) {
            LOG.debug("Jar-Default {} konnte nicht als Fallback geladen werden.", resourcePfad, e);
        }
    }

    void migriereLegacyFallsNoetig() {
        if (!legacyDatei.isFile()) {
            return;
        }
        FileConfiguration alt;
        try {
            alt = yamlUtil.laden(legacyDatei);
        } catch (Exception e) {
            LOG.warn("Alte config.yml konnte nicht gelesen werden, Migration übersprungen.", e);
            return;
        }
        if (alt.getKeys(false).isEmpty()) {
            sichereLegacyAlsBackup();
            return;
        }
        boolean alleNeuExistieren = generalDatei.isFile() && generalDatei.length() > 0
                && messagesDatei.isFile() && messagesDatei.length() > 0
                && screensDatei.isFile() && screensDatei.length() > 0
                && broadcastsDatei.isFile() && broadcastsDatei.length() > 0
                && guiDatei.isFile() && guiDatei.length() > 0;
        if (alleNeuExistieren) {
            LOG.info("Alte config.yml gefunden, aber config/*.yml existieren bereits – "
                    + "Legacy wird nur als Backup gesichert, neue Dateien bleiben unberührt.");
            sichereLegacyAlsBackup();
            return;
        }
        LOG.info("Migriere alte config.yml nach config/general.yml, messages.yml, screens.yml, broadcasts.yml, gui.yml ...");
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
        schreibeTeilDatei(guiDatei, alt, List.of("gui"));
        sichereLegacyAlsBackup();
        LOG.info("Migration abgeschlossen – alte config.yml als Backup gesichert.");
    }

    private void schreibeTeilDatei(File ziel, FileConfiguration quelle, List<String> topKeys) {
        if (ziel.isFile() && ziel.length() > 0) {
            try {
                FileConfiguration bestehend = yamlUtil.laden(ziel);
                boolean geaendert = false;
                for (String key : topKeys) {
                    if (!bestehend.contains(key) && quelle.contains(key)) {
                        bestehend.set(key, quelle.get(key));
                        geaendert = true;
                    }
                }
                if (geaendert) {
                    yamlUtil.speichern(bestehend, ziel);
                }
                return;
            } catch (Exception e) {
                LOG.warn("Teil-Datei {} konnte nicht ergänzt werden.", ziel.getName(), e);
                return;
            }
        }
        try {
            YamlConfiguration neu = new YamlConfiguration();
            for (String key : topKeys) {
                if (quelle.contains(key)) {
                    neu.set(key, quelle.get(key));
                }
            }
            if (!neu.getKeys(false).isEmpty()) {
                File eltern = ziel.getParentFile();
                if (eltern != null && !eltern.exists()) {
                    eltern.mkdirs();
                }
                yamlUtil.speichern(neu, ziel);
            }
        } catch (Exception e) {
            LOG.warn("Teil-Datei {} konnte nicht aus Legacy erzeugt werden.", ziel.getName(), e);
        }
    }

    private void sichereLegacyAlsBackup() {
        try {
            File backup = new File(plugin.getDataFolder(), "config.yml.vor-config-split.bak");
            if (backup.exists()) {
                backup = new File(plugin.getDataFolder(),
                        "config.yml.vor-config-split-" + System.currentTimeMillis() + ".bak");
            }
            java.nio.file.Files.move(legacyDatei.toPath(), backup.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            LOG.info("Alte config.yml nach {} gesichert.", backup.getName());
        } catch (Exception e) {
            LOG.warn("Alte config.yml konnte nicht als Backup gesichert werden.", e);
        }
    }

    @Override
    public String praefix() {
        return konfiguration.getString("prefix", STANDARD_PREFIX);
    }

    @Override
    public String nachricht(String schluessel) {
        if (!konfiguration.contains("messages." + schluessel)) {
            String eingebaut = eingebauteNachricht(schluessel);
            if (eingebaut != null) {
                LOG.debug("messages-Key '{}' fehlt in config/messages.yml, nutze eingebauten Standard.", schluessel);
                return eingebaut;
            }
            LOG.warn("Fehlender messages-Key '{}' in config/messages.yml, nutze Fallback-Text.", schluessel);
            return STANDARD_NACHRICHT;
        }
        String wert = konfiguration.getString("messages." + schluessel, STANDARD_NACHRICHT);
        if (wert == null || wert.isBlank()) {
            String eingebaut = eingebauteNachricht(schluessel);
            return eingebaut != null ? eingebaut : STANDARD_NACHRICHT;
        }
        return wert;
    }

    static String eingebauteNachricht(String schluessel) {
        if (schluessel == null) {
            return null;
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
                    "<red>Deine Nachricht wurde blockiert (Chatfilter: %quelle%, Kanal: %kanal%).";
            case "mute-sync-laeuft" ->
                    "<yellow>Strafen werden geladen, bitte kurz warten.";
            case "reload-teilweise" ->
                    "<yellow>Die Konfiguration wurde geladen, aber Teile schlugen fehl: <white>%detail% <yellow>Details in der Konsole und /punish-diagnostics.";
            default -> null;
        };
    }

    @Override
    public int verstoesseMaxPunkte() {
        return Math.max(1, konfiguration.getInt("verstoesse.max-punkte", 6));
    }

    @Override
    public String verstoesseAutoBannDauer() {
        return konfiguration.getString("verstoesse.auto-bann-dauer", "7d");
    }

    @Override
    public VerstossStufe verstossStandardStufe() {
        String roh = konfiguration.getString("verstoesse.default-stufe", "medium");
        var parsed = VerstossStufe.parse(roh);
        if (parsed.isEmpty()) {
            LOG.warn("verstoesse.default-stufe '{}' ist ungültig, nutze medium.", roh);
            return VerstossStufe.standard();
        }
        return parsed.get();
    }

    @Override
    public long verstossStufenDauerMillis(VerstossStufe stufe) {
        VerstossStufe effektiv = stufe == null ? VerstossStufe.standard() : stufe;
        String roh = konfiguration.getString(
                "verstoesse.stufen." + effektiv.schluessel() + ".dauer", effektiv.standardDauerText());
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
        int wert = konfiguration.getInt(
                "verstoesse.stufen." + effektiv.schluessel() + ".punkte", effektiv.standardPunkte());
        if (wert < 1) {
            LOG.warn("verstoesse.stufen.{}.punkte '{}' ist ungültig (min. 1), nutze {}.",
                    effektiv.schluessel(), wert, effektiv.standardPunkte());
            return effektiv.standardPunkte();
        }
        return wert;
    }

    @Override
    public LimitSnapshot limitSnapshot() {
        FileConfiguration cfg = konfiguration;
        return new LimitSnapshot(
                Math.max(1, cfg.getInt("verstoesse.max-punkte", 6)),
                cfg.getString("verstoesse.auto-bann-dauer", "7d"));
    }

    @Override
    public long timeoutMaxMinuten() {
        long wert = konfiguration.getLong("limits.timeout-max-minuten", 1440L);
        if (wert <= 0) {
            return 1440L;
        }
        return Math.min(wert, Long.MAX_VALUE / 60000L);
    }

    @Override
    public long muteMaxMinuten() {
        long wert = konfiguration.getLong("limits.mute-max-minuten", 43200L);
        if (wert <= 0) {
            return 43200L;
        }
        return Math.min(wert, Long.MAX_VALUE / 60000L);
    }

    @Override
    public long freezeMaxMinuten() {
        if (!konfiguration.contains("limits.freeze-max-minuten")) {
            return muteMaxMinuten();
        }
        long wert = konfiguration.getLong("limits.freeze-max-minuten", 43200L);
        if (wert <= 0) {
            return muteMaxMinuten();
        }
        return Math.min(wert, Long.MAX_VALUE / 60000L);
    }

    @Override
    public long banMaxMinuten() {
        long wert = konfiguration.getLong("limits.ban-max-minuten", 525600L);
        if (wert <= 0) {
            return 525600L;
        }
        return Math.min(wert, Long.MAX_VALUE / 60000L);
    }

    @Override
    public long banMinMinuten() {
        long fallback = timeoutMaxMinuten();
        // Nicht gesetzt (oder <= 0): Timeout-Max steuert gleichzeitig das Ban-Min (eine Option).
        if (!konfiguration.contains("limits.ban-min-minuten")) {
            return Math.min(fallback, banMaxMinuten());
        }
        long wert = konfiguration.getLong("limits.ban-min-minuten", fallback);
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
        return leseBestaetigung("bestaetigungen.pardon-all");
    }

    @Override
    public boolean bestaetigungKickAll() {
        return leseBestaetigung("bestaetigungen.kick-all");
    }

    @Override
    public boolean bestaetigungBanAll() {
        return leseBestaetigung("bestaetigungen.ban-all");
    }

    @Override
    public boolean bestaetigungMuteAll() {
        return leseBestaetigung("bestaetigungen.mute-all");
    }

    @Override
    public boolean bestaetigungTimeoutAll() {
        return leseBestaetigung("bestaetigungen.timeout-all");
    }

    @Override
    public boolean bestaetigungUnmuteAll() {
        return leseBestaetigung("bestaetigungen.unmute-all");
    }

    @Override
    public boolean bestaetigungUntimeoutAll() {
        return leseBestaetigung("bestaetigungen.untimeout-all");
    }

    private boolean leseBestaetigung(String pfad) {
        Object roh = null;
        try {
            roh = konfiguration.get(pfad);
        } catch (Exception e) {
            LOG.warn("Bestätigungspfad {} konnte nicht gelesen werden.", pfad, e);
        }
        if (roh instanceof Boolean bool) {
            return bool;
        }
        if (roh == null) {
            return true;
        }
        LOG.warn("{} ist kein Boolean (Wert: '{}'), erzwinge true (fail-closed). Erlaubt: true/false.", pfad, roh);
        return true;
    }

    @Override
    public boolean schutzFailClosed() {
        Object roh = null;
        try {
            roh = konfiguration.get("schutz.fail-closed");
        } catch (Exception e) {
            LOG.warn("schutz.fail-closed konnte nicht gelesen werden.", e);
        }
        if (roh instanceof Boolean bool) {
            return bool;
        }
        if (roh == null) {
            return true;
        }
        LOG.warn("schutz.fail-closed ist kein Boolean, erzwinge true (fail-closed).");
        return true;
    }

    @Override
    public boolean offlineAllowNeverSeen() {
        Object roh = null;
        try {
            roh = konfiguration.get("offline-resolution.allow-never-seen");
        } catch (Exception e) {
            LOG.trace("offline-resolution.allow-never-seen konnte nicht gelesen werden.", e);
        }
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
        String roh;
        try {
            roh = konfiguration.getString("speicher.typ", "yaml");
        } catch (Exception e) {
            LOG.trace("speicher.typ konnte nicht gelesen werden.", e);
            return SpeicherTyp.YAML;
        }
        try {
            return SpeicherTyp.ausWertStrikt(roh);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("speicher.typ ist ungültig: '" + roh + "'", e);
        }
    }

    public boolean speicherMigration() {
        try {
            Object roh = konfiguration.get("speicher.migration-beim-start");
            if (roh instanceof Boolean bool) {
                return bool;
            }
            return roh == null;
        } catch (Exception e) {
            LOG.trace("speicher.migration-beim-start konnte nicht gelesen werden.", e);
            return true;
        }
    }

    public MysqlKonfiguration mysqlKonfiguration() {
        String host = leseText("speicher.mysql.host", "127.0.0.1");
        int port = leseInt("speicher.mysql.port", 3306);
        String datenbank = leseText("speicher.mysql.datenbank", "punishsystem");
        String benutzer = leseText("speicher.mysql.benutzer", "punish");
        String passwort = leseText("speicher.mysql.passwort", "");
        boolean useSsl = leseBool("speicher.mysql.use-ssl", false);
        int poolMax = leseInt("speicher.mysql.pool-max", 5);
        try {
            return new MysqlKonfiguration(host, port, datenbank, benutzer, passwort, useSsl, poolMax);
        } catch (IllegalArgumentException e) {
            LOG.error("speicher.mysql ist ungültig: {}", e.getMessage());
            throw e;
        }
    }

    private String leseText(String pfad, String standard) {
        try {
            String wert = konfiguration.getString(pfad, standard);
            return wert == null ? standard : wert;
        } catch (Exception e) {
            LOG.trace("Config-Pfad {} konnte nicht gelesen werden.", pfad, e);
            return standard;
        }
    }

    private int leseInt(String pfad, int standard) {
        try {
            return konfiguration.getInt(pfad, standard);
        } catch (Exception e) {
            LOG.trace("Config-Pfad {} konnte nicht gelesen werden.", pfad, e);
            return standard;
        }
    }

    private boolean leseBool(String pfad, boolean standard) {
        try {
            Object roh = konfiguration.get(pfad);
            if (roh instanceof Boolean bool) {
                return bool;
            }
            return standard;
        } catch (Exception e) {
            LOG.trace("Config-Pfad {} konnte nicht gelesen werden.", pfad, e);
            return standard;
        }
    }

    @Override
    public ZoneId anzeigeZone() {
        String roh = konfiguration.getString("anzeige-zone", "Europe/Berlin");
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
        return Math.min(100, Math.max(1, konfiguration.getInt("history.eintraege-pro-seite", 10)));
    }

    @Override
    public long reportCooldownSekunden() {
        long wert = konfiguration.getLong("reports.cooldown-sekunden", 60L);
        return Math.max(0L, Math.min(wert, 3600L));
    }

    @Override
    public int reportMaxOffenProReporter() {
        return Math.min(50, Math.max(1, konfiguration.getInt("reports.max-offen-pro-reporter", 3)));
    }

    @Override
    public long reportDuplikatSperreMinuten() {
        long wert = konfiguration.getLong("reports.duplikat-sperre-minuten", 10L);
        return Math.max(0L, Math.min(wert, 1440L));
    }

    @Override
    public List<String> tabDauerListe() {
        List<String> liste = konfiguration.getStringList("tab.dauer-liste");
        return liste.isEmpty() ? List.of("10m", "1h", "6h", "1d", "7d", "30d", "perm") : liste;
    }

    @Override
    public List<String> tabGrundListe() {
        List<String> liste = konfiguration.getStringList("tab.grund-liste");
        return liste.isEmpty()
                ? List.of("Beleidigung", "Spamming", "Cheating", "Teaming", "Bugusing", "Hacking")
                : liste;
    }

    @Override
    public List<String> grundListe(String befehl) {
        for (String kandidat : Konfiguration.vorschlagBefehlKette(befehl)) {
            List<String> sauber = saubereVorschlagListe(
                    konfiguration.getStringList("vorschlaege." + kandidat + ".gruende"));
            if (!sauber.isEmpty()) {
                return sauber;
            }
        }
        return tabGrundListe();
    }

    @Override
    public List<String> dauerListe(String befehl) {
        for (String kandidat : Konfiguration.vorschlagBefehlKette(befehl)) {
            List<String> sauber = saubereVorschlagListe(
                    konfiguration.getStringList("vorschlaege." + kandidat + ".dauern"));
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
        String gesucht = grund.trim().toLowerCase(Locale.ROOT);
        for (String kandidat : Konfiguration.vorschlagBefehlKette(befehl)) {
            for (java.util.Map<String, String> vorlage : vorlagenRoh()) {
                String vb = vorlage.getOrDefault("befehl", "");
                String vg = vorlage.getOrDefault("grund", "");
                String vd = vorlage.getOrDefault("dauer", "");
                if (!vd.isBlank() && vb.equalsIgnoreCase(kandidat) && vg.equalsIgnoreCase(gesucht)) {
                    return vd;
                }
            }
        }
        for (java.util.Map<String, String> vorlage : vorlagenRoh()) {
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
    public java.util.Map<String, String> vorlagenFuer(String befehl) {
        java.util.Map<String, String> ergebnis = new java.util.LinkedHashMap<>();
        for (String kandidat : Konfiguration.vorschlagBefehlKette(befehl)) {
            for (java.util.Map<String, String> vorlage : vorlagenRoh()) {
                String vb = vorlage.getOrDefault("befehl", "");
                String vg = vorlage.getOrDefault("grund", "");
                String vd = vorlage.getOrDefault("dauer", "");
                if (!vd.isBlank() && vb.equalsIgnoreCase(kandidat) && !vg.isBlank()) {
                    ergebnis.putIfAbsent(vg.toLowerCase(Locale.ROOT), vd);
                }
            }
        }
        return java.util.Map.copyOf(ergebnis);
    }

    @Override
    public List<Vorlage> vorlagen() {
        List<Vorlage> ergebnis = new java.util.ArrayList<>();
        for (java.util.Map<?, ?> roh : konfiguration.getMapList("vorschlaege.vorlagen")) {
            if (roh == null) {
                continue;
            }
            String grund = textAus(roh, "grund");
            if (grund.isEmpty()) {
                continue;
            }
            ergebnis.add(new Vorlage(textAus(roh, "befehl").toLowerCase(Locale.ROOT), grund, textAus(roh, "dauer")));
        }
        return List.copyOf(ergebnis);
    }

    private static String textAus(java.util.Map<?, ?> map, String schluessel) {
        Object wert = map.get(schluessel);
        return wert == null ? "" : String.valueOf(wert).trim();
    }

    private List<java.util.Map<String, String>> vorlagenRoh() {
        List<java.util.Map<String, String>> ergebnis = new java.util.ArrayList<>();
        for (java.util.Map<?, ?> roh : konfiguration.getMapList("vorschlaege.vorlagen")) {
            if (roh == null) {
                continue;
            }
            java.util.Map<String, String> eintrag = new java.util.HashMap<>();
            for (java.util.Map.Entry<?, ?> e : roh.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    eintrag.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
            ergebnis.add(eintrag);
        }
        return ergebnis;
    }

    private static List<String> saubereVorschlagListe(List<String> roh) {
        if (roh == null || roh.isEmpty()) {
            return List.of();
        }
        List<String> sauber = new java.util.ArrayList<>(roh.size());
        for (String eintrag : roh) {
            if (eintrag != null && !eintrag.isBlank()) {
                sauber.add(eintrag.trim());
            }
        }
        return List.copyOf(sauber);
    }

    @Override
    public List<String> muteBlockierteBefehle() {
        if (!konfiguration.contains("mute.blockierte-befehle")) {
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
        return konfiguration.getStringList("mute.blockierte-befehle").stream()
                .filter(e -> e != null && !e.isBlank())
                .map(e -> e.trim().toLowerCase(Locale.ROOT))
                .map(e -> e.startsWith("/") ? e : "/" + e)
                .distinct()
                .toList();
    }

    @Override
    public String standardGrund(String aktion) {
        if (!konfiguration.contains("defaults." + aktion)) {
            LOG.warn("Fehlender defaults-Key '{}' in config/general.yml, nutze Fallback-Text.", aktion);
            return STANDARD_GRUND;
        }
        return konfiguration.getString("defaults." + aktion, STANDARD_GRUND);
    }

    @Override
    public String screen(String typ) {
        String wert = konfiguration.getString("screens." + typ, null);
        if (wert == null) {
            LOG.warn("screens.{} fehlt in config/screens.yml, nutze eingebauten Fallback-Text.", typ);
            return standardScreen(typ);
        }
        return wert;
    }

    @Override
    public boolean broadcastAktiv(String typ) {
        Object roh = konfiguration.get("broadcasts." + typ + ".aktiv");
        if (roh == null) {
            return true;
        }
        if (roh instanceof Boolean bool) {
            return bool;
        }
        // getBoolean würde bei Tippfehler (z. B. "wahr") still auf false fallen und
        // den Broadcast abschalten – bewusst strict lesen und fail-open (aktiv) werten.
        LOG.warn("broadcasts.{}.aktiv ist kein Boolean (Wert: '{}'), nutze true. Erlaubt: true/false.",
                typ, roh);
        return true;
    }

    @Override
    public String broadcastNachricht(String typ) {
        String nachricht = konfiguration.getString("broadcasts." + typ + ".nachricht", "");
        if (nachricht == null || nachricht.isBlank()) {
            LOG.warn("Kein Broadcast-Text für Typ '{}' (unbekannt oder leer), nutze Fallback-Text.", typ);
            return "<gray>[<yellow>Broadcast<gray>] <white>%spieler% <gray>%grund%";
        }
        return nachricht;
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

    private volatile String gelernterServerName;

    public void lerneServerName(String kanonisch) {
        String normiert = ServerScope.normalisiereServerName(kanonisch);
        if (normiert == null) {
            return;
        }
        String bisher = gelernterServerName;
        if (normiert.equals(bisher)) {
            return;
        }
        gelernterServerName = normiert;
        LOG.info("Bridge-Servername vom Proxy übernommen: {} (Zero-Config, config-Eintrag optional).", normiert);
    }

    @Override
    public String serverName() {
        String gelernt = gelernterServerName;
        if (gelernt != null) {
            return gelernt;
        }
        String roh = konfiguration.getString("netzwerk.server-name", null);
        String normiert = ServerScope.normalisiereServerName(roh);
        if (normiert != null) {
            return normiert;
        }
        return ServerScope.UNBEKANNT_WERT;
    }

    public String tunnelSecret() {
        try {
            String roh = konfiguration.getString("netzwerk.tunnel-secret", null);
            return roh == null ? "" : roh.trim();
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public List<String> bekannteServer() {
        List<String> roh;
        try {
            roh = konfiguration.getStringList("netzwerk.server-liste");
        } catch (Exception e) {
            return List.of();
        }
        if (roh == null || roh.isEmpty()) {
            return List.of();
        }
        List<String> normiert = new ArrayList<>();
        for (String eintrag : roh) {
            String name = ServerScope.normalisiereServerName(eintrag);
            if (name != null && !normiert.contains(name)) {
                normiert.add(name);
            }
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

    public String guiTitel() {
        return konfiguration.getString("gui.titel", "Punish");
    }

    public List<String> guiFallbackGruende() {
        List<String> liste = konfiguration.getStringList("gui.fallback-gruende");
        return liste.isEmpty()
                ? List.of("Beleidigung", "Spamming", "Cheating", "Teaming", "Bugusing", "Hacking")
                : List.copyOf(liste);
    }

    public List<String> guiFallbackDauern() {
        List<String> liste = konfiguration.getStringList("gui.fallback-dauern");
        return liste.isEmpty()
                ? List.of("10m", "1h", "6h", "1d", "7d", "30d", "perm")
                : List.copyOf(liste);
    }
}
