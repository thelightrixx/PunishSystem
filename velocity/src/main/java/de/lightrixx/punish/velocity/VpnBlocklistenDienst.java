package de.lightrixx.punish.velocity;

import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class VpnBlocklistenDienst implements AutoCloseable {

    static final List<String> STANDARD_QUELLEN = List.of(
            "https://raw.githubusercontent.com/X4BNet/lists_vpn/main/output/vpn/ipv4.txt",
            "https://raw.githubusercontent.com/X4BNet/lists_vpn/main/output/vpn/ipv6.txt",
            "https://raw.githubusercontent.com/aaronburt/vpn-block-list/main/lists/vpn.txt");
    static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_ZEILEN = 250_000;

    @FunctionalInterface
    interface Download {
        byte[] laden(URI quelle) throws IOException;
    }

    private record Einstellungen(boolean aktiv, List<URI> quellen, Index freigaben,
                                  long intervallMillis) {
    }

    private record Stand(Einstellungen einstellungen, Map<URI, List<Bereich>> quellen, Index index) {
    }

    private record Bereich(int bits, BigInteger von, BigInteger bis) {
    }

    private record Index(List<Bereich> ipv4, List<Bereich> ipv6) {
        private static final Index LEER = new Index(List.of(), List.of());

        static Index aus(List<Bereich> bereiche) {
            return new Index(verschmelze(bereiche, 32), verschmelze(bereiche, 128));
        }

        private static List<Bereich> verschmelze(List<Bereich> bereiche, int bits) {
            var sortiert = bereiche.stream().filter(b -> b.bits() == bits)
                    .sorted(Comparator.comparing(Bereich::von)).toList();
            var ergebnis = new ArrayList<Bereich>();
            for (var bereich : sortiert) {
                if (ergebnis.isEmpty()) {
                    ergebnis.add(bereich);
                    continue;
                }
                var letzter = ergebnis.getLast();
                if (bereich.von().compareTo(letzter.bis().add(BigInteger.ONE)) <= 0) {
                    ergebnis.set(ergebnis.size() - 1,
                            new Bereich(bits, letzter.von(), letzter.bis().max(bereich.bis())));
                } else {
                    ergebnis.add(bereich);
                }
            }
            return List.copyOf(ergebnis);
        }

        boolean enthaelt(byte[] adresse) {
            var bereiche = adresse.length == 4 ? ipv4 : ipv6;
            var wert = new BigInteger(1, adresse);
            int links = 0;
            int rechts = bereiche.size() - 1;
            while (links <= rechts) {
                int mitte = (links + rechts) >>> 1;
                var bereich = bereiche.get(mitte);
                if (wert.compareTo(bereich.von()) < 0) {
                    rechts = mitte - 1;
                } else if (wert.compareTo(bereich.bis()) > 0) {
                    links = mitte + 1;
                } else {
                    return true;
                }
            }
            return false;
        }
    }

    private final VelocityKonfiguration config;
    private final Logger logger;
    private final Path ordner;
    private final Download download;
    private final ScheduledExecutorService pool;
    private final AtomicBoolean laeuft = new AtomicBoolean();
    private volatile Stand stand = new Stand(new Einstellungen(false, List.of(), Index.LEER, 0L),
            Map.of(), Index.LEER);
    private volatile long naechsteAktualisierung;
    private boolean geschlossen;
    private ScheduledFuture<?> wartung;

    VpnBlocklistenDienst(VelocityKonfiguration config, Logger logger, Path datenOrdner) {
        this(config, logger, datenOrdner, VpnBlocklistenDienst::ladeQuelle,
                Executors.newSingleThreadScheduledExecutor(auftrag -> {
                    var thread = new Thread(auftrag, "punish-vpn-blocklisten");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    VpnBlocklistenDienst(VelocityKonfiguration config, Logger logger, Path datenOrdner,
                        Download download, ScheduledExecutorService pool) {
        this.config = Objects.requireNonNull(config, "Die Konfiguration darf nicht null sein.");
        this.logger = Objects.requireNonNull(logger, "Der Logger darf nicht null sein.");
        this.ordner = Objects.requireNonNull(datenOrdner, "Der Datenordner darf nicht null sein.")
                .resolve("vpn-blocklisten");
        this.download = Objects.requireNonNull(download, "Der Download darf nicht null sein.");
        this.pool = Objects.requireNonNull(pool, "Der Pool darf nicht null sein.");
    }

    synchronized void neuLaden() {
        if (geschlossen) {
            return;
        }
        boolean aktiv = config.vpnSchutzAktiv();
        var quellen = aktiv ? config.vpnSchutzQuellen().stream()
                .map(VpnBlocklistenDienst::pruefeQuelle).distinct().toList() : List.<URI>of();
        if (quellen.size() > 16) {
            throw new IllegalArgumentException("vpn-schutz.quellen darf höchstens 16 URLs enthalten.");
        }
        var freigaben = new ArrayList<Bereich>();
        if (aktiv) {
            for (String eintrag : config.vpnSchutzFreigaben()) {
                freigaben.add(parseBereich(eintrag));
            }
        }
        var einstellungen = new Einstellungen(aktiv, quellen, Index.aus(freigaben),
                TimeUnit.MINUTES.toMillis(config.vpnSchutzIntervallMinuten()));
        var geladen = new LinkedHashMap<URI, List<Bereich>>();
        for (URI quelle : quellen) {
            var bisher = stand.quellen().get(quelle);
            if (bisher != null) {
                geladen.put(quelle, bisher);
                continue;
            }
            Path datei = cacheDatei(quelle);
            try {
                if (Files.isRegularFile(datei)) {
                    if (Files.size(datei) > MAX_BYTES) {
                        throw new IOException("Lokale Blockliste überschreitet 8 MiB.");
                    }
                    geladen.put(quelle, parseListe(Files.readAllBytes(datei)));
                }
            } catch (IOException | IllegalArgumentException e) {
                logger.warn("Lokale VPN-Blockliste für {} ist unlesbar/ungültig und wird übersprungen.",
                        quelle, e);
            }
        }
        stand = neuerStand(einstellungen, geladen);
        naechsteAktualisierung = 0L;
        if (wartung != null) {
            wartung.cancel(false);
        }
        if (aktiv) {
            logger.info("VPN-Schutz: {}/{} Quellen lokal bereit, {} IPv4-/{} IPv6-Bereiche.",
                    geladen.size(), quellen.size(), stand.index().ipv4().size(), stand.index().ipv6().size());
            if (geladen.isEmpty()) {
                logger.warn("VPN-Schutz ohne gültige Listen: Logins bleiben bis zum Download erlaubt.");
            }
            wartung = pool.scheduleWithFixedDelay(this::wartung, 0L, 1L, TimeUnit.MINUTES);
        } else {
            logger.info("VPN-Schutz deaktiviert.");
        }
    }

    boolean gesperrt(String ip) {
        var aktuell = stand;
        if (!aktuell.einstellungen().aktiv() || aktuell.quellen().isEmpty()) {
            return false;
        }
        byte[] adresse = adresse(ip);
        return !aktuell.einstellungen().freigaben().enthaelt(adresse)
                && aktuell.index().enthaelt(adresse);
    }

    private void wartung() {
        if (System.currentTimeMillis() < naechsteAktualisierung) {
            return;
        }
        try {
            aktualisieren();
        } catch (RuntimeException e) {
            logger.warn("VPN-Blocklisten konnten nicht aktualisiert werden; letzter gültiger Stand bleibt aktiv.", e);
        }
    }

    void aktualisieren() {
        if (!laeuft.compareAndSet(false, true)) {
            return;
        }
        try {
            var einstellungen = stand.einstellungen();
            if (!einstellungen.aktiv()) {
                return;
            }
            int erfolgreich = 0;
            for (URI quelle : einstellungen.quellen()) {
                synchronized (this) {
                    if (geschlossen || stand.einstellungen() != einstellungen) {
                        return;
                    }
                }
                try {
                    byte[] daten = download.laden(quelle);
                    var bereiche = parseListe(daten);
                    synchronized (this) {
                        if (geschlossen || stand.einstellungen() != einstellungen) {
                            return;
                        }
                        var neu = new LinkedHashMap<>(stand.quellen());
                        neu.put(quelle, bereiche);
                        stand = neuerStand(einstellungen, neu);
                        try {
                            speichern(quelle, daten);
                        } catch (IOException e) {
                            logger.warn("VPN-Blockliste für {} ist im RAM aktiv, konnte aber nicht lokal gespeichert werden.",
                                    quelle, e);
                        }
                    }
                    erfolgreich++;
                } catch (IOException | IllegalArgumentException e) {
                    logger.warn("VPN-Blockliste für {} nicht aktualisiert; letzter gültiger Stand bleibt aktiv.",
                            quelle, e);
                }
            }
            synchronized (this) {
                if (!geschlossen && stand.einstellungen() == einstellungen) {
                    boolean vollstaendig = erfolgreich == einstellungen.quellen().size();
                    long wartezeit = vollstaendig ? einstellungen.intervallMillis()
                            : Math.min(einstellungen.intervallMillis(), TimeUnit.MINUTES.toMillis(5L));
                    naechsteAktualisierung = System.currentTimeMillis() + wartezeit;
                    logger.info("VPN-Blocklisten: {}/{} Quellen aktualisiert, {}/{} Quellen aktiv.",
                            erfolgreich, einstellungen.quellen().size(), stand.quellen().size(),
                            einstellungen.quellen().size());
                }
            }
        } finally {
            laeuft.set(false);
        }
    }

    private static Stand neuerStand(Einstellungen einstellungen, Map<URI, List<Bereich>> quellen) {
        var bereiche = new ArrayList<Bereich>();
        quellen.values().forEach(bereiche::addAll);
        return new Stand(einstellungen, Map.copyOf(quellen), Index.aus(bereiche));
    }

    Path cacheDatei(URI quelle) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(quelle.toASCIIString().getBytes(StandardCharsets.UTF_8));
            return ordner.resolve(HexFormat.of().formatHex(hash) + ".txt");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 ist nicht verfügbar.", e);
        }
    }

    private void speichern(URI quelle, byte[] daten) throws IOException {
        Files.createDirectories(ordner);
        Path temp = Files.createTempFile(ordner, "vpn-", ".tmp");
        try {
            Files.write(temp, daten);
            try {
                Files.move(temp, cacheDatei(quelle), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, cacheDatei(quelle), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    static URI pruefeQuelle(String url) {
        var quelle = URI.create(Objects.requireNonNull(url, "Die Quellen-URL darf nicht null sein.").trim());
        if (!"https".equalsIgnoreCase(quelle.getScheme())
                || !"raw.githubusercontent.com".equalsIgnoreCase(quelle.getHost())
                || quelle.getUserInfo() != null || quelle.getQuery() != null || quelle.getFragment() != null
                || (quelle.getPort() != -1 && quelle.getPort() != 443)
                || quelle.getPath() == null || quelle.getPath().split("/").length < 5) {
            throw new IllegalArgumentException("VPN-Quellen müssen direkte HTTPS-URLs von raw.githubusercontent.com sein.");
        }
        return quelle;
    }

    private static byte[] ladeQuelle(URI quelle) throws IOException {
        var verbindung = (HttpURLConnection) quelle.toURL().openConnection();
        verbindung.setConnectTimeout(10_000);
        verbindung.setReadTimeout(15_000);
        verbindung.setInstanceFollowRedirects(false);
        verbindung.setRequestProperty("User-Agent", "PunishSystem-VPN-Blocklisten");
        long ende = System.nanoTime() + TimeUnit.SECONDS.toNanos(30L);
        try {
            int status = verbindung.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + status + " beim Laden der VPN-Blockliste.");
            }
            if (verbindung.getContentLengthLong() > MAX_BYTES) {
                throw new IOException("VPN-Blockliste überschreitet 8 MiB.");
            }
            try (var in = verbindung.getInputStream(); var out = new ByteArrayOutputStream()) {
                byte[] puffer = new byte[8192];
                int gelesen;
                while ((gelesen = in.read(puffer)) != -1) {
                    if (System.nanoTime() > ende || Thread.currentThread().isInterrupted()) {
                        throw new IOException("Zeitlimit/Abbruch beim Laden der VPN-Blockliste.");
                    }
                    if (out.size() + gelesen > MAX_BYTES) {
                        throw new IOException("VPN-Blockliste überschreitet 8 MiB.");
                    }
                    out.write(puffer, 0, gelesen);
                }
                return out.toByteArray();
            }
        } finally {
            verbindung.disconnect();
        }
    }

    private static List<Bereich> parseListe(byte[] daten) throws IOException {
        if (daten.length > MAX_BYTES) {
            throw new IOException("VPN-Blockliste überschreitet 8 MiB.");
        }
        var ergebnis = new ArrayList<Bereich>();
        try (var reader = new BufferedReader(new StringReader(new String(daten, StandardCharsets.UTF_8)))) {
            String zeile;
            int nummer = 0;
            while ((zeile = reader.readLine()) != null) {
                if (++nummer > MAX_ZEILEN || zeile.length() > 512) {
                    throw new IOException("VPN-Blockliste enthält zu viele/zu lange Zeilen.");
                }
                if (nummer == 1 && zeile.startsWith("\uFEFF")) {
                    zeile = zeile.substring(1);
                }
                zeile = zeile.split("#", 2)[0].trim();
                if (!zeile.isEmpty()) {
                    try {
                        ergebnis.add(parseBereich(zeile));
                    } catch (IllegalArgumentException e) {
                        throw new IOException("Ungültiger IP-/CIDR-Eintrag in VPN-Blockliste, Zeile " + nummer + ".", e);
                    }
                }
            }
        }
        if (ergebnis.isEmpty()) {
            throw new IOException("VPN-Blockliste enthält keine IP-/CIDR-Einträge.");
        }
        return List.copyOf(ergebnis);
    }

    private static Bereich parseBereich(String eintrag) {
        String[] teile = Objects.requireNonNull(eintrag, "Der IP-/CIDR-Eintrag darf nicht null sein.")
                .trim().split("/", -1);
        if (teile.length > 2) {
            throw new IllegalArgumentException("Ungültiger CIDR-Eintrag.");
        }
        byte[] adresse = adresse(teile[0]);
        int bits = adresse.length * 8;
        int originalBits = teile[0].contains(":") ? 128 : 32;
        int prefix = originalBits;
        if (teile.length == 2) {
            if (!teile[1].matches("[0-9]{1,3}")) {
                throw new IllegalArgumentException("Ungültige CIDR-Präfixlänge.");
            }
            prefix = Integer.parseInt(teile[1]);
        }
        if (prefix <= 0 || prefix > originalBits) {
            throw new IllegalArgumentException("Ungültige CIDR-Präfixlänge; /0 ist nicht erlaubt.");
        }
        prefix -= originalBits - bits;
        if (prefix <= 0) {
            throw new IllegalArgumentException("IPv4-gemappter CIDR-Bereich ist zu groß.");
        }
        int rest = bits - prefix;
        var von = new BigInteger(1, adresse).shiftRight(rest).shiftLeft(rest);
        return new Bereich(bits, von, von.add(BigInteger.ONE.shiftLeft(rest).subtract(BigInteger.ONE)));
    }

    private static byte[] adresse(String ip) {
        Objects.requireNonNull(ip, "Die IP darf nicht null sein.");
        if (ip.length() > 64 || !ip.matches("[0-9a-fA-F:.]+")) {
            throw new IllegalArgumentException("Es sind nur IP-Literale ohne DNS-Auflösung erlaubt.");
        }
        if (!ip.contains(":")) {
            String[] oktette = ip.split("\\.", -1);
            if (oktette.length != 4) {
                throw new IllegalArgumentException("Ungültiges IPv4-Literal.");
            }
            for (String oktett : oktette) {
                if (!oktett.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(oktett) > 255) {
                    throw new IllegalArgumentException("Ungültiges IPv4-Literal.");
                }
            }
        }
        try {
            byte[] bytes = InetAddress.getByName(ip).getAddress();
            if (bytes.length == 16 && bytes[10] == (byte) 255 && bytes[11] == (byte) 255
                    && Arrays.equals(Arrays.copyOf(bytes, 10), new byte[10])) {
                return Arrays.copyOfRange(bytes, 12, 16);
            }
            return bytes;
        } catch (java.net.UnknownHostException e) {
            throw new IllegalArgumentException("Ungültiges IP-Literal.", e);
        }
    }

    @Override
    public synchronized void close() {
        geschlossen = true;
        if (wartung != null) {
            wartung.cancel(false);
        }
        pool.shutdownNow();
    }
}
