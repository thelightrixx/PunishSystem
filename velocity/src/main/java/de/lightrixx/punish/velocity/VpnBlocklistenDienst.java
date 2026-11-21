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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

final class VpnBlocklistenDienst implements AutoCloseable {

    static final String PROTON_ROUTING =
            "https://raw.githubusercontent.com/tn3w/ProtonVPN-IPs/master/protonvpn_entry_ip_ranges.txt";
    static final List<String> STANDARD_QUELLEN = Stream.concat(Stream.of(
            "https://raw.githubusercontent.com/X4BNet/lists_vpn/main/output/vpn/ipv4.txt",
            "https://raw.githubusercontent.com/X4BNet/lists_vpn/main/output/vpn/ipv6.txt",
            "https://raw.githubusercontent.com/aaronburt/vpn-block-list/main/lists/vpn.txt",
            PROTON_ROUTING), Stream.of("airvpn", "ivpn", "mullvad", "nordvpn", "ovpn", "pia",
                    "protonvpn", "riseupvpn", "surfshark", "windscribe")
                    .map(name -> "https://raw.githubusercontent.com/Joe12387/open-source-vpn-ip-lists/master/"
                            + name + ".txt")).toList();
    static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_ZEILEN = 250_000;

    @FunctionalInterface
    interface Download {
        byte[] laden(URI quelle) throws IOException;
    }

    enum Modus { STRENG, AUSGEWOGEN }

    enum Art { VPN, ROUTING }

    enum Aktion { DEAKTIVIERT, OHNE_LISTEN, FREI, FREIGEGEBEN, MELDEN, SPERREN }

    record Quelle(URI url, String name, Art art) {
    }

    record Treffer(Quelle quelle, String cidr) {
    }

    record Ergebnis(Aktion aktion, List<Treffer> treffer, Optional<String> freigabe) {
    }

    record QuellenStatus(Quelle quelle, int eintraege, long letzterDownload, long letzteAenderung,
                         long fehlerSeit, String fehler) {
    }

    record Status(boolean aktiv, Modus modus, int bereit, long naechsterDownload, List<QuellenStatus> quellen) {
    }

    private record Einstellungen(boolean aktiv, Modus modus, List<Quelle> quellen, BereichsIndex freigaben,
                                  long intervallMillis) {
    }

    private record Stand(Einstellungen einstellungen, Map<URI, Daten> quellen,
                         Map<URI, Fehler> fehler, Index index) {
    }

    private record Fehler(long seit, String text) {
    }

    private record Daten(List<Bereich> bereiche, BereichsIndex index, String hash, long download, long aenderung) {
    }

    private record Netz(int bits, int prefix, BigInteger von) {
    }

    private record Bereich(int bits, BigInteger von, BigInteger bis) {
        int prefix() {
            return bits - bis.subtract(von).bitLength();
        }

        String cidr() {
            byte[] roh = von.toByteArray();
            var adresse = new byte[bits / 8];
            int anzahl = Math.min(roh.length, adresse.length);
            System.arraycopy(roh, roh.length - anzahl, adresse, adresse.length - anzahl, anzahl);
            try {
                return InetAddress.getByAddress(adresse).getHostAddress() + "/" + prefix();
            } catch (java.net.UnknownHostException e) {
                throw new IllegalStateException("Ungültige interne IP-Länge.", e);
            }
        }
    }

    private record BereichsIndex(Index schnell, Map<Netz, Bereich> netze) {
        static BereichsIndex aus(List<Bereich> bereiche) {
            var netze = new LinkedHashMap<Netz, Bereich>();
            for (var bereich : bereiche) {
                netze.put(new Netz(bereich.bits(), bereich.prefix(), bereich.von()), bereich);
            }
            return new BereichsIndex(Index.aus(bereiche), Map.copyOf(netze));
        }

