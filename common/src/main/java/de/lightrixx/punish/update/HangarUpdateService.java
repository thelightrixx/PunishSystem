package de.lightrixx.punish.update;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HangarUpdateService {

    private static final Logger LOG = LoggerFactory.getLogger(HangarUpdateService.class);

    public record Artefakt(String plattform, String dateiname, String url, String sha256) {
        public Artefakt {
            Objects.requireNonNull(plattform, "plattform");
            if (dateiname == null) {
                dateiname = "";
            }
            Objects.requireNonNull(url, "url");
        }
    }

    public record ReleaseInfo(String version, String channel, List<Artefakt> downloads) {
        public ReleaseInfo {
            Objects.requireNonNull(version, "version");
            if (channel == null) {
                channel = "";
            }
            downloads = downloads == null ? List.of() : List.copyOf(downloads);
        }
    }

    public interface HttpAbruf {
        String leseText(String url) throws Exception;

        byte[] ladeBytes(String url, long maxBytes) throws Exception;
    }

    private static final Pattern NAME_MUSTER =
            Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern VERSION_ANFANG =
            Pattern.compile("^[vV]?\\d+\\.\\d+.*", Pattern.DOTALL);
    private static final Pattern VERSION_STRENG =
            Pattern.compile("^[vV]?[0-9]+(\\.[0-9]+)+([-+][A-Za-z0-9]+(\\.[A-Za-z0-9]+)*)*$");
    private static final Pattern CHANNEL_MUSTER =
            Pattern.compile("\"channel\"\\s*:\\s*\\{[^}]*\"name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern DOWNLOAD_MUSTER =
            Pattern.compile("\"downloadUrl\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern HASH_MUSTER =
            Pattern.compile("\"(?:sha256Hash|hash)\"\\s*:\\s*\"([0-9a-fA-F]{64})\"");
    private static final Pattern PLATTFORM_MUSTER =
            Pattern.compile("\"platform\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern PLATTFORM_SCHLUESSEL_MUSTER =
            Pattern.compile("\"(PAPER|VELOCITY|WATERFALL|FOLIA)\"\\s*:\\s*\\{");

    private final String autor;
    private final String slug;
    private final String channel;
    private final HttpAbruf http;

    public HangarUpdateService(String autor, String slug, String channel, HttpAbruf http) {
        this.autor = requireText(autor, "autor");
        this.slug = requireText(slug, "slug");
        String kanal = channel == null || channel.isBlank() ? "Release" : channel.trim();
        this.channel = kanal;
        this.http = Objects.requireNonNull(http, "Der HTTP-Abruf darf nicht null sein.");
    }

    private static final int MAX_UMLEITUNGEN = 3;

    // Eine Instanz statt einer pro Aufruf: der HttpClient hängt sonst an GC/Cleaner
    // statt an einem definierten Lifecycle, und jeder Update-Check erzeugte einen
    // neuen Selektor-Thread.
    private static final HttpAbruf STANDARD_ABRUF = new HttpAbruf() {
        private final java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(20))
                .build();

        @Override
        public String leseText(String url) throws Exception {
            return new String(ladeBytes(url, 512L * 1024L), java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override
        public byte[] ladeBytes(String url, long maxBytes) throws Exception {
            java.net.URI ziel = java.net.URI.create(url);
            pruefeHttpsNachRedirect(ziel);
            for (int sprung = 0; sprung <= MAX_UMLEITUNGEN; sprung++) {
                var request = java.net.http.HttpRequest.newBuilder(ziel)
                        .timeout(Duration.ofMinutes(10))
                        .GET()
                        .build();
                var response = client.send(request,
                        java.net.http.HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                if (status >= 300 && status < 400) {
                    String zielKopf = response.headers().firstValue("Location").orElse("");
                    response.body().close();
                    if (zielKopf.isBlank()) {
                        throw new IllegalStateException("Umleitung ohne Ziel.");
                    }
                    java.net.URI neu = ziel.resolve(zielKopf);
                    pruefeHttpsNachRedirect(neu);
                    ziel = neu;
                    continue;
                }
                if (status < 200 || status >= 300) {
                    response.body().close();
                    throw new IllegalStateException("HTTP " + status);
                }
                byte[] body = leseBegrenzt(response.body(), maxBytes);
                if (body.length < 1) {
                    throw new IllegalStateException("Unerwartete Download-Größe.");
                }
                return body;
            }
            throw new IllegalStateException("Zu viele Umleitungen.");
        }

        private byte[] leseBegrenzt(java.io.InputStream in, long maxBytes) throws Exception {
            try (in) {
                java.io.ByteArrayOutputStream puff = new java.io.ByteArrayOutputStream();
                byte[] block = new byte[8192];
                long gesamt = 0;
                int gelesen;
                while ((gelesen = in.read(block)) > 0) {
                    gesamt += gelesen;
                    if (gesamt > maxBytes) {
                        throw new IllegalStateException("Unerwartete Download-Größe.");
                    }
                    puff.write(block, 0, gelesen);
                }
                return puff.toByteArray();
            }
        }
    };

    public static HttpAbruf standardAbruf() {
        return STANDARD_ABRUF;
    }

    public String latestReleaseUrl() {
        return "https://hangar.papermc.io/api/v1/projects/" + autor + "/" + slug
                + "/latestrelease?channel=" + urlKodiere(channel);
    }

    public ReleaseInfo pruefe() throws Exception {
        String roh = http.leseText(latestReleaseUrl());
        if (ersteVersionAusNamensfeldern(roh) != null) {
            return parseLatestRelease(roh, autor, slug, channel);
        }
        // Die Hangar-API liefert /latestrelease als text/plain (nur der Versionsname).
        // Die Details (Downloads, Hashes, Kanal) stehen im Versions-Objekt.
        String version = roh == null ? "" : roh.trim();
        if (!VERSION_STRENG.matcher(version).matches()) {
            throw new IllegalArgumentException("Hangar-Antwort ohne Version.");
        }
        return parseLatestRelease(http.leseText(versionsUrl(version)), autor, slug, channel);
    }

    public String versionsUrl(String version) {
        return "https://hangar.papermc.io/api/v1/projects/" + autor + "/" + slug
                + "/versions/" + urlKodiere(version);
    }

    public byte[] ladeArtefakt(Artefakt artefakt, long maxBytes) throws Exception {
        Objects.requireNonNull(artefakt, "artefakt");
        return http.ladeBytes(artefakt.url(), maxBytes);
    }

    public static ReleaseInfo parseLatestRelease(String json, String autor, String slug, String channel) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Leere Hangar-Antwort.");
        }
        String version = ersteVersionAusNamensfeldern(json);
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("Hangar-Antwort ohne Version.");
        }
        version = version.trim();
        if (!VERSION_STRENG.matcher(version).matches()) {
            throw new IllegalArgumentException("Hangar-Antwort ohne gueltige Version.");
        }
        String kanal = ersteGruppe(CHANNEL_MUSTER, json);
        if (kanal == null || kanal.isBlank()) {
            kanal = channel == null ? "" : channel;
        }
        List<Artefakt> artefakte = parseDownloads(json, autor, slug, version.trim());
        return new ReleaseInfo(version.trim(), kanal.trim(), artefakte);
    }

    static List<Artefakt> parseDownloads(String json, String autor, String slug, String version) {
        List<Artefakt> ergebnis = new ArrayList<>();
        Matcher downloads = DOWNLOAD_MUSTER.matcher(json);
        while (downloads.find()) {
            String url = downloads.group(1).trim();
            String vorher = json.substring(Math.max(0, downloads.start() - 800), downloads.start());
            String nachher = json.substring(downloads.end(), Math.min(json.length(), downloads.end() + 800));
            String plattform = letzteGruppe(PLATTFORM_SCHLUESSEL_MUSTER, vorher);
            if (plattform == null || plattform.isBlank()) {
                plattform = letzteGruppe(PLATTFORM_MUSTER, vorher);
            }
            if (plattform == null || plattform.isBlank()) {
                plattform = ersteGruppe(PLATTFORM_MUSTER, nachher);
            }
            String hash = letzteGruppe(HASH_MUSTER, vorher);
            if (hash == null || hash.isBlank()) {
                hash = ersteGruppe(HASH_MUSTER, nachher);
            }
            String dateiname = dateinameAus(url, plattform, version);
            ergebnis.add(new Artefakt(
                    plattform == null ? "" : plattform.toUpperCase(Locale.ROOT),
                    dateiname, url, hash == null ? "" : hash.toLowerCase(Locale.ROOT)));
        }
        if (ergebnis.isEmpty()) {
            for (String plattform : List.of("VELOCITY", "PAPER")) {
                ergebnis.add(new Artefakt(plattform,
                        "PunishSystem-" + version + "-" + plattform.toLowerCase(Locale.ROOT) + ".jar",
                        downloadUrlFuer(autor, slug, version, plattform), ""));
            }
        }
        return List.copyOf(ergebnis);
    }

    public static String downloadUrlFuer(String autor, String slug, String version, String plattform) {
        return "https://hangar.papermc.io/api/v1/projects/" + autor + "/" + slug
                + "/versions/" + urlKodiere(version) + "/" + urlKodiere(plattform) + "/download";
    }

    private static String dateinameAus(String url, String plattform, String version) {
        try {
            String pfad = java.net.URI.create(url).getPath();
            String name = pfad.substring(pfad.lastIndexOf('/') + 1);
            if (!name.isBlank()) {
                return name;
            }
        } catch (Exception e) {
            LOG.debug("Dateiname aus Download-URL nicht ableitbar, nutze Fallback.", e);
        }
        String p = plattform == null || plattform.isBlank() ? "server" : plattform.toLowerCase(Locale.ROOT);
        return "PunishSystem-" + version + "-" + p + ".jar";
    }

    private static String ersteGruppe(Pattern muster, String text) {
        Matcher m = muster.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Liefert das erste "name"-Feld, das nach einer Version aussieht (beginnt
     * mit Ziffern wie "4.0..."). Feldreihenfolge-Änderungen der Hangar-API
     * treffen damit nicht das falsche Feld (z. B. Kanal- oder Projektname).
     */
    private static String ersteVersionAusNamensfeldern(String json) {
        Matcher m = NAME_MUSTER.matcher(json);
        while (m.find()) {
            String kandidat = m.group(1).trim();
            if (VERSION_ANFANG.matcher(kandidat).matches()) {
                return kandidat;
            }
        }
        return null;
    }

    private static String letzteGruppe(Pattern muster, String text) {
        Matcher m = muster.matcher(text);
        String treffer = null;
        while (m.find()) {
            treffer = m.group(1);
        }
        return treffer;
    }

    static final String ERLAUBTER_DOWNLOAD_HOST = "hangar.papermc.io";
    static final java.util.Set<String> ERLAUBTE_DOWNLOAD_HOSTS =
            java.util.Set.of("hangar.papermc.io", "hangarcdn.papermc.io");

    static void pruefeHttpsNachRedirect(java.net.URI ziel) {
        if (ziel == null) {
            throw new IllegalArgumentException("Nur https erlaubt (kein Ziel).");
        }
        if (!"https".equalsIgnoreCase(ziel.getScheme())) {
            throw new IllegalArgumentException("Nur https erlaubt (Redirect-Ziel: "
                    + String.valueOf(ziel.getScheme()) + ").");
        }
        boolean erlaubt = false;
        for (String host : ERLAUBTE_DOWNLOAD_HOSTS) {
            if (host.equalsIgnoreCase(ziel.getHost())) {
                erlaubt = true;
                break;
            }
        }
        if (!erlaubt) {
            throw new IllegalArgumentException("Download von fremdem Host blockiert (erlaubt: "
                    + ERLAUBTE_DOWNLOAD_HOSTS + ", erhalten: " + ziel.getHost() + ").");
        }
    }

    private static String urlKodiere(String wert) {
        try {
            return java.net.URLEncoder.encode(wert, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return wert;
        }
    }

    private static String requireText(String wert, String name) {
        if (wert == null || wert.isBlank()) {
            throw new IllegalArgumentException("Hangar-" + name + " fehlt (update.hangar-" + name + " setzen).");
        }
        return wert.trim();
    }

    public static long ticksFuerIntervallMinuten(long minuten) {
        if (minuten <= 0) {
            return 0L;
        }
        long gedeckelt = Math.min(minuten, 60L * 24L * 7L);
        try {
            return Math.multiplyExact(gedeckelt, 60L * 20L);
        } catch (ArithmeticException e) {
            return 60L * 24L * 7L * 60L * 20L;
        }
    }

    public static de.lightrixx.punish.core.GeplanteAufgabe planeHinweisIntervall(
            de.lightrixx.punish.core.AuftragsPlaner planer,
            java.util.function.LongSupplier intervallMinuten,
            Runnable hinweisPruefung) {
        java.util.Objects.requireNonNull(planer, "Der Planer darf nicht null sein.");
        java.util.Objects.requireNonNull(intervallMinuten, "Der Intervall-Lieferant darf nicht null sein.");
        java.util.Objects.requireNonNull(hinweisPruefung, "Die Hinweis-Prüfung darf nicht null sein.");
        long minuten;
        try {
            minuten = intervallMinuten.getAsLong();
        } catch (Exception e) {
            LOG.debug("Hinweis-Intervall konnte nicht gelesen werden.", e);
            return null;
        }
        if (minuten <= 0) {
            return null;
        }
        long ticks = ticksFuerIntervallMinuten(minuten);
        if (ticks <= 0) {
            return null;
        }
        try {
            return planer.intervallAsync(() -> {
                try {
                    hinweisPruefung.run();
                } catch (Exception e) {
                    // Nur Hinweis, nie Download – Fehler nur debuggen
                    LOG.debug("Hinweis-Prüfung fehlgeschlagen.", e);
                }
            }, ticks, ticks);
        } catch (Exception e) {
            LOG.debug("Hinweis-Intervall konnte nicht geplant werden.", e);
            return null;
        }
    }
}
