package de.lightrixx.punish.velocity;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import de.lightrixx.punish.net.Protokoll;
import org.slf4j.Logger;

import java.io.File;
import java.nio.file.Files;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ModellVerteilung {

    /** Blockgröße: bewusst unter der 32-KB-Grenze für Custom-Payloads.
     *  Größere Blöcke wirft Papers Decoder mit DecoderException weg
     *  und kickt den Spieler auf der betroffenen Verbindung. */
    public static final int CHUNK_GROESSE = 30000;
    private static final long PAUSE_MS = 10L;
    private static final String MODELL_NAME = "modell_int8.onnx";
    private static final String VOCAB_NAME = "vocab.txt";

    public interface Versand {
        void sende(RegisteredServer server, byte[] paket) throws Exception;
    }

    @FunctionalInterface
    interface Download {
        byte[] laden(Logger log, String url, long maxBytes);
    }

    private final com.velocitypowered.api.proxy.ProxyServer proxy;
    private final Logger logger;
    private final VelocityKonfiguration konfiguration;
    private final File ordner;
    private final Versand versand;
    private final ExecutorService pool;
    private final Download download;
    private final Set<String> laufend = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean downloadLaeuft = new AtomicBoolean();

    private volatile byte[] modellBytes;
    private volatile byte[] vocabBytes;
    private volatile String modellHash = "";
    private volatile String vocabHash = "";

    public ModellVerteilung(com.velocitypowered.api.proxy.ProxyServer proxy, Logger logger,
                            VelocityKonfiguration konfiguration, File datenOrdner, Versand versand) {
        this(proxy, logger, konfiguration, datenOrdner, versand, ModellVerteilung::ladeUrl);
    }

    ModellVerteilung(com.velocitypowered.api.proxy.ProxyServer proxy, Logger logger,
                     VelocityKonfiguration konfiguration, File datenOrdner, Versand versand,
                     Download download) {
        this.proxy = Objects.requireNonNull(proxy, "Der Proxy darf nicht null sein.");
        this.logger = Objects.requireNonNull(logger, "Der Logger darf nicht null sein.");
        this.konfiguration = Objects.requireNonNull(konfiguration, "Die Konfiguration darf nicht null sein.");
        this.ordner = new File(Objects.requireNonNull(datenOrdner, "Der Datenordner darf nicht null sein."),
                "chatfilter");
        this.versand = Objects.requireNonNull(versand, "Der Versand darf nicht null sein.");
        this.download = Objects.requireNonNull(download, "Der Download darf nicht null sein.");
        this.pool = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "punish-modell-sync");
            thread.setDaemon(true);
            return thread;
        });
    }

    static final String VOCAB_URL = "https://huggingface.co/ankekat1000/toxic-bert-german/resolve/main/vocab.txt";
    static final String VOCAB_HASH = "982F8396EC746DB0ED414DCC4789398AB6B365663CADA50F776AFB905DACBB61";
    static final String MODELL_HASH = "95979FBCDC34784670CBD11BEAA7952B743BEEA7B1DD7E64908AEE5AD00AC825";

    /**
     * Liest vorhandene Modell-/Vokabular-Dateien lokal ein. Löst bewusst keinen
     * Netzwerkzugriff aus; der Auto-Download läuft über {@link #starten()}.
     */
    public void laden() {
        try {
            if (!ordner.exists()) {
                ordner.mkdirs();
            }
            byte[] modell = leseStill(new File(ordner, MODELL_NAME));
            byte[] vocab = leseStill(new File(ordner, VOCAB_NAME));
            if (modell == null || vocab == null) {
                modellBytes = null;
                vocabBytes = null;
                modellHash = "";
                vocabHash = "";
                logger.info("Modell-Sync inaktiv (kein Modell auf dem Proxy, Ordner {}).", ordner.getName());
                return;
            }
            modellBytes = modell;
            vocabBytes = vocab;
            modellHash = hashBytes(modell);
            vocabHash = hashBytes(vocab);
            logger.info("Modell-Sync bereit (Modell {} MB, Vocab {} KB).",
                    modell.length / 1024 / 1024, vocab.length / 1024);
        } catch (Exception e) {
            logger.warn("Modell-Dateien konnten nicht geladen werden, Sync inaktiv.", e);
            modellBytes = null;
            vocabBytes = null;
        }
    }

    /**
     * Startet die Verteilung ohne blockierenden Netzwerkzugriff: lokale Dateien
     * werden sofort gelesen, ein fehlendes Modell wird im Hintergrund geladen und
     * danach einmal an alle Bridges verteilt.
     */
    public void starten() {
        laden();
        if (aktiv() || !autoDownloadAktiv() || !chatfilterAktiv()) {
            return;
        }
        if (!downloadLaeuft.compareAndSet(false, true)) {
            return;
        }
        logger.info("Modell fehlt, Auto-Download läuft im Hintergrund (Proxy-Start wartet nicht).");
        try {
            pool.submit(this::ladeImHintergrund);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            downloadLaeuft.set(false);
            logger.debug("Modell-Auto-Download nicht gestartet (Verteilung bereits geschlossen).", e);
        }
    }

    private void ladeImHintergrund() {
        try {
            File modellDatei = new File(ordner, MODELL_NAME);
            File vocabDatei = new File(ordner, VOCAB_NAME);
            ladeFehlendesNach(modellDatei, vocabDatei,
                    !dateiVorhanden(modellDatei), !dateiVorhanden(vocabDatei));
            laden();
            if (aktiv()) {
                logger.info("Modell-Sync nach Auto-Download bereit, verteile an alle Bridges.");
                syncAnAlle();
            }
        } catch (Exception e) {
            logger.warn("Modell-Auto-Download fehlgeschlagen, Sync bleibt inaktiv.", e);
        } finally {
            downloadLaeuft.set(false);
        }
    }

    private boolean autoDownloadAktiv() {
        try {
            return konfiguration.chatfilterAutoDownload();
        } catch (Exception e) {
            return true;
        }
    }

    private boolean chatfilterAktiv() {
        try {
            return konfiguration.chatfilterAktiv();
        } catch (Exception e) {
            return false;
        }
    }

    private void ladeFehlendesNach(File modellDatei, File vocabDatei, boolean modellFehlt, boolean vocabFehlt) {
        if (vocabFehlt) {
            try {
                logger.info("Chatfilter-vocab fehlt, lade automatisch (ankekat1000/toxic-bert-german, CC-BY-NC-SA-4.0, nur nicht-kommerziell) ...");
                byte[] geladen = download.laden(logger, VOCAB_URL, 2_000_000L);
                if (geladen != null && !hashPruefen(geladen, VOCAB_HASH, false)) {
                    logger.error("vocab.txt SHA weicht ab (erwartet {}, erhalten {}, Upstream-Update möglich), Datei verworfen.",
                            VOCAB_HASH.substring(0, 16), hashBytes(geladen).substring(0, 16));
                    geladen = null;
                }
                if (geladen != null) {
                    schreibeAtomar(vocabDatei, geladen);
                    logger.info("vocab.txt automatisch geladen ({} KB).", geladen.length / 1024);
                }
            } catch (Exception e) {
                logger.warn("vocab.txt Auto-Download fehlgeschlagen (manuell via scripts/chatfilter-modell-laden.ps1 laden).", e);
            }
        }
        if (modellFehlt) {
            String url = "";
            try {
                url = konfiguration.chatfilterModellUrl();
            } catch (Exception e) {
                logger.debug("chatfilter.modell-url konnte nicht gelesen werden.", e);
            }
            if (url == null || url.isBlank()) {
                logger.warn("modell_int8.onnx fehlt: kein Auto-Download ohne chatfilter.modell-url. "
                        + "Einmalig Selbst-Export (siehe scripts/chatfilter-modell-laden.ps1) oder modell-url setzen. "
                        + "Bis dahin nur Wortliste + vocab.");
                return;
            }
            try {
                logger.info("Chatfilter-Modell fehlt, lade automatisch von konfigurierter URL ...");
                byte[] geladen = download.laden(logger, url.trim(), Protokoll.MAX_MODELL_DATEI_BYTES);
                if (geladen == null) {
                    return;
                }
                if (!hashPruefen(geladen, MODELL_HASH, true)) {
                    logger.error("modell_int8.onnx SHA stimmt nicht, Datei verworfen.");
                    return;
                }
                schreibeAtomar(modellDatei, geladen);
                logger.info("modell_int8.onnx automatisch geladen ({} MB).", geladen.length / 1024 / 1024);
            } catch (Exception e) {
                logger.warn("Modell Auto-Download fehlgeschlagen.", e);
            }
        }
    }

    private static final int MAX_UMLEITUNGEN = 3;

    private static byte[] ladeUrl(Logger log, String url, long maxBytes) {
        try {
            java.net.URI ziel = java.net.URI.create(url);
            if (!"https".equalsIgnoreCase(ziel.getScheme())) {
                log.warn("Modell-Download abgelehnt (nur https erlaubt): {}.", hostVon(url));
                return null;
            }
            String ursprungsHost = ziel.getHost();
            var client = java.net.http.HttpClient.newBuilder()
                    .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                    .connectTimeout(java.time.Duration.ofSeconds(20))
                    .build();
            for (int sprung = 0; sprung <= MAX_UMLEITUNGEN; sprung++) {
                var request = java.net.http.HttpRequest.newBuilder(ziel)
                        .timeout(java.time.Duration.ofMinutes(10))
                        .GET()
                        .build();
                var response = client.send(request,
                        java.net.http.HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                try (java.io.InputStream strom = response.body()) {
                    if (status >= 300 && status < 400) {
                        String zielKopf = response.headers().firstValue("Location").orElse("");
                        if (zielKopf.isBlank()) {
                            log.warn("Modell-Download verworfen (Umleitung ohne Ziel von {}).", hostVon(url));
                            return null;
                        }
                        java.net.URI neu = ziel.resolve(zielKopf);
                        if (!istErlaubtesZiel(neu, ursprungsHost)) {
                            log.warn("Modell-Download abgelehnt (fremder Umleitungs-Host {} von {}).",
                                    hostVon(String.valueOf(neu)), hostVon(url));
                            return null;
                        }
                        ziel = neu;
                        continue;
                    }
                    if (status < 200 || status >= 300) {
                        log.warn("Modell-Download fehlgeschlagen (HTTP {} von {}).", status, hostVon(url));
                        return null;
                    }
                    byte[] body = liesBegrenzt(strom, maxBytes);
                    if (body == null || body.length < 1) {
                        log.warn("Modell-Download verworfen (Größe über Limit {} Bytes oder leer, von {}).",
                                maxBytes, hostVon(url));
                        return null;
                    }
                    return body;
                }
            }
            log.warn("Modell-Download verworfen (zu viele Umleitungen von {}).", hostVon(url));
            return null;
        } catch (Exception e) {
            log.warn("Modell-Download fehlgeschlagen ({}).", hostVon(url), e);
            return null;
        }
    }

    /**
     * Umleitungen bleiben beim Ursprungs-Host oder auf Hugging-Face-CDNs
     * (*.huggingface.co, *.hf.co). Andere Hosts werden nicht angefragt.
     */
    static boolean istErlaubtesZiel(java.net.URI ziel, String ursprungsHost) {
        if (ziel == null || !"https".equalsIgnoreCase(ziel.getScheme())) {
            return false;
        }
        String host = ziel.getHost();
        if (host == null || host.isBlank()) {
            return false;
        }
        if (host.equalsIgnoreCase(ursprungsHost)) {
            return true;
        }
        String klein = host.toLowerCase(java.util.Locale.ROOT);
        return klein.equals("huggingface.co") || klein.endsWith(".huggingface.co") || klein.endsWith(".hf.co");
    }

    private static byte[] liesBegrenzt(java.io.InputStream strom, long maxBytes) throws java.io.IOException {
        long limit = Math.min(maxBytes, Integer.MAX_VALUE - 8L);
        java.io.ByteArrayOutputStream puffer = new java.io.ByteArrayOutputStream();
        byte[] block = new byte[8192];
        long gesamt = 0;
        int gelesen;
        while ((gelesen = strom.read(block)) != -1) {
            gesamt += gelesen;
            if (gesamt > limit) {
                return null;
            }
            puffer.write(block, 0, gelesen);
        }
        return puffer.toByteArray();
    }

    private static String hostVon(String url) {
        try {
            String host = java.net.URI.create(url).getHost();
            return host == null ? "unbekannt" : host;
        } catch (Exception e) {
            return "unbekannt";
        }
    }

    private static boolean hashPruefen(byte[] daten, String erwartet, boolean strikt) {
        try {
            String hash = hashBytes(daten);
            return erwartet.equalsIgnoreCase(hash);
        } catch (Exception e) {
            return false;
        }
    }

    private static void schreibeAtomar(File ziel, byte[] daten) throws Exception {
        File tmp = new File(ziel.getParentFile(), ziel.getName() + ".part");
        Files.write(tmp.toPath(), daten);
        try {
            Files.move(tmp.toPath(), ziel.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            Files.move(tmp.toPath(), ziel.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean dateiVorhanden(File datei) {
        return datei != null && datei.isFile() && datei.length() > 0
                && datei.length() <= Protokoll.MAX_MODELL_DATEI_BYTES;
    }

    private static byte[] leseStill(File datei) {
        try {
            if (datei == null || !datei.isFile()) {
                return null;
            }
            if (datei.length() < 1 || datei.length() > Protokoll.MAX_MODELL_DATEI_BYTES) {
                return null;
            }
            return Files.readAllBytes(datei.toPath());
        } catch (Exception e) {
            return null;
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

    public boolean aktiv() {
        try {
            if (!konfiguration.chatfilterModellSync()) {
                return false;
            }
        } catch (Exception e) {
            return false;
        }
        return modellBytes != null && vocabBytes != null;
    }

    public void standEmpfangen(RegisteredServer server, String modellStand, String vocabStand) {
        if (server == null) {
            return;
        }
        if (!aktiv()) {
            return;
        }
        String standModell = modellStand == null ? "" : modellStand.trim().toLowerCase(java.util.Locale.ROOT);
        String standVocab = vocabStand == null ? "" : vocabStand.trim().toLowerCase(java.util.Locale.ROOT);
        if (modellHash.equals(standModell) && vocabHash.equals(standVocab)) {
            logger.debug("Modell-Stand aktuell ({}).", serverName(server));
            return;
        }
        logger.info("Modell-Sync an '{}' (Proxy-Modell neuer/abweichend).", serverName(server));
        syncAn(server);
    }

    public void quittungEmpfangen(RegisteredServer server, Protokoll.ModellQuittung quittung) {
        if (quittung == null) {
            return;
        }
        if (quittung.erfolg()) {
            logger.info("Modell-Sync erfolgreich ('{}' auf {}).",
                    quittung.dateiname(), server == null ? "?" : serverName(server));
        } else {
            logger.warn("Modell-Sync abgelehnt ('{}' auf {}).",
                    quittung.dateiname(), server == null ? "?" : serverName(server));
        }
    }

    public void syncAnAlle() {
        if (!aktiv()) {
            logger.warn("Modell-Sync angefordert, aber inaktiv (modell-sync=false oder kein Modell).");
            return;
        }
        for (RegisteredServer server : proxy.getAllServers()) {
            syncAn(server);
        }
        logger.info("Modell-Sync an alle Bridges angestoßen.");
    }

    public void syncAn(RegisteredServer server) {
        if (server == null || !aktiv()) {
            return;
        }
        byte[] modell = modellBytes;
        byte[] vocab = vocabBytes;
        if (modell == null || vocab == null) {
            return;
        }
        String name = serverName(server);
        if (!laufend.add(name)) {
            logger.debug("Modell-Sync an '{}' läuft bereits.", name);
            return;
        }
        String hashModell = modellHash;
        String hashVocab = vocabHash;
        try {
            pool.submit(() -> {
                try {
                    sendeDatei(server, MODELL_NAME, modell, hashModell);
                    sendeDatei(server, VOCAB_NAME, vocab, hashVocab);
                    logger.info("Modell-Sync an '{}' gesendet (Quittung ausstehend).", name);
                } catch (Exception e) {
                    logger.warn("Modell-Sync an '{}' fehlgeschlagen.", name, e);
                } finally {
                    laufend.remove(name);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            laufend.remove(name);
            logger.debug("Modell-Sync an '{}' nicht gestartet (Verteilung bereits geschlossen).", name, e);
        }
    }

    private void sendeDatei(RegisteredServer server, String name, byte[] daten, String hash)
            throws Exception {
        int anzahl = (daten.length + CHUNK_GROESSE - 1) / CHUNK_GROESSE;
        versand.sende(server, Protokoll.modellMeta(
                new Protokoll.ModellMeta(name, daten.length, hash, CHUNK_GROESSE, anzahl)));
        for (int i = 0; i < anzahl; i++) {
            int von = i * CHUNK_GROESSE;
            int bis = Math.min(daten.length, von + CHUNK_GROESSE);
            byte[] teil = java.util.Arrays.copyOfRange(daten, von, bis);
            versand.sende(server, Protokoll.modellBlock(
                    new Protokoll.ModellBlock(name, i, teil)));
            try {
                Thread.sleep(PAUSE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Sync unterbrochen.", e);
            }
        }
        versand.sende(server, Protokoll.modellFertig(
                new Protokoll.ModellFertig(name, hash, daten.length)));
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
            logger.debug("Modell-Sync-Pool konnte nicht beendet werden.", e);
        }
    }
}
