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
    private volatile String letzterChannel = "";
    private volatile long letztePruefungMillis;
    private volatile String ausstehendVersion = "";
    private volatile long ausstehendZeit;

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
                    HangarUpdateService dienst = dienstAusConfig();
                    HangarUpdateService.ReleaseInfo info = dienst.pruefe();
                    letzteRemoteVersion = info.version();
                    letzterChannel = info.channel();
                    letztePruefungMillis = System.currentTimeMillis();
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
            ausstehendVersion = letzteRemoteVersion;
            ausstehendZeit = System.currentTimeMillis();
            messageService.senden(sender, MessageKeys.UPDATE_BESTAETIGUNG_NOETIG,
                    Map.of("version", letzteRemoteVersion.isBlank() ? "unbekannt" : letzteRemoteVersion));
            return;
        }
        ausstehendVersion = "";
        try {
            taskScheduler.aufgabeAsync(() -> {
                try {
                    taskScheduler.aufgabe(() -> messageService.senden(sender, MessageKeys.UPDATE_START));
                    HangarUpdateService dienst = dienstAusConfig();
                    HangarUpdateService.ReleaseInfo info = dienst.pruefe();
                    letzteRemoteVersion = info.version();
                    letzterChannel = info.channel();
                    letztePruefungMillis = System.currentTimeMillis();
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
                                    + dateiname + " (Hangar-Antwort unvollständig).");
                        }
                        byte[] daten = dienst.ladeArtefakt(new HangarUpdateService.Artefakt(
                                artefakt.plattform(), artefakt.dateiname(), url, artefakt.sha256()), MAX_JAR_BYTES);
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
                    if (bridge != null && bridge121 == null) {
                        throw new IllegalStateException(
                                "Update abgelehnt: zweites PAPER-Artefakt (bridge-1.21) fehlt in der "
                                        + "Hangar-Antwort. Version nicht verteilt.");
                    }
                    if (velocity != null) {
                        ersetzeEigeneJar(velocity, info.version());
                    } else {
                        LOG.warn("Update ohne Velocity-Artefakt (nur Bridges werden verteilt).");
                    }
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
                    taskScheduler.aufgabe(() -> messageService.senden(sender, MessageKeys.UPDATE_ERFOLGREICH_NEUSTART,
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

    private HangarUpdateService dienstAusConfig() {
        String autor = konfiguration.updateHangarAutor();
        String slug = konfiguration.updateHangarSlug();
        return new HangarUpdateService(autor, slug, konfiguration.updateChannel(), httpAbruf);
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

    /**
     * B-011: strikte Artefakt-Zuordnung anhand der Hangar-API-Plattform, keine
     * Dateiname-Heuristik. VELOCITY gilt für velocity, das erste PAPER für
     * bridge, ein zweites PAPER für bridge-1.21. Unbekannte Plattformen
     * brechen mit Fehler ab statt auf bridge zurückzufallen.
     */
    static final class ArtefaktZuordnung {
        private boolean paperVergeben;

        String nimm(HangarUpdateService.Artefakt artefakt) {
            if (artefakt == null) {
                throw new IllegalStateException("Update abgelehnt: Artefakt ohne Plattform.");
            }
            String plattform = artefakt.plattform() == null ? ""
                    : artefakt.plattform().trim().toUpperCase(Locale.ROOT);
            if ("VELOCITY".equals(plattform)) {
                return "velocity";
            }
            if ("PAPER".equals(plattform)) {
                if (!paperVergeben) {
                    paperVergeben = true;
                    return "bridge";
                }
                return "bridge-1.21";
            }
            throw new IllegalStateException("Update abgelehnt: unbekannte Plattform '"
                    + (artefakt.plattform() == null ? "" : artefakt.plattform().trim()) + "'.");
        }
    }

    private void ersetzeEigeneJar(byte[] daten, String version) {
        try {
            var quelle = VelocityUpdateBefehl.class.getProtectionDomain().getCodeSource();
            if (quelle == null || quelle.getLocation() == null) {
                LOG.warn("Eigene JAR-Position unbekannt, Velocity-Update {} nicht bereitgestellt "
                        + "(Bridges trotzdem verteilt).", version);
                return;
            }
            java.nio.file.Path jar = java.nio.file.Paths.get(quelle.getLocation().toURI());
            java.nio.file.Path ordner = jar.getParent();
            if (ordner == null) {
                LOG.warn("Plugin-Ordner unbekannt, Velocity-Update {} nicht bereitgestellt.", version);
                return;
            }
            java.nio.file.Path bereit = legeUpdateBereit(ordner, daten, version);
            LOG.warn("Velocity-Update {} bereitgestellt ({}), aktiv nach Proxy-Neustart "
                    + "(laufende JAR wird nicht ersetzt).", version, bereit.getFileName());
        } catch (Exception e) {
            LOG.warn("Velocity-Update konnte nicht bereitgestellt werden (Bridges trotzdem verteilt).", e);
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
