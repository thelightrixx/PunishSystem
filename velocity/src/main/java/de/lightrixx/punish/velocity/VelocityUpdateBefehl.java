package de.lightrixx.punish.velocity;

import de.lightrixx.punish.command.PunishCommand;
import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.Permissions;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.storage.PlayerCache;
import de.lightrixx.punish.update.HangarUpdateService;
import de.lightrixx.punish.update.UpdateVergleich;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public final class VelocityUpdateBefehl extends PunishCommand {

    private static final Logger LOG = LoggerFactory.getLogger(VelocityUpdateBefehl.class);
    private static final long MAX_JAR_BYTES = 64L * 1024L * 1024L;

    private final VelocityKonfiguration konfiguration;
    private final Supplier<String> versionLieferant;
    private final java.util.concurrent.atomic.AtomicBoolean laeuft = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile BrueckenKanal brueckenKanal;
    private volatile UpdateVerteilung updateVerteilung;
    private volatile HangarUpdateService.HttpAbruf httpAbruf = HangarUpdateService.standardAbruf();
    private volatile String letzteRemoteVersion = "";

    public VelocityUpdateBefehl(MessageService messageService,
                                VelocityKonfiguration konfiguration,
                                ProtectionService protectionService,
                                PlayerCache playerCache,
                                AuftragsPlaner planer,
                                de.lightrixx.punish.core.SpielerVerzeichnis verzeichnis,
                                Supplier<String> versionLieferant) {
        super(messageService, konfiguration, protectionService, playerCache, planer,
                Objects.requireNonNull(verzeichnis, "Das Spielerverzeichnis darf nicht null sein."));
        this.konfiguration = Objects.requireNonNull(konfiguration, "Die Konfiguration darf nicht null sein.");
        this.versionLieferant = Objects.requireNonNull(versionLieferant, "Der Versionslieferant darf nicht null sein.");
    }

    public void setBrueckenKanal(BrueckenKanal kanal) {
        this.brueckenKanal = kanal;
    }

    public void setUpdateVerteilung(UpdateVerteilung verteilung) {
        this.updateVerteilung = Objects.requireNonNull(verteilung,
                "Die Update-Verteilung darf nicht null sein.");
    }

    public void setHttpAbruf(HangarUpdateService.HttpAbruf abruf) {
        this.httpAbruf = Objects.requireNonNull(abruf, "Der HTTP-Abruf darf nicht null sein.");
    }

    @Override
    protected String recht() {
        return Permissions.PUNISH_UPDATE.node();
    }

    @Override
    public boolean unterstuetztScope() {
        return false;
    }

    @Override
    public void ausfuehren(BefehlsSender sender, String[] args) {
        String aktion = args == null || args.length == 0 || args[0] == null || args[0].isBlank()
                ? "check" : args[0].trim().toLowerCase(Locale.ROOT);
        boolean bestaetigt = (args != null && args.length > 1 && "confirm".equalsIgnoreCase(args[1].trim()))
                || "confirm".equals(aktion);
        String befehl = "confirm".equals(aktion) ? "apply" : aktion;
        switch (befehl) {
            case "check" -> pruefeAsync(sender);
            case "status" -> sendeStatus(sender);
            case "apply" -> wendeAnAsync(sender, bestaetigt);
            default -> pruefeAsync(sender);
        }
    }

    private void pruefeAsync(BefehlsSender sender) {
        if (!laeuft.compareAndSet(false, true)) {
            messageService.senden(sender, MessageKeys.UPDATE_LAEUFT_BEREITS);
            return;
        }
        try {
            taskScheduler.aufgabeAsync(() -> {
                try {
                    HangarUpdateService.ReleaseInfo info = pruefeGithub(konfiguration, httpAbruf);
                    letzteRemoteVersion = info.version();
                    String aktuell = aktuelleVersion();
                    if (UpdateVergleich.neuerVerfuegbar(aktuell, info.version())) {
                        taskScheduler.aufgabe(() -> messageService.senden(sender, MessageKeys.UPDATE_VERFUEGBAR,
                                Map.of("version", info.version(), "aktuell", aktuell, "channel", info.channel())));
                    } else {
                        taskScheduler.aufgabe(() -> messageService.senden(sender, MessageKeys.UPDATE_AKTUELL,
                                Map.of("version", aktuell)));
                    }
                } catch (Exception e) {
                    LOG.warn("Update-Check fehlgeschlagen.", e);
                    try {
                        taskScheduler.aufgabe(() -> messageService.senden(sender, MessageKeys.UPDATE_FEHLER,
                                Map.of("detail", kurz(e))));
                    } catch (Exception ignored) {
                        LOG.debug("Update-Fehlermeldung verworfen.", ignored);
                    }
                } finally {
                    laeuft.set(false);
                }
            });
        } catch (IllegalStateException e) {
            laeuft.set(false);
            messageService.senden(sender, MessageKeys.UPDATE_FEHLER, Map.of("detail", "Scheduler geschlossen"));
        }
    }

    private void wendeAnAsync(BefehlsSender sender, boolean bestaetigt) {
        if (!laeuft.compareAndSet(false, true)) {
            messageService.senden(sender, MessageKeys.UPDATE_LAEUFT_BEREITS);
            return;
        }
        if (bestaetigungNoetig() && !bestaetigt) {
            laeuft.set(false);
            messageService.senden(sender, MessageKeys.UPDATE_BESTAETIGUNG_NOETIG,
                    Map.of("version", letzteRemoteVersion.isBlank() ? "unbekannt" : letzteRemoteVersion));
            return;
        }
        try {
            taskScheduler.aufgabeAsync(() -> {
                try {
                    taskScheduler.aufgabe(() -> messageService.senden(sender, MessageKeys.UPDATE_START));
                    HangarUpdateService.ReleaseInfo info = pruefeGithub(konfiguration, httpAbruf);
                    letzteRemoteVersion = info.version();
                    String aktuell = aktuelleVersion();
                    if (!UpdateVergleich.neuerVerfuegbar(aktuell, info.version())) {
                        taskScheduler.aufgabe(() -> messageService.senden(sender, MessageKeys.UPDATE_AKTUELL,
                                Map.of("version", aktuell)));
                        return;
                    }
                    byte[] velocity = null;
                    byte[] bridge = null;
                    byte[] bridge121 = null;
                    ArtefaktZuordnung zuordnung = new ArtefaktZuordnung();
                    for (HangarUpdateService.Artefakt artefakt : info.downloads()) {
                        String dateiname = artefakt.dateiname() == null ? "" : artefakt.dateiname().toLowerCase(Locale.ROOT);
                        String url = artefakt.url();
                        if (url == null || url.isBlank()) {
                            continue;
                        }
                        if (artefakt.sha256() == null || artefakt.sha256().isBlank()) {
                            throw new IllegalStateException("Update abgelehnt: kein SHA-256 für "
                                    + dateiname + " (GitHub-Antwort unvollständig).");
                        }
                        byte[] daten = httpAbruf.ladeBytes(url, MAX_JAR_BYTES);
                        String hash = UpdateVerteilung.hashStill(daten);
                        if (!hash.equalsIgnoreCase(artefakt.sha256().trim())) {
                            throw new IllegalStateException("SHA-256 weicht ab (" + dateiname + ").");
                        }
                        switch (zuordnung.nimm(artefakt)) {
                            case "velocity" -> {
                                if (velocity != null) {
                                    throw new IllegalStateException(
                                            "Update abgelehnt: doppeltes Velocity-Artefakt.");
                                }
                                velocity = daten;
                            }
                            case "bridge" -> {
                                if (bridge != null) {
                                    throw new IllegalStateException(
                                            "Update abgelehnt: doppeltes Bridge-Artefakt.");
                                }
                                bridge = daten;
                            }
                            case "bridge-1.21" -> {
                                if (bridge121 != null) {
                                    throw new IllegalStateException(
                                            "Update abgelehnt: doppeltes Bridge-1.21-Artefakt.");
                                }
                                bridge121 = daten;
                            }
                            default -> throw new IllegalStateException(
                                    "Update abgelehnt: unbekanntes Artefakt.");
                        }
                    }
                    if (velocity == null || bridge == null || bridge121 == null) {
                        throw new IllegalStateException(
                                "Update abgelehnt: Das GitHub Release muss Velocity und beide Bridge-Varianten enthalten.");
                    }
                    ersetzeEigeneJar(velocity, info.version());
                    UpdateVerteilung verteilung = updateVerteilung;
                    BrueckenKanal kanal = brueckenKanal;
                    if (verteilung != null && (bridge != null || bridge121 != null)) {
                        verteilung.legeAb(info.version(), bridge, bridge121);
                        try {
                            taskScheduler.aufgabe(() -> messageService.senden(sender, MessageKeys.UPDATE_PUSH_LAUFEND,
                                    Map.of("version", info.version())));
                        } catch (Exception ignored) {
                            LOG.debug("Push-Startmeldung verworfen.", ignored);
                        }
                        if (kanal != null) {
                            verteilung.pushAnAlle(kanal);
                        }
                    }
                    String installiert = info.version();
                    taskScheduler.aufgabe(() -> messageService.senden(sender, MessageKeys.UPDATE_BEREITGESTELLT,
                            Map.of("version", installiert)));
                } catch (Exception e) {
                    LOG.warn("Update-Apply fehlgeschlagen.", e);
                    try {
                        taskScheduler.aufgabe(() -> messageService.senden(sender, MessageKeys.UPDATE_FEHLER,
                                Map.of("detail", kurz(e))));
                    } catch (Exception ignored) {
                        LOG.debug("Update-Fehlermeldung verworfen.", ignored);
                    }
                } finally {
                    laeuft.set(false);
                }
            });
        } catch (IllegalStateException e) {
            laeuft.set(false);
            messageService.senden(sender, MessageKeys.UPDATE_FEHLER, Map.of("detail", "Scheduler geschlossen"));
        }
    }

    private void sendeStatus(BefehlsSender sender) {
        String aktuell = aktuelleVersion();
        String remote = letzteRemoteVersion.isBlank() ? "unbekannt" : letzteRemoteVersion;
        Map<String, String> bruecken = Map.of();
        Map<String, String> varianten = Map.of();
        Map<String, Boolean> quittungen = Map.of();
        BrueckenKanal kanal = brueckenKanal;
        if (kanal != null) {
            try {
                bruecken = kanal.bridgeVersionen();
            } catch (Exception e) {
                LOG.debug("Bridge-Versionen konnten nicht gelesen werden.", e);
            }
            try {
                varianten = kanal.bridgeVarianten();
            } catch (Exception e) {
                LOG.debug("Bridge-Varianten konnten nicht gelesen werden.", e);
            }
        }
        UpdateVerteilung verteilung = updateVerteilung;
        if (verteilung != null) {
            try {
                quittungen = verteilung.quittungen();
            } catch (Exception e) {
                LOG.debug("Update-Quittungen konnten nicht gelesen werden.", e);
            }
        }
        StringBuilder detail = new StringBuilder("Proxy=").append(aktuell)
                .append(", Remote=").append(remote);
        if (!bruecken.isEmpty()) {
            var teile = new java.util.ArrayList<String>();
            for (var eintrag : bruecken.entrySet()) {
                String v = varianten.getOrDefault(eintrag.getKey(), "?");
                teile.add(eintrag.getKey() + "=" + eintrag.getValue() + "[" + v + "]");
            }
            teile.sort(String.CASE_INSENSITIVE_ORDER);
            detail.append(", ").append(String.join(", ", teile));
        }
        if (!quittungen.isEmpty()) {
            detail.append(", Quittungen=").append(quittungen);
        }
        messageService.senden(sender, MessageKeys.UPDATE_PUSH_FERTIG,
                Map.of("version", remote, "detail", detail.toString()));
    }

    static HangarUpdateService.ReleaseInfo pruefeGithub(VelocityKonfiguration config,
                                                        HangarUpdateService.HttpAbruf http) throws Exception {
        String repository = config.updateGithubRepository();
        if (repository == null || !repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("update.github-repository muss Besitzer/Repository enthalten.");
        }
        boolean vorab = !"Release".equalsIgnoreCase(config.updateChannel());
        String api = "https://api.github.com/repos/" + repository + "/releases";
        var optionen = new org.yaml.snakeyaml.LoaderOptions();
        Object roh = new org.yaml.snakeyaml.Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(optionen))
                .load(http.leseText(vorab ? api + "?per_page=30" : api + "/latest"));
        if (roh == null) {
            throw new IllegalStateException("Leere GitHub-Antwort.");
        }
        java.util.List<?> releases = roh instanceof java.util.List<?> liste ? liste : java.util.List.of(roh);
        java.util.Map<?, ?> gewaehlt = null;
        String version = "";
        for (Object kandidat : releases) {
            if (!(kandidat instanceof java.util.Map<?, ?> release)
                    || Boolean.TRUE.equals(release.get("draft"))
                    || (!vorab && Boolean.TRUE.equals(release.get("prerelease")))) {
                continue;
            }
            String tag = String.valueOf(release.get("tag_name"));
            if (!tag.matches("[vV]?[0-9]+(\\.[0-9]+)+([-+][A-Za-z0-9]+(\\.[A-Za-z0-9]+)*)*")) {
                continue;
            }
            String kandidatVersion = tag.replaceFirst("^[vV]", "");
            if (gewaehlt == null || UpdateVergleich.neuerVerfuegbar(version, kandidatVersion)) {
                gewaehlt = release;
                version = kandidatVersion;
            }
        }
        if (gewaehlt == null || !(gewaehlt.get("assets") instanceof java.util.List<?> assets)) {
            throw new IllegalStateException("Kein passendes GitHub Release gefunden.");
        }
        String tag = String.valueOf(gewaehlt.get("tag_name"));
        java.util.Map<String, java.util.Map<?, ?>> dateien = new java.util.LinkedHashMap<>();
        for (Object objekt : assets) {
            if (objekt instanceof java.util.Map<?, ?> asset && asset.get("name") instanceof String name) {
                if (dateien.putIfAbsent(name, asset) != null) {
                    throw new IllegalStateException("Doppeltes Release-Artefakt: " + name);
                }
            }
        }
        var summenDatei = dateien.get("SHA256SUMS.txt");
        if (summenDatei == null) {
            throw new IllegalStateException("SHA256SUMS.txt fehlt im GitHub Release.");
        }
        java.util.Map<String, String> summen = new java.util.HashMap<>();
        for (String zeile : http.leseText(githubDownloadUrl(summenDatei, repository, tag)).lines().toList()) {
            var matcher = java.util.regex.Pattern.compile("^([0-9a-fA-F]{64}) [ *](.+)$").matcher(zeile);
            if (matcher.matches() && summen.putIfAbsent(matcher.group(2), matcher.group(1)) != null) {
                throw new IllegalStateException("Doppelte Prüfsumme: " + matcher.group(2));
            }
        }
        java.util.List<HangarUpdateService.Artefakt> downloads = new java.util.ArrayList<>();
        String[] teile = version.split("\\+", 2);
        for (String variante : java.util.List.of("velocity", "bridge", "bridge-1.21")) {
            String name = "PunishSystem-v" + teile[0] + "-(" + variante + ")"
                    + (teile.length == 2 ? "+" + teile[1].replace('-', '_') : "") + ".jar";
            if (!dateien.containsKey(name)) {
                name = "PunishSystem-v" + version + "-" + variante + ".jar";
            }
            var asset = dateien.get(name);
            String hash = summen.get(name);
            if (asset == null || hash == null) {
                throw new IllegalStateException("Artefakt oder SHA-256 fehlt im GitHub Release: " + name);
            }
            Object digest = asset.get("digest");
            if (digest instanceof String text && !text.equalsIgnoreCase("sha256:" + hash)) {
                throw new IllegalStateException("Widersprüchliche SHA-256-Prüfsumme: " + name);
            }
            downloads.add(new HangarUpdateService.Artefakt(variante.toUpperCase(Locale.ROOT), name,
                    githubDownloadUrl(asset, repository, tag), hash));
        }
        return new HangarUpdateService.ReleaseInfo(version,
                Boolean.TRUE.equals(gewaehlt.get("prerelease")) ? "Beta" : "Release", downloads);
    }

    private static String githubDownloadUrl(java.util.Map<?, ?> asset, String repository, String tag) {
        String url = String.valueOf(asset.get("browser_download_url"));
        java.net.URI uri = java.net.URI.create(url);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !"github.com".equalsIgnoreCase(uri.getHost())
                || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                || !("/" + repository + "/releases/download/" + tag + "/" + asset.get("name"))
                        .equals(uri.getPath())) {
            throw new IllegalArgumentException("Release-Download liegt außerhalb des gewählten GitHub Releases.");
        }
        return url;
    }

    private String aktuelleVersion() {
        try {
            String geliefert = versionLieferant.get();
            if (geliefert != null && !geliefert.isBlank()) {
                return geliefert.trim();
            }
        } catch (Exception e) {
            LOG.debug("Versionsabfrage fehlgeschlagen.", e);
        }
        return "unbekannt";
    }

    private boolean bestaetigungNoetig() {
        try {
            return konfiguration.updateBestaetigungFuerApply();
        } catch (Exception e) {
            return true;
        }
    }

    static final class ArtefaktZuordnung {

        String nimm(HangarUpdateService.Artefakt artefakt) {
            if (artefakt == null) {
                throw new IllegalStateException("Update abgelehnt: Artefakt ohne Plattform.");
            }
            String plattform = artefakt.plattform() == null ? ""
                    : artefakt.plattform().trim().toUpperCase(Locale.ROOT);
            if ("VELOCITY".equals(plattform)) {
                return "velocity";
            }
            if ("BRIDGE".equals(plattform) || "BRIDGE-1.21".equals(plattform)) {
                return plattform.toLowerCase(Locale.ROOT);
            }
            throw new IllegalStateException("Update abgelehnt: unbekannte Plattform '"
                    + (artefakt.plattform() == null ? "" : artefakt.plattform().trim()) + "'.");
        }
    }

    private void ersetzeEigeneJar(byte[] daten, String version) {
        try {
            var quelle = VelocityUpdateBefehl.class.getProtectionDomain().getCodeSource();
            if (quelle == null || quelle.getLocation() == null) {
                throw new IllegalStateException("Eigene JAR-Position unbekannt, Update nicht bereitgestellt.");
            }
            java.nio.file.Path jar = java.nio.file.Paths.get(quelle.getLocation().toURI());
            java.nio.file.Path ordner = jar.getParent();
            if (ordner == null) {
                throw new IllegalStateException("Plugin-Ordner unbekannt, Update nicht bereitgestellt.");
            }
            java.nio.file.Path bereit = legeUpdateBereit(ordner, daten, version);
            LOG.warn("Velocity-Update {} bereitgestellt ({}). Vor dem Neustart die bisherige Plugin-JAR "
                    + "manuell durch diese Datei ersetzen.", version, bereit);
        } catch (Exception e) {
            throw new IllegalStateException("Velocity-Update konnte nicht bereitgestellt werden.", e);
        }
    }

    static java.nio.file.Path legeUpdateBereit(java.nio.file.Path ordner, byte[] daten,
                                               String version) throws java.io.IOException {
        Objects.requireNonNull(ordner, "Der Ordner darf nicht null sein.");
        Objects.requireNonNull(daten, "Die Daten dürfen nicht null sein.");
        String sicher = version == null ? "unbekannt"
                : version.trim().replaceAll("[^A-Za-z0-9._+-]", "_");
        if (sicher.isBlank()) {
            sicher = "unbekannt";
        }
        if (sicher.length() > 64) {
            sicher = sicher.substring(0, 64);
        }
        java.nio.file.Path zielOrdner = ordner.resolve("update-pending");
        java.nio.file.Files.createDirectories(zielOrdner);
        java.nio.file.Path ziel = zielOrdner.resolve("PunishSystemVelocity-" + sicher + "-velocity.jar");
        java.nio.file.Path teil = zielOrdner.resolve(ziel.getFileName() + ".part");
        java.nio.file.Files.write(teil, daten);
        String hash = UpdateVerteilung.hashStill(java.nio.file.Files.readAllBytes(teil));
        if (!hash.equalsIgnoreCase(UpdateVerteilung.hashStill(daten))) {
            try {
                java.nio.file.Files.deleteIfExists(teil);
            } catch (Exception ignored) {
                LOG.debug("Unvollständige .part-Datei konnte nicht gelöscht werden.", ignored);
            }
            throw new IllegalStateException("Update-Datei unvollständig geschrieben.");
        }
        java.nio.file.Files.move(teil, ziel,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return ziel;
    }

    private static String kurz(Exception e) {
        String meldung = e == null ? "" : String.valueOf(e.getMessage());
        if (meldung.isBlank()) {
            return e == null ? "unbekannt" : e.getClass().getSimpleName();
        }
        return meldung.length() > 160 ? meldung.substring(0, 160) : meldung;
    }

    @Override
    public List<String> vervollstaendigen(BefehlsSender sender, String[] args) {
        if (args == null || args.length <= 1) {
            String prefix = args == null || args.length == 0 || args[0] == null ? "" : args[0].trim().toLowerCase(Locale.ROOT);
            return filtere(List.of("check", "apply", "confirm", "status"), prefix);
        }
        if (args.length == 2 && "apply".equalsIgnoreCase(args[0].trim())) {
            return filtere(List.of("confirm"), args[1] == null ? "" : args[1].trim().toLowerCase(Locale.ROOT));
        }
        return List.of();
    }
}