        Optional<Bereich> finde(byte[] adresse) {
            if (!schnell.enthaelt(adresse)) {
                return Optional.empty();
            }
            int bits = adresse.length * 8;
            var wert = new BigInteger(1, adresse);
            for (int prefix = bits; prefix > 0; prefix--) {
                int rest = bits - prefix;
                var bereich = netze.get(new Netz(bits, prefix, wert.shiftRight(rest).shiftLeft(rest)));
                if (bereich != null) {
                    return Optional.of(bereich);
                }
            }
            return Optional.empty();
        }
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
    private final LongSupplier uhr;
    private final AtomicBoolean laeuft = new AtomicBoolean();
    private volatile Stand stand = new Stand(new Einstellungen(false, Modus.STRENG, List.of(),
            BereichsIndex.aus(List.of()), 0L), Map.of(), Map.of(), Index.LEER);
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
        this(config, logger, datenOrdner, download, pool, System::currentTimeMillis);
    }

    VpnBlocklistenDienst(VelocityKonfiguration config, Logger logger, Path datenOrdner,
                        Download download, ScheduledExecutorService pool, LongSupplier uhr) {
        this.config = Objects.requireNonNull(config, "Die Konfiguration darf nicht null sein.");
        this.logger = Objects.requireNonNull(logger, "Der Logger darf nicht null sein.");
        this.ordner = Objects.requireNonNull(datenOrdner, "Der Datenordner darf nicht null sein.")
                .resolve("vpn-blocklisten");
        this.download = Objects.requireNonNull(download, "Der Download darf nicht null sein.");
        this.pool = Objects.requireNonNull(pool, "Der Pool darf nicht null sein.");
        this.uhr = Objects.requireNonNull(uhr, "Die Uhr darf nicht null sein.");
    }

    synchronized void neuLaden() {
        if (geschlossen) {
            return;
        }
        boolean aktiv = config.vpnSchutzAktiv();
        Modus modus = switch (Objects.requireNonNullElse(config.vpnSchutzModus(), "streng")) {
            case "streng" -> Modus.STRENG;
            case "ausgewogen" -> Modus.AUSGEWOGEN;
            default -> throw new IllegalArgumentException("vpn-schutz.modus muss streng oder ausgewogen sein.");
        };
        var routing = config.vpnSchutzRoutingQuellen().stream().map(VpnBlocklistenDienst::pruefeQuelle)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        var quellen = aktiv ? config.vpnSchutzQuellen().stream()
                .map(VpnBlocklistenDienst::pruefeQuelle).distinct().map(url -> quelle(url, routing)).toList()
                : List.<Quelle>of();
        if (quellen.size() > 16) {
            throw new IllegalArgumentException("vpn-schutz.quellen darf höchstens 16 URLs enthalten.");
        }
        var freigaben = new ArrayList<Bereich>();
        if (aktiv) {
            for (String eintrag : config.vpnSchutzFreigaben()) {
                freigaben.add(parseBereich(eintrag));
            }
        }
        var einstellungen = new Einstellungen(aktiv, modus, quellen, BereichsIndex.aus(freigaben),
                TimeUnit.MINUTES.toMillis(config.vpnSchutzIntervallMinuten()));
        var geladen = new LinkedHashMap<URI, Daten>();
        var fehler = new LinkedHashMap<URI, Fehler>();
        for (var eintrag : quellen) {
            URI quelle = eintrag.url();
            var bisher = stand.quellen().get(quelle);
            if (bisher != null) {
                geladen.put(quelle, bisher);
                if (stand.fehler().containsKey(quelle)) {
                    fehler.put(quelle, stand.fehler().get(quelle));
                }
                continue;
            }
            Path datei = cacheDatei(quelle);
            try {
                if (Files.isRegularFile(datei)) {
                    if (Files.size(datei) > MAX_BYTES) {
                        throw new IOException("Lokale Blockliste überschreitet 8 MiB.");
                    }
                    byte[] daten = Files.readAllBytes(datei);
                    var bereiche = parseListe(daten);
                    String hash = hash(daten);
                    long[] zeiten = leseZeiten(quelle, hash);
                    geladen.put(quelle, new Daten(bereiche, BereichsIndex.aus(bereiche), hash, zeiten[0], zeiten[1]));
                }
            } catch (IOException | IllegalArgumentException e) {
                logger.warn("Lokale VPN-Blockliste für {} ist unlesbar/ungültig und wird übersprungen.",
                        quelle, e);
                fehler.put(quelle, new Fehler(uhr.getAsLong(), Objects.requireNonNullElse(e.getMessage(), e.toString())));
            }
        }
        stand = neuerStand(einstellungen, geladen, fehler);
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
        return pruefe(ip).aktion() == Aktion.SPERREN;
    }

