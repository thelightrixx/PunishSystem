package de.lightrixx.punish.model;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record IpBanRecord(
        String ip,
        String grund,
        String ausfuehrer,
        long erstelltAm,
        Long ablaufAm,
        ServerScope scope
) {

    public IpBanRecord(String ip, String grund, String ausfuehrer, long erstelltAm, Long ablaufAm) {
        this(ip, grund, ausfuehrer, erstelltAm, ablaufAm, ServerScope.unbekannt());
    }

    private static final String OKTETT = "(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)";
    private static final String IP_V4_RUMPF = "(" + OKTETT + "\\.){3}" + OKTETT;
    private static final Pattern IP_V4_MUSTER = Pattern.compile("^" + IP_V4_RUMPF + "$");
    private static final Pattern IP_V6_MUSTER = Pattern.compile(
            "^("
                    + "([0-9a-f]{1,4}:){7}[0-9a-f]{1,4}"
                    + "|([0-9a-f]{1,4}:){1,7}:"
                    + "|([0-9a-f]{1,4}:){1,6}:[0-9a-f]{1,4}"
                    + "|([0-9a-f]{1,4}:){1,5}(:[0-9a-f]{1,4}){1,2}"
                    + "|([0-9a-f]{1,4}:){1,4}(:[0-9a-f]{1,4}){1,3}"
                    + "|([0-9a-f]{1,4}:){1,3}(:[0-9a-f]{1,4}){1,4}"
                    + "|([0-9a-f]{1,4}:){1,2}(:[0-9a-f]{1,4}){1,5}"
                    + "|[0-9a-f]{1,4}:((:[0-9a-f]{1,4}){1,6})"
                    + "|:((:[0-9a-f]{1,4}){1,7}|:)"
                    + "|::(ffff(:0{1,4})?:)?" + IP_V4_RUMPF
                    + "|([0-9a-f]{1,4}:){1,4}:" + IP_V4_RUMPF
                    + ")$"
    );

    public IpBanRecord {
        Objects.requireNonNull(ip, "Die IP-Adresse darf nicht null sein.");
        if (scope == null) {
            throw new IllegalArgumentException("Der Scope darf nicht null sein (nutze ServerScope.unbekannt()).");
        }
        ip = kanonisieren(ip);
        Objects.requireNonNull(grund, "Der Grund darf nicht null sein.");
        Objects.requireNonNull(ausfuehrer, "Der Ausführende darf nicht null sein.");
        if (erstelltAm <= 0) {
            throw new IllegalArgumentException("Der Erstellungszeitpunkt muss positiv sein.");
        }

        if (ablaufAm != null && (ablaufAm <= 0 || ablaufAm == -1L || ablaufAm == Long.MAX_VALUE)) {
            ablaufAm = null;
        }
        if (ablaufAm != null && ablaufAm <= erstelltAm) {
            throw new IllegalArgumentException(
                    "Der Ablaufzeitpunkt muss nach dem Erstellungszeitpunkt liegen.");
        }
    }

    public IpBanRecord(String ip, String grund, String ausfuehrer, long erstelltAm) {
        this(ip, grund, ausfuehrer, erstelltAm, null, ServerScope.unbekannt());
    }

    public boolean istAktivFuer(long jetztMillis, String aktuellerServer) {
        return istAktiv(jetztMillis) && scope.giltFuer(aktuellerServer);
    }

    public boolean istPermanent() {
        return ablaufAm == null;
    }

    public boolean istAktiv(long jetztMillis) {
        if (istPermanent()) {
            return true;
        }
        return ablaufAm > jetztMillis;
    }

    public boolean istAbgelaufen(long jetztMillis) {
        return !istPermanent() && ablaufAm <= jetztMillis;
    }

    public long verbleibendeMillis(long jetztMillis) {
        if (istPermanent()) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, ablaufAm - jetztMillis);
    }

    public static String kanonisieren(String roh) {
        if (roh == null) {
            throw new IllegalArgumentException("Die IP-Adresse darf nicht null sein.");
        }
        if (roh.length() > 64) {
            throw new IllegalArgumentException("Die IP-Adresse ist zu lang.");
        }

        String ip = roh.trim().toLowerCase(Locale.ROOT);
        if (ip.isBlank()) {
            throw new IllegalArgumentException("Die IP-Adresse darf nicht leer sein.");
        }
        if (ip.length() > 64) {
            throw new IllegalArgumentException("Die IP-Adresse ist zu lang.");
        }

        if (!istGueltigeIp(ip)) {
            throw new IllegalArgumentException("Ungültige IP-Adresse: " + ip);
        }

        try {
            InetAddress adresse = InetAddress.getByName(ip);
            return adresse.getHostAddress().toLowerCase(Locale.ROOT);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Ungültige IP-Adresse: " + ip, e);
        }
    }

    private static boolean istGueltigeIp(String ip) {
        return IP_V4_MUSTER.matcher(ip).matches()
                || IP_V6_MUSTER.matcher(ip).matches();
    }
}
