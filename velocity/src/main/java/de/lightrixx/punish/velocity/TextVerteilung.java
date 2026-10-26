package de.lightrixx.punish.velocity;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import de.lightrixx.punish.chatfilter.WortlistenFilter;
import de.lightrixx.punish.net.Protokoll;
import org.slf4j.Logger;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TextVerteilung {

    public static final int CHUNK_GROESSE = 30000;

    private static final String WORTLISTE_KOPF = "# PunishSystem - zentrale Wortliste (Proxy-Master)\n"
            + "# Wird per Text-Sync an alle Bridges verteilt. Einträge mit\n"
            + "# /chatfilter add ergänzen (Konsole und Spieler möglich).\n";

    private static final String GUI_MINIMAL = "gui:\n  titel: \"Punish\"\n";

    public interface Versand {
        void sende(RegisteredServer server, byte[] paket) throws Exception;
    }

    private final com.velocitypowered.api.proxy.ProxyServer proxy;
    private final Logger logger;
    private final File configOrdner;
    private final File chatfilterOrdner;
    private final Versand versand;
    private final ExecutorService pool;
    private final Set<String> laufend = ConcurrentHashMap.newKeySet();

    private volatile Map<String, byte[]> pakete = Map.of();
    private volatile String paketHash = "";

    public TextVerteilung(com.velocitypowered.api.proxy.ProxyServer proxy, Logger logger,
                          File datenOrdner, Versand versand) {
        this.proxy = Objects.requireNonNull(proxy, "Der Proxy darf nicht null sein.");
        this.logger = Objects.requireNonNull(logger, "Der Logger darf nicht null sein.");
        File daten = Objects.requireNonNull(datenOrdner, "Der Datenordner darf nicht null sein.");
        this.configOrdner = new File(daten, "config");
        this.chatfilterOrdner = new File(daten, "chatfilter");
        this.versand = Objects.requireNonNull(versand, "Der Versand darf nicht null sein.");
        this.pool = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "punish-text-sync");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void laden() {
        try {
            if (!chatfilterOrdner.exists()) {
                chatfilterOrdner.mkdirs();
            }
            File wortliste = new File(chatfilterOrdner, "wortliste.txt");
            if (!wortliste.isFile()) {
                byte[] saat = leseSeedWortliste();
                if (saat == null) {
                    saat = WORTLISTE_KOPF.getBytes(StandardCharsets.UTF_8);
                }
                Files.write(wortliste.toPath(), saat);
                logger.info("Zentrale Wortliste angelegt ({}).", wortliste.getName());
            }
            File guiDatei = new File(configOrdner, "gui.yml");
            if (!guiDatei.isFile()) {
                byte[] saat = leseSeedGui();
                if (saat == null) {
                    saat = GUI_MINIMAL.getBytes(StandardCharsets.UTF_8);
                }
                Files.write(guiDatei.toPath(), saat);
                logger.info("Zentrale GUI-Datei angelegt ({}).", guiDatei.getName());
            }
            Map<String, byte[]> neu = new LinkedHashMap<>();
            neu.put("general.yml", generalBereinigt());
            neu.put("messages.yml", leseStill(new File(configOrdner, "messages.yml")));
            neu.put("screens.yml", leseStill(new File(configOrdner, "screens.yml")));
            neu.put("broadcasts.yml", leseStill(new File(configOrdner, "broadcasts.yml")));
            neu.put("gui.yml", leseStill(guiDatei));
            neu.put("wortliste.txt", leseStill(wortliste));
            for (var eintrag : neu.entrySet()) {
                if (eintrag.getValue() == null) {
                    logger.warn("Text-Datei {} fehlt – bisheriger Text-Sync bleibt aktiv ({} Dateien).",
                            eintrag.getKey(), pakete.size());
                    return;
                }
            }
            pakete = Map.copyOf(neu);
            paketHash = hashPaket(neu);
            logger.info("Text-Sync bereit ({} Dateien, Paket {}).", neu.size(),
                    paketHash.substring(0, 12));
        } catch (Exception e) {
            logger.warn("Text-Dateien konnten nicht geladen werden – bisheriger Text-Sync bleibt aktiv.", e);
        }
    }

    private byte[] leseSeedWortliste() {
        try (var in = getClass().getResourceAsStream("/config/wortliste.txt")) {
            if (in == null) {
                return null;
            }
            byte[] daten = in.readAllBytes();
            return daten.length > 0 ? daten : null;
        } catch (Exception e) {
            logger.debug("Seed-Wortliste nicht lesbar.", e);
            return null;
        }
    }

    private byte[] leseSeedGui() {
        try (var in = getClass().getResourceAsStream("/config/gui.yml")) {
            if (in == null) {
                return null;
            }
            byte[] daten = in.readAllBytes();
            return daten.length > 0 ? daten : null;
        } catch (Exception e) {
            logger.debug("Seed-GUI nicht lesbar.", e);
            return null;
        }
    }

    private byte[] generalBereinigt() {
        try {
            byte[] roh = leseStill(new File(configOrdner, "general.yml"));
            if (roh == null) {
                return null;
            }
            return bereinigeGeneral(roh);
        } catch (Exception e) {
            logger.debug("general.yml konnte nicht bereinigt werden.", e);
            return null;
        }
    }

    static byte[] bereinigeGeneral(byte[] roh) {
        Object geladen = safeYaml().load(new String(roh, StandardCharsets.UTF_8));
        if (!(geladen instanceof Map)) {
            return roh;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> karte = new LinkedHashMap<>((Map<String, Object>) geladen);
        karte.remove("speicher");
        Object netzwerk = karte.get("netzwerk");
        if (netzwerk instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> netz = new LinkedHashMap<>((Map<String, Object>) netzwerk);
            netz.remove("server-name");
            netz.remove("tunnel-secret");
            netz.remove("bridge-secrets");
            karte.put("netzwerk", netz);
        }
        org.yaml.snakeyaml.DumperOptions optionen = new org.yaml.snakeyaml.DumperOptions();
        optionen.setDefaultFlowStyle(org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK);
        optionen.setAllowUnicode(true);
        return new org.yaml.snakeyaml.Yaml(optionen).dump(karte)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static org.yaml.snakeyaml.Yaml safeYaml() {
        var optionen = new org.yaml.snakeyaml.LoaderOptions();
        return new org.yaml.snakeyaml.Yaml(
                new org.yaml.snakeyaml.constructor.SafeConstructor(optionen));
    }

    private static byte[] leseStill(File datei) {
        try {
            if (datei == null || !datei.isFile()) {
                return null;
            }
            long laenge = datei.length();
            if (laenge < 1 || laenge > Protokoll.MAX_TEXT_DATEI_BYTES) {
                return null;
            }
            return Files.readAllBytes(datei.toPath());
        } catch (Exception e) {
            return null;
        }
    }

    static String hashPaket(Map<String, byte[]> dateien) {
        return Protokoll.textPaketHash(dateien);
    }

    public boolean aktiv() {
        return !pakete.isEmpty() && !paketHash.isEmpty();
    }

    public String paketHash() {
        return paketHash;
    }

    public void standEmpfangen(RegisteredServer server, String stand) {
        if (server == null || !aktiv()) {
            return;
        }
        String sauber = stand == null ? "" : stand.trim().toLowerCase(java.util.Locale.ROOT);
        if (paketHash.equals(sauber)) {
            logger.debug("Text-Stand aktuell ({}).", serverName(server));
            return;
        }
        logger.info("Text-Sync an '{}' (Proxy-Stand neuer/abweichend).", serverName(server));
        syncAn(server);
    }

    public void quittungEmpfangen(RegisteredServer server, Protokoll.TextQuittung quittung) {
        if (quittung == null) {
            return;
        }
        if (quittung.erfolg()) {
            logger.info("Text-Sync erfolgreich (Paket {} auf {}).",
                    kuerzeHash(quittung.paketHash()), server == null ? "?" : serverName(server));
        } else {
            logger.warn("Text-Sync abgelehnt (Paket {} auf {}).",
                    kuerzeHash(quittung.paketHash()), server == null ? "?" : serverName(server));
        }
    }

    private static String kuerzeHash(String hash) {
        if (hash == null || hash.isBlank()) {
            return "?";
        }
        String sauber = hash.trim();
        return sauber.length() > 12 ? sauber.substring(0, 12) : sauber;
    }

    public void syncAnAlle() {
        if (!aktiv()) {
            logger.warn("Text-Sync angefordert, aber inaktiv (Dateien fehlen?).");
            return;
        }
        for (RegisteredServer server : proxy.getAllServers()) {
            syncAn(server);
        }
        logger.info("Text-Sync an alle Bridges angestoßen.");
    }

    @SuppressWarnings("unchecked")
    public void syncAn(RegisteredServer server) {
        if (server == null || !aktiv()) {
            return;
        }
        Map<String, byte[]> sendePakete = pakete;
        String hash = paketHash;
        String name = serverName(server);
        if (!laufend.add(name)) {
            logger.debug("Text-Sync an '{}' läuft bereits.", name);
            return;
        }
        try {
            pool.submit(() -> {
                try {
                    var namen = new ArrayList<>(sendePakete.keySet());
                    namen.sort(String::compareTo);
                    for (String datei : namen) {
                        sendeDatei(server, datei, sendePakete.get(datei));
                    }
                    versand.sende(server, Protokoll.textFertig(new Protokoll.TextFertig(hash)));
                    logger.info("Text-Sync an '{}' gesendet (Quittung ausstehend).", name);
                } catch (Exception e) {
                    logger.warn("Text-Sync an '{}' fehlgeschlagen.", name, e);
                } finally {
                    laufend.remove(name);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            laufend.remove(name);
            logger.debug("Text-Sync an '{}' verworfen (Executor geschlossen).", name);
        }
    }

    private void sendeDatei(RegisteredServer server, String name, byte[] daten) throws Exception {
        int anzahl = (daten.length + CHUNK_GROESSE - 1) / CHUNK_GROESSE;
        String hash = TextVerteilung.hashBytes(daten);
        versand.sende(server, Protokoll.textMeta(
                new Protokoll.TextMeta(name, daten.length, hash, CHUNK_GROESSE, anzahl)));
        for (int i = 0; i < anzahl; i++) {
            int von = i * CHUNK_GROESSE;
            int bis = Math.min(daten.length, von + CHUNK_GROESSE);
            byte[] teil = java.util.Arrays.copyOfRange(daten, von, bis);
            versand.sende(server, Protokoll.textBlock(
                    new Protokoll.TextBlock(name, i, teil)));
        }
    }

    static String hashBytes(byte[] daten) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(daten));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 nicht verfügbar.", e);
        }
    }

    public boolean wortHinzufuegen(String wort) {
        Optional<String> gueltig = WortlistenFilter.gueltigerEintrag(wort);
        if (gueltig.isEmpty()) {
            return false;
        }
        try {
            File wortliste = new File(chatfilterOrdner, "wortliste.txt");
            byte[] roh = leseStill(wortliste);
            String stand = roh == null ? "" : new String(roh, StandardCharsets.UTF_8);
            String gesucht = gueltig.get();
            boolean vorhanden = stand.lines()
                    .map(linie -> linie.trim().toLowerCase(java.util.Locale.ROOT))
                    .anyMatch(linie -> linie.equals(gesucht.toLowerCase(java.util.Locale.ROOT)));
            if (!vorhanden) {
                String anhang = (stand.isEmpty() || stand.endsWith("\n") ? "" : "\n") + gesucht + "\n";
                Files.writeString(wortliste.toPath(), anhang, StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            }
            laden();
            syncAnAlle();
            return true;
        } catch (Exception e) {
            logger.warn("Wort konnte nicht in die zentrale Wortliste aufgenommen werden.", e);
            return false;
        }
    }

    static String serverName(RegisteredServer server) {
        try {
            return server.getServerInfo().getName();
        } catch (Exception e) {
            return "?";
        }
    }

    public void schliessen() {
        try {
            pool.shutdownNow();
        } catch (Exception e) {
            logger.debug("Text-Sync-Pool konnte nicht beendet werden.", e);
        }
    }
}
