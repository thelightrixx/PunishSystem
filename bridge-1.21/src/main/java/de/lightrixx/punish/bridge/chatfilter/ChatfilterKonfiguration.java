package de.lightrixx.punish.bridge.chatfilter;

import de.lightrixx.punish.bridge.TextEmpfaenger;
import de.lightrixx.punish.chatfilter.FilterKanal;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class ChatfilterKonfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(ChatfilterKonfiguration.class);

    public enum Modus {
        AUS,
        STUFE1,
        KI
    }

    private final JavaPlugin plugin;
    private final File ordner;
    private final File datei;
    private volatile FileConfiguration konfiguration = new YamlConfiguration();
    private volatile boolean ladeFehler;

    public ChatfilterKonfiguration(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "Das Plugin darf nicht null sein.");
        this.ordner = new File(plugin.getDataFolder(), "chatfilter");
        this.datei = new File(new File(plugin.getDataFolder(), "config"), "chatfilter.yml");
    }

    public boolean hatLadeFehler() {
        return ladeFehler;
    }

    public void laden() {
        ladeFehler = false;
        try {
            File configOrdner = datei.getParentFile();
            if (configOrdner != null && !configOrdner.exists()) {
                configOrdner.mkdirs();
            }
            if (!ordner.exists()) {
                ordner.mkdirs();
            }
            if (!datei.exists()) {
                try {
                    plugin.saveResource("config/chatfilter.yml", false);
                } catch (Exception e) {
                    LOG.warn("Standard-chatfilter.yml konnte nicht angelegt werden.", e);
                }
            }
            FileConfiguration geladen = new YamlConfiguration();
            if (datei.isFile()) {
                try {
                    geladen.load(datei);
                } catch (Exception e) {
                    ladeFehler = true;
                    LOG.warn("chatfilter.yml konnte nicht gelesen werden, nutze Defaults.", e);
                    geladen = new YamlConfiguration();
                }
            }
            mergeDefaults(geladen);
            wendeProxyFilterDefaultsAn(geladen);
            this.konfiguration = geladen;
            sichereWortliste();
        } catch (Exception e) {
            ladeFehler = true;
            LOG.warn("Chatfilter-Konfiguration konnte nicht geladen werden, nutze Defaults.", e);
        }
        LOG.info("Chatfilter: aktiv={}, schwelle={}, ki-timeout={}ms.",
                aktiv(), schwelle(), kiTimeoutMillis());
    }

    private void mergeDefaults(FileConfiguration geladen) {
        try (var in = plugin.getResource("config/chatfilter.yml")) {
            if (in == null) {
                return;
            }
            try (var reader = new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)) {
                FileConfiguration def = YamlConfiguration.loadConfiguration(reader);
                for (String key : def.getKeys(true)) {
                    try {
                        if (def.isConfigurationSection(key)) {
                            continue;
                        }
                        if (!geladen.contains(key)) {
                            geladen.set(key, def.get(key));
                        }
                    } catch (Exception e) {
                        LOG.debug("Chatfilter-Default-Key {} konnte nicht gemerged werden.", key, e);
                    }
                }
            }
        } catch (Exception e) {
            LOG.debug("Chatfilter-Jar-Defaults konnten nicht geladen werden.", e);
        }
    }

    private void wendeProxyFilterDefaultsAn(FileConfiguration ziel) {
        try {
            File sync = new File(new File(plugin.getDataFolder(), TextEmpfaenger.SYNC_ORDNER), "general.yml");
            if (!sync.isFile() || sync.length() < 1) {
                return;
            }
            FileConfiguration proxy = YamlConfiguration.loadConfiguration(sync);
            boolean übernommen = false;
            for (String pfad : List.of("chatfilter.aktiv", "chatfilter.schwelle", "chatfilter.ki-timeout-ms",
                    "chatfilter.max-zeichen", "chatfilter.klartext-uebertragen")) {
                if (proxy.contains(pfad)) {
                    ziel.set(pfad, proxy.get(pfad));
                    übernommen = true;
                }
            }
            for (String kanal : List.of("chat", "privat", "schild", "buch", "amboss")) {
                String pfad = "chatfilter.kanaele." + kanal;
                if (proxy.contains(pfad)) {
                    ziel.set(pfad, proxy.getString(pfad));
                    übernommen = true;
                }
            }
            if (übernommen) {
                LOG.info("Proxy-Filterwerte übernommen (Text-Sync, lokale chatfilter.yml ist Fallback).");
            }
        } catch (Exception e) {
            LOG.debug("Proxy-Filterwerte konnten nicht übernommen werden (lokale Werte aktiv).", e);
        }
    }

    public boolean aktiv() {
        return leseBool("chatfilter.aktiv", false);
    }

    public Boolean leseLaufzeitFilter() {
        try {
            Object roh = konfiguration.get("chatfilter.laufzeit-filter");
            return roh instanceof Boolean bool ? bool : null;
        } catch (Exception e) {
            return null;
        }
    }

    public Boolean leseLaufzeitKi() {
        try {
            Object roh = konfiguration.get("chatfilter.laufzeit-ki");
            return roh instanceof Boolean bool ? bool : null;
        } catch (Exception e) {
            return null;
        }
    }

    public boolean speichereLaufzeitSchalter(Boolean filter, Boolean ki) {
        try {
            FileConfiguration neu = new YamlConfiguration();
            if (datei.isFile()) {
                try {
                    neu.load(datei);
                } catch (Exception e) {
                    LOG.debug("Laufzeit-Schalter: vorhandene chatfilter.yml nicht lesbar, lege neu an.", e);
                }
            }
            if (filter == null) {
                neu.set("chatfilter.laufzeit-filter", null);
            } else {
                neu.set("chatfilter.laufzeit-filter", filter);
            }
            if (ki == null) {
                neu.set("chatfilter.laufzeit-ki", null);
            } else {
                neu.set("chatfilter.laufzeit-ki", ki);
            }
            File eltern = datei.getParentFile();
            if (eltern != null && !eltern.exists()) {
                eltern.mkdirs();
            }
            File tmp = new File(eltern, datei.getName() + ".tmp");
            neu.save(tmp);
            try {
                java.nio.file.Files.move(tmp.toPath(), datei.toPath(),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception moveFehler) {
                java.nio.file.Files.move(tmp.toPath(), datei.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            konfiguration.set("chatfilter.laufzeit-filter", filter);
            konfiguration.set("chatfilter.laufzeit-ki", ki);
            return true;
        } catch (Exception e) {
            LOG.warn("Laufzeit-Schalter konnte nicht persistiert werden.", e);
            return false;
        }
    }

    public double schwelle() {
        double wert;
        try {
            wert = konfiguration.getDouble("chatfilter.schwelle", 0.7d);
        } catch (Exception e) {
            return 0.7d;
        }
        if (Double.isNaN(wert) || wert < 0.0d || wert > 1.0d) {
            LOG.warn("chatfilter.schwelle '{}' ist ungültig, nutze 0.7.", wert);
            return 0.7d;
        }
        return wert;
    }

    public long kiTimeoutMillis() {
        long wert;
        try {
            wert = konfiguration.getLong("chatfilter.ki-timeout-ms", 150L);
        } catch (Exception e) {
            return 150L;
        }
        return Math.min(5000L, Math.max(20L, wert));
    }

    public int maxZeichen() {
        int wert;
        try {
            wert = konfiguration.getInt("chatfilter.max-zeichen", 500);
        } catch (Exception e) {
            return 500;
        }
        return Math.min(2000, Math.max(50, wert));
    }

    public String bypassPermission() {
        String wert;
        try {
            wert = konfiguration.getString("chatfilter.bypass-permission", "punish.chatfilter.bypass");
        } catch (Exception e) {
            return "punish.chatfilter.bypass";
        }
        if (wert == null || wert.isBlank()) {
            return "punish.chatfilter.bypass";
        }
        return wert.trim();
    }

    public Modus modusFuer(FilterKanal kanal) {
        Objects.requireNonNull(kanal, "Der Kanal darf nicht null sein.");
        String schluessel = switch (kanal) {
            case CHAT -> "chat";
            case PRIVATNACHRICHT -> "privat";
            case SCHILD -> "schild";
            case BUCH -> "buch";
            case AMBOSS -> "amboss";
        };
        String roh;
        try {
            roh = konfiguration.getString("chatfilter.kanaele." + schluessel, "stufe1");
        } catch (Exception e) {
            return Modus.STUFE1;
        }
        if (roh == null || roh.isBlank()) {
            return Modus.STUFE1;
        }
        try {
            return Modus.valueOf(roh.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            LOG.warn("chatfilter.kanaele.{} '{}' ist ungültig, nutze stufe1.", schluessel, roh);
            return Modus.STUFE1;
        }
    }

    private void sichereWortliste() {
        try {
            File ziel = dateiAufloesen(wortlisteDateiname());
            if (ziel.exists()) {
                return;
            }
            File eltern = ziel.getParentFile();
            if (eltern != null && !eltern.exists()) {
                eltern.mkdirs();
            }
            try (var in = plugin.getResource("config/wortliste.txt")) {
                if (in == null) {
                    LOG.debug("Gebündelte Standard-Wortliste fehlt im Jar.");
                    return;
                }
                java.nio.file.Files.copy(in, ziel.toPath());
            }
            LOG.info("Standard-Wortliste angelegt ({}).", ziel.getName());
        } catch (Exception e) {
            LOG.warn("Standard-Wortliste konnte nicht angelegt werden.", e);
        }
    }

    public File dateiAufloesen(String name) {
        Objects.requireNonNull(name, "Der Dateiname darf nicht null sein.");
        File kandidat = new File(name.trim());
        if (kandidat.isAbsolute()) {
            return kandidat;
        }
        return new File(ordner, name.trim());
    }

    public List<String> leseZeilen(File datei) throws java.io.IOException {
        Objects.requireNonNull(datei, "Die Datei darf nicht null sein.");
        return java.nio.file.Files.readAllLines(datei.toPath(), java.nio.charset.StandardCharsets.UTF_8);
    }

    public String wortlisteDateiname() {
        return leseText("chatfilter.wortliste-datei", "wortliste.txt");
    }

    public String modellDateiname() {
        return leseText("chatfilter.modell-datei", "modell_int8.onnx");
    }

    public String vocabDateiname() {
        return leseText("chatfilter.vocab-datei", "vocab.txt");
    }

    public boolean klartextUbertragen() {
        try {
            Object roh = konfiguration.get("chatfilter.klartext-uebertragen");
            if (roh instanceof Boolean bool) {
                return bool;
            }
            return true;
        } catch (Exception e) {
            return true;
        }
    }

    public java.util.Set<String> befehlAusnahmen() {
        try {
            java.util.List<String> roh = konfiguration.getStringList("chatfilter.befehl-ausnahmen");
            if (roh == null || roh.isEmpty()) {
                return java.util.Set.of();
            }
            java.util.Set<String> normiert = new java.util.HashSet<>();
            for (String eintrag : roh) {
                if (eintrag == null || eintrag.isBlank()) {
                    continue;
                }
                String befehl = eintrag.trim().toLowerCase(java.util.Locale.ROOT);
                if (!befehl.startsWith("/")) {
                    befehl = "/" + befehl;
                }
                normiert.add(befehl);
            }
            return java.util.Set.copyOf(normiert);
        } catch (Exception e) {
            return java.util.Set.of();
        }
    }

    private String leseText(String pfad, String standard) {
        try {
            String wert = konfiguration.getString(pfad, standard);
            return wert == null || wert.isBlank() ? standard : wert.trim();
        } catch (Exception e) {
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
            return standard;
        }
    }
}
