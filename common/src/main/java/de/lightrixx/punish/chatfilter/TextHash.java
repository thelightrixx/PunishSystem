package de.lightrixx.punish.chatfilter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public final class TextHash {

    private TextHash() {
    }

    public static String sha256Hex(String text) {
        Objects.requireNonNull(text, "Der Text darf nicht null sein.");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 wird von dieser JVM nicht unterstützt.", e);
        }
    }

    public static String sha256DateiHex(java.nio.file.Path datei) throws java.io.IOException {
        Objects.requireNonNull(datei, "Die Datei darf nicht null sein.");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var in = java.nio.file.Files.newInputStream(datei)) {
                byte[] puffer = new byte[65536];
                int gelesen;
                while ((gelesen = in.read(puffer)) >= 0) {
                    digest.update(puffer, 0, gelesen);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 wird von dieser JVM nicht unterstützt.", e);
        }
    }
}