    Ergebnis pruefe(String ip) {
        var aktuell = stand;
        byte[] adresse = adresse(ip);
        if (!aktuell.einstellungen().aktiv()) {
            return new Ergebnis(Aktion.DEAKTIVIERT, List.of(), Optional.empty());
        }
        var freigabe = aktuell.einstellungen().freigaben().finde(adresse).map(Bereich::cidr);
        var treffer = new ArrayList<Treffer>();
        if (aktuell.index().enthaelt(adresse)) {
            for (var quelle : aktuell.einstellungen().quellen()) {
                var daten = aktuell.quellen().get(quelle.url());
                if (daten != null) {
                    daten.index().finde(adresse).ifPresent(bereich -> treffer.add(new Treffer(quelle, bereich.cidr())));
                }
            }
        }
        Aktion aktion = freigabe.isPresent() ? Aktion.FREIGEGEBEN
                : aktuell.quellen().isEmpty() ? Aktion.OHNE_LISTEN : Aktion.FREI;
        if (freigabe.isEmpty() && !treffer.isEmpty()) {
            aktion = aktuell.einstellungen().modus() == Modus.STRENG
                    || treffer.stream().anyMatch(t -> t.quelle().art() == Art.VPN) ? Aktion.SPERREN : Aktion.MELDEN;
        }
        return new Ergebnis(aktion, List.copyOf(treffer), freigabe);
    }

    Status status() {
        var aktuell = stand;
        var quellen = aktuell.einstellungen().quellen().stream().map(quelle -> {
            var daten = aktuell.quellen().get(quelle.url());
            var fehler = aktuell.fehler().get(quelle.url());
            return new QuellenStatus(quelle, daten == null ? 0 : daten.bereiche().size(),
                    daten == null ? 0L : daten.download(), daten == null ? 0L : daten.aenderung(),
                    fehler == null ? 0L : fehler.seit(), fehler == null ? "" : fehler.text());
        }).toList();
        return new Status(aktuell.einstellungen().aktiv(), aktuell.einstellungen().modus(),
                aktuell.quellen().size(), naechsteAktualisierung, quellen);
    }

    private static Quelle quelle(URI url, Set<URI> routing) {
        String pfad = url.getPath();
        String name = url.toASCIIString();
        if (url.toString().equalsIgnoreCase(PROTON_ROUTING)) {
            name = "Proton-Routing";
        } else if (pfad.startsWith("/X4BNet/lists_vpn/")) {
            name = "X4BNet " + pfad.substring(pfad.lastIndexOf('/') + 1);
        } else if (pfad.startsWith("/aaronburt/vpn-block-list/")) {
            name = "aaronburt VPN";
        } else if (pfad.startsWith("/Joe12387/open-source-vpn-ip-lists/")) {
            name = "Anbieterliste " + pfad.substring(pfad.lastIndexOf('/') + 1).replace(".txt", "");
        }
        return new Quelle(url, name, url.toString().equalsIgnoreCase(PROTON_ROUTING) || routing.contains(url)
                ? Art.ROUTING : Art.VPN);
    }

