package de.lightrixx.punish.net;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;

public final class TunnelSignatur {

    public static final int LAENGE = 32;
    public static final int MINDESTLAENGE = 16;

    private static final String KDF_KONTEXT = "punish-tunnel-v7:";

    /**
     * Richtungsgebundene Schlüsselableitung. Proxy und Bridge leiten aus dem
     * gemeinsamen Secret unterschiedliche Schlüssel ab, damit ein mitgeschnittenes
     * Paket nicht in die Gegenrichtung gespiegelt werden kann.
     */
    public enum Richtung {
        PROXY_ZU_BRIDGE("proxy->bridge"),
        BRIDGE_ZU_PROXY("bridge->proxy");

        private final String kontext;

        Richtung(String kontext) {
            this.kontext = kontext;
        }

        String kontext() {
            return kontext;
        }
    }

    private TunnelSignatur() {
    }

    public static boolean istKonfiguriert(String geheimnis) {
        return geheimnis != null && !geheimnis.isBlank();
    }

    public static boolean istSchwach(String geheimnis) {
        return istKonfiguriert(geheimnis) && geheimnis.trim().length() < MINDESTLAENGE;
    }

    public static byte[] schluesselAus(String geheimnis, Richtung richtung) {
        if (!istKonfiguriert(geheimnis)) {
            throw new IllegalArgumentException("Das Geheimnis darf nicht leer sein.");
        }
        Objects.requireNonNull(richtung, "Die Richtung darf nicht null sein.");
        try {
            byte[] kontext = (KDF_KONTEXT + richtung.kontext())
                    .getBytes(StandardCharsets.UTF_8);
            return hmac(kontext, geheimnis.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("HMAC-Schlüsselableitung fehlgeschlagen.", e);
        }
    }

    public static byte[] signieren(byte[] paket, byte[] schluessel) throws IOException {
        Objects.requireNonNull(paket, "Das Paket darf nicht null sein.");
        if (schluessel == null || schluessel.length == 0) {
            throw new IOException("Tunnel-Schlüssel fehlt (Signatur abgelehnt).");
        }
        pruefeGroesse(paket.length + LAENGE);
        byte[] signatur = hmac(paket, schluessel);
        byte[] versand = Arrays.copyOf(paket, paket.length + LAENGE);
        System.arraycopy(signatur, 0, versand, paket.length, LAENGE);
        return versand;
    }

    public static byte[] pruefenUndAbziehen(byte[] roh, byte[] schluessel) throws IOException {
        Objects.requireNonNull(roh, "Das Paket darf nicht null sein.");
        if (schluessel == null || schluessel.length == 0) {
            throw new IOException("Tunnel-Schlüssel fehlt (Signaturprüfung abgelehnt).");
        }
        if (roh.length < 1 + LAENGE) {
            throw new IOException("Tunnel-Paket ohne Signatur verworfen (" + roh.length + " Bytes).");
        }
        pruefeGroesse(roh.length);
        int nutzlaenge = roh.length - LAENGE;
        byte[] erwartet = hmac(Arrays.copyOf(roh, nutzlaenge), schluessel);
        byte[] erhalten = Arrays.copyOfRange(roh, nutzlaenge, roh.length);
        if (!MessageDigest.isEqual(erwartet, erhalten)) {
            throw new IOException("Tunnel-Signatur ungültig (Bridge-Secret und Richtung prüfen).");
        }
        return Arrays.copyOf(roh, nutzlaenge);
    }

    private static void pruefeGroesse(int laenge) throws IOException {
        if (laenge > Protokoll.MAX_NUTZDATEN + 1 + LAENGE) {
            throw new IOException("Tunnel-Paket zu groß: " + laenge);
        }
    }

    private static byte[] hmac(byte[] daten, byte[] schluessel) throws IOException {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(schluessel, "HmacSHA256"));
            return mac.doFinal(daten);
        } catch (Exception e) {
            throw new IOException("HMAC konnte nicht berechnet werden.", e);
        }
    }
}
