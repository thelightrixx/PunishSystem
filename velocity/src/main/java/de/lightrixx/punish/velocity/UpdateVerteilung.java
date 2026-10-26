package de.lightrixx.punish.velocity;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import de.lightrixx.punish.net.Protokoll;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class UpdateVerteilung {

    public static final int CHUNK_GROESSE = 30000;
    private static final long PAUSE_MS = 10L;

    public interface Versand {
        void sende(RegisteredServer server, byte[] paket) throws Exception;
    }

    private final com.velocitypowered.api.proxy.ProxyServer proxy;
    private final Logger logger;
    private final Versand versand;
    private final ExecutorService pool;
    private final Set<String> laufend = ConcurrentHashMap.newKeySet();
    private final Map<String, Boolean> quittungen = new ConcurrentHashMap<>();
    private volatile byte[] bridgeBytes;
    private volatile byte[] bridge121Bytes;
    private volatile String bridgeHash = "";
    private volatile String bridge121Hash = "";
    private volatile String version = "";

    public UpdateVerteilung(com.velocitypowered.api.proxy.ProxyServer proxy, Logger logger, Versand versand) {
        this.proxy = Objects.requireNonNull(proxy, "Der Proxy darf nicht null sein.");
        this.logger = Objects.requireNonNull(logger, "Der Logger darf nicht null sein.");
        this.versand = Objects.requireNonNull(versand, "Der Versand darf nicht null sein.");
        this.pool = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "punish-update-push");
            thread.setDaemon(true);
            return thread;
        });
    }

    public synchronized void legeAb(String neueVersion, byte[] bridge, byte[] bridge121) {
        this.version = neueVersion == null ? "" : neueVersion.trim();
        this.bridgeBytes = bridge;
        this.bridge121Bytes = bridge121;
        this.bridgeHash = hashStill(bridge);
        this.bridge121Hash = hashStill(bridge121);
        quittungen.clear();
    }

    public boolean bereit() {
        return !version.isBlank() && (bridgeBytes != null || bridge121Bytes != null);
    }

    public String version() {
        return version;
    }

    public void standEmpfangen(RegisteredServer server, String kanonisch) {
        if (server == null || !bereit()) {
            return;
        }
        pushAn(server, kanonisch);
    }

    public void quittungEmpfangen(RegisteredServer server, Protokoll.UpdateQuittung quittung) {
        if (quittung == null) {
            return;
        }
        String name = server == null ? "?" : serverName(server);
        quittungen.put(name, quittung.erfolg());
        if (quittung.erfolg()) {
            logger.info("Update-Push erfolgreich ('{}' {} auf {}).", quittung.variante(), quittung.version(), name);
        } else {
            logger.warn("Update-Push abgelehnt ('{}' {} auf {}).", quittung.variante(), quittung.version(), name);
        }
    }

    public Map<String, Boolean> quittungen() {
        return Map.copyOf(quittungen);
    }

    public void pushAnAlle(BrueckenKanal kanal) {
        if (!bereit()) {
            logger.warn("Update-Push angefordert, aber keine Update-Dateien abgelegt.");
            return;
        }
        for (RegisteredServer server : proxy.getAllServers()) {
            String kanonisch = serverName(server);
            String variante = "";
            try {
                variante = kanal == null ? "" : kanal.bridgeVarianten().getOrDefault(kanonisch, "");
            } catch (Exception e) {
                variante = "";
            }
            if (variante.isBlank()) {
                pushBeide(server, kanonisch);
            } else {
                pushAn(server, kanonisch);
            }
        }
        logger.info("Update-Push an alle Bridges angestoßen (Version {}).", version);
    }

    private void pushBeide(RegisteredServer server, String kanonisch) {
        if (bridgeBytes != null) {
            sendeVariante(server, kanonisch, "bridge", bridgeBytes, bridgeHash);
        }
        if (bridge121Bytes != null) {
            sendeVariante(server, kanonisch, "bridge-1.21", bridge121Bytes, bridge121Hash);
        }
    }

    public void pushAn(RegisteredServer server, String kanonisch) {
        if (server == null || !bereit()) {
            return;
        }
        String name = kanonisch == null || kanonisch.isBlank() ? serverName(server) : kanonisch;
        pushBeide(server, name);
    }

    public void pushVarianteAn(RegisteredServer server, String variante) {
        if (server == null || !bereit() || variante == null) {
            return;
        }
        String v = variante.trim().toLowerCase(java.util.Locale.ROOT);
        if ("bridge".equals(v) && bridgeBytes != null) {
            sendeVariante(server, serverName(server), v, bridgeBytes, bridgeHash);
        } else if ("bridge-1.21".equals(v) && bridge121Bytes != null) {
            sendeVariante(server, serverName(server), v, bridge121Bytes, bridge121Hash);
        }
    }

    private void sendeVariante(RegisteredServer server, String name, String variante, byte[] daten, String hash) {
        String schluessel = name + "|" + variante;
        if (!laufend.add(schluessel)) {
            logger.debug("Update-Push an '{}' ({}) läuft bereits.", name, variante);
            return;
        }
        String pushVersion = version;
        try {
            pool.submit(() -> {
                try {
                    sendeDatei(server, variante, pushVersion, daten, hash);
                    logger.info("Update-Push an '{}' ({}, {} Bytes) gesendet (Quittung ausstehend).",
                            name, variante, daten.length);
                } catch (Exception e) {
                    logger.warn("Update-Push an '{}' ({}) fehlgeschlagen.", name, variante, e);
                } finally {
                    laufend.remove(schluessel);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            laufend.remove(schluessel);
            logger.debug("Update-Push an '{}' ({}) verworfen (Executor geschlossen).", name, variante);
        }
    }

    private void sendeDatei(RegisteredServer server, String variante, String pushVersion,
                            byte[] daten, String hash) throws Exception {
        int anzahl = (daten.length + CHUNK_GROESSE - 1) / CHUNK_GROESSE;
        versand.sende(server, Protokoll.updateMeta(
                new Protokoll.UpdateMeta(variante, pushVersion, daten.length, hash, CHUNK_GROESSE, anzahl)));
        for (int i = 0; i < anzahl; i++) {
            int von = i * CHUNK_GROESSE;
            int bis = Math.min(daten.length, von + CHUNK_GROESSE);
            byte[] teil = java.util.Arrays.copyOfRange(daten, von, bis);
            versand.sende(server, Protokoll.updateBlock(
                    new Protokoll.UpdateBlock(variante, i, teil)));
            try {
                Thread.sleep(PAUSE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Update-Push unterbrochen.", e);
            }
        }
        versand.sende(server, Protokoll.updateFertig(
                new Protokoll.UpdateFertig(variante, pushVersion, hash, daten.length)));
    }

    static String hashStill(byte[] daten) {
        try {
            if (daten == null || daten.length == 0) {
                return "";
            }
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(daten));
        } catch (Exception e) {
            return "";
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
            logger.debug("Update-Push-Pool konnte nicht beendet werden.", e);
        }
    }
}