    private void wartung() {
        if (uhr.getAsLong() < naechsteAktualisierung) {
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
            for (var eintrag : einstellungen.quellen()) {
                URI quelle = eintrag.url();
                synchronized (this) {
                    if (geschlossen || stand.einstellungen() != einstellungen) {
                        return;
                    }
                }
                try {
                    byte[] daten = download.laden(quelle);
                    var bereiche = parseListe(daten);
                    var index = BereichsIndex.aus(bereiche);
                    String hash = hash(daten);
                    synchronized (this) {
                        if (geschlossen || stand.einstellungen() != einstellungen) {
                            return;
                        }
                        var neu = new LinkedHashMap<>(stand.quellen());
                        var bisher = neu.get(quelle);
                        long jetzt = uhr.getAsLong();
                        var neuerWert = new Daten(bereiche, index, hash, jetzt,
                                bisher != null && bisher.hash().equals(hash) ? bisher.aenderung() : jetzt);
                        neu.put(quelle, neuerWert);
                        var fehler = new LinkedHashMap<>(stand.fehler());
                        fehler.remove(quelle);
                        stand = neuerStand(einstellungen, neu, fehler);
                        try {
                            speichern(quelle, daten);
                            schreibeDatei(metaDatei(quelle), (hash + "\n" + neuerWert.download() + "\n"
                                    + neuerWert.aenderung() + "\n").getBytes(StandardCharsets.UTF_8));
                        } catch (IOException e) {
                            logger.warn("VPN-Blockliste für {} ist im RAM aktiv, konnte aber nicht lokal gespeichert werden.",
                                    quelle, e);
                        }
                    }
                    erfolgreich++;
                } catch (IOException | IllegalArgumentException e) {
                    logger.warn("VPN-Blockliste für {} nicht aktualisiert; letzter gültiger Stand bleibt aktiv.",
                            quelle, e);
                    synchronized (this) {
                        if (!geschlossen && stand.einstellungen() == einstellungen) {
                            var fehler = new LinkedHashMap<>(stand.fehler());
                            fehler.put(quelle, new Fehler(uhr.getAsLong(),
                                    Objects.requireNonNullElse(e.getMessage(), e.toString())));
                            stand = new Stand(einstellungen, stand.quellen(), Map.copyOf(fehler), stand.index());
                        }
                    }
                }
            }
            synchronized (this) {
                if (!geschlossen && stand.einstellungen() == einstellungen) {
                    boolean vollstaendig = erfolgreich == einstellungen.quellen().size();
                    long wartezeit = vollstaendig ? einstellungen.intervallMillis()
                            : Math.min(einstellungen.intervallMillis(), TimeUnit.MINUTES.toMillis(5L));
                    naechsteAktualisierung = uhr.getAsLong() + wartezeit;
                    logger.info("VPN-Blocklisten: {}/{} Quellen erfolgreich heruntergeladen/geprüft, {}/{} Quellen aktiv.",
                            erfolgreich, einstellungen.quellen().size(), stand.quellen().size(),
                            einstellungen.quellen().size());
                }
            }
        } finally {
            laeuft.set(false);
        }
    }

    private static Stand neuerStand(Einstellungen einstellungen, Map<URI, Daten> quellen, Map<URI, Fehler> fehler) {
        var bereiche = new ArrayList<Bereich>();
        quellen.values().forEach(daten -> bereiche.addAll(daten.bereiche()));
        return new Stand(einstellungen, Map.copyOf(quellen), Map.copyOf(fehler), Index.aus(bereiche));
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
        schreibeDatei(cacheDatei(quelle), daten);
    }

    private Path metaDatei(URI quelle) {
        return cacheDatei(quelle).resolveSibling(cacheDatei(quelle).getFileName() + ".meta");
    }

    private long[] leseZeiten(URI quelle, String hash) {
        Path datei = metaDatei(quelle);
        try {
            if (Files.isRegularFile(datei)) {
                if (Files.size(datei) > 512L) {
                    throw new IOException("VPN-Metadaten sind zu groß.");
                }
                var zeilen = Files.readAllLines(datei, StandardCharsets.UTF_8);
                if (zeilen.size() != 3 || !zeilen.getFirst().equals(hash)) {
                    throw new IOException("VPN-Metadaten passen nicht zum Listeninhalt.");
                }
                long download = Long.parseLong(zeilen.get(1));
                long aenderung = Long.parseLong(zeilen.get(2));
                if (download < 0L || aenderung < 0L || aenderung > download || download > uhr.getAsLong()) {
                    throw new IOException("Ungültige Zeitangaben in VPN-Metadaten.");
                }
                return new long[]{download, aenderung};
            }
        } catch (IOException | NumberFormatException e) {
            logger.warn("VPN-Zeitangaben für {} unlesbar; Inhalt bleibt nutzbar, Zeitangaben unbekannt.", quelle, e);
        }
        return new long[]{0L, 0L};
    }

    private static String hash(byte[] daten) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(daten));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 ist nicht verfügbar.", e);
        }
    }

    private void schreibeDatei(Path ziel, byte[] daten) throws IOException {
        Files.createDirectories(ordner);
        Path temp = Files.createTempFile(ordner, "vpn-", ".tmp");
        try {
            Files.write(temp, daten);
            try {
                Files.move(temp, ziel, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, ziel, StandardCopyOption.REPLACE_EXISTING);
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
