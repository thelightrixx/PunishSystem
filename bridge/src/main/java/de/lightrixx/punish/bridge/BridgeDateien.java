package de.lightrixx.punish.bridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

final class BridgeDateien {

    private static final Logger LOG = LoggerFactory.getLogger(BridgeDateien.class);
    private static final long STAND_STREAMING_AB_BYTES = 8L * 1024L * 1024L;

    private BridgeDateien() {
    }

    static java.util.Optional<String> chatfilterStand(File datenOrdner,
            de.lightrixx.punish.bridge.chatfilter.ChatfilterKonfiguration cfConfig) {
        try {
            java.util.Map<String, byte[]> stand = new java.util.LinkedHashMap<>();
            java.io.File configOrdner = new java.io.File(datenOrdner, "config");
            leseInStand(stand, new java.io.File(configOrdner, "chatfilter.yml"), "chatfilter.yml");
            leseInStand(stand, new java.io.File(new java.io.File(datenOrdner, TextEmpfaenger.SYNC_ORDNER),
                    "general.yml"), "config-sync-general.yml");
            if (cfConfig != null) {
                leseInStand(stand, cfConfig.dateiAufloesen(cfConfig.wortlisteDateiname()), "wortliste.txt");
                leseInStand(stand, cfConfig.dateiAufloesen(cfConfig.modellDateiname()), "modell_int8.onnx");
                leseInStand(stand, cfConfig.dateiAufloesen(cfConfig.vocabDateiname()), "vocab.txt");
            }
            if (stand.isEmpty()) {
                return java.util.Optional.of("");
            }
            return java.util.Optional.of(de.lightrixx.punish.net.Protokoll.textPaketHash(stand));
        } catch (Exception e) {
            LOG.debug("Chatfilter-Datei-Stand konnte nicht berechnet werden.", e);
            return java.util.Optional.empty();
        }
    }

    private static void leseInStand(java.util.Map<String, byte[]> stand, java.io.File datei,
                                    String schluessel) {
        try {
            if (datei != null && datei.isFile() && datei.length() > 0) {
                if (datei.length() > STAND_STREAMING_AB_BYTES) {
                    String hash = de.lightrixx.punish.chatfilter.TextHash.sha256DateiHex(
                            datei.toPath());
                    stand.put(schluessel, hash.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } else {
                    stand.put(schluessel, java.nio.file.Files.readAllBytes(datei.toPath()));
                }
            }
        } catch (Exception e) {
            LOG.debug("Chatfilter-Datei {} konnte nicht gelesen werden.", schluessel, e);
        }
    }

    static String hashDateiStill(
            de.lightrixx.punish.bridge.chatfilter.ChatfilterKonfiguration cfConfig, String name) {
        try {
            java.io.File datei = cfConfig.dateiAufloesen(name);
            if (datei == null || !datei.isFile()) {
                return "";
            }
            return de.lightrixx.punish.chatfilter.TextHash.sha256DateiHex(datei.toPath());
        } catch (Exception e) {
            LOG.debug("Modell-Hash konnte nicht berechnet werden.", e);
            return "";
        }
    }

    private static boolean fehltZeilenumbruch(java.nio.file.Path pfad) throws java.io.IOException {
        try (java.nio.channels.FileChannel kanal = java.nio.channels.FileChannel.open(pfad,
                java.nio.file.StandardOpenOption.READ)) {
            long groesse = kanal.size();
            if (groesse <= 0) {
                return false;
            }
            java.nio.ByteBuffer puffer = java.nio.ByteBuffer.allocate(1);
            kanal.position(groesse - 1);
            kanal.read(puffer);
            byte letztes = puffer.array()[0];
            return letztes != '\n' && letztes != '\r';
        }
    }

    static void migriereLegacyOrdner(File neu) {
        try {
            File eltern = neu.getParentFile();
            if (eltern == null) {
                return;
            }
            if (neu.exists() && neu.isDirectory() && neu.list() != null && neu.list().length > 0) {
                return;
            }
            String[] legacyNamen = {"PunishBridge", "PunishSystem-Bridge"};
            for (String legacyName : legacyNamen) {
                File alt = new File(eltern, legacyName);
                if (alt.equals(neu) || !alt.isDirectory()) {
                    continue;
                }
                try {
                    java.nio.file.Files.move(alt.toPath(), neu.toPath(),
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                    LOG.info("Datenordner '{}' nach '{}' migriert.", alt.getName(), neu.getName());
                    return;
                } catch (Exception moveFehler) {
                    LOG.debug("Atomarer Move fehlgeschlagen, versuche Kopie.", moveFehler);
                    try {
                        kopiereOrdnerRekursiv(alt.toPath(), neu.toPath());
                        LOG.info("Datenordner '{}' nach '{}' kopiert (alt bleibt als Backup).",
                                alt.getName(), neu.getName());
                        return;
                    } catch (Exception kopierFehler) {
                        LOG.warn("Legacy-Datenordner '{}' konnte nicht migriert werden.", alt.getName(), kopierFehler);
                    }
                }
            }
        } catch (Exception e) {
            LOG.debug("Legacy-Migration übersprungen.", e);
        }
    }

    private static void kopiereOrdnerRekursiv(java.nio.file.Path quelle, java.nio.file.Path ziel) throws java.io.IOException {
        try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.walk(quelle)) {
            for (java.nio.file.Path pfad : (Iterable<java.nio.file.Path>) stream::iterator) {
                java.nio.file.Path relativ = quelle.relativize(pfad);
                java.nio.file.Path zielPfad = ziel.resolve(relativ);
                if (java.nio.file.Files.isDirectory(pfad)) {
                    java.nio.file.Files.createDirectories(zielPfad);
                } else {
                    java.nio.file.Files.createDirectories(zielPfad.getParent());
                    java.nio.file.Files.copy(pfad, zielPfad, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    static void fuegeWortHinzu(File datei, String wort) throws java.io.IOException {
        var eltern = datei.getParentFile();
        if (eltern != null) {
            eltern.mkdirs();
        }
        java.nio.file.Path pfad = datei.toPath();
        if (java.nio.file.Files.exists(pfad) && fehltZeilenumbruch(pfad)) {
            java.nio.file.Files.writeString(pfad, System.lineSeparator(),
                    java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.APPEND);
        }
        java.nio.file.Files.writeString(pfad, wort + System.lineSeparator(),
                java.nio.charset.StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }
}
