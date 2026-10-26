package de.lightrixx.punish.core;

import de.lightrixx.punish.model.VerstossStufe;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface Konfiguration {

    String AKTUELLE_CONFIG_VERSION = "EA26";

    static java.util.Optional<de.lightrixx.punish.util.BuildNumber> parseConfigVersion(String roh) {
        if (roh == null || roh.isBlank()) {
            return java.util.Optional.empty();
        }
        return de.lightrixx.punish.util.BuildNumber.tryParse(roh.trim());
    }

    static boolean istConfigNeuerAlsJar(String configVersion, String jarVersion) {
        java.util.Optional<de.lightrixx.punish.util.BuildNumber> config =
                parseConfigVersion(configVersion);
        java.util.Optional<de.lightrixx.punish.util.BuildNumber> jar =
                parseConfigVersion(jarVersion);
        if (config.isEmpty() || jar.isEmpty()) {
            return false;
        }
        return config.get().compareTo(jar.get()) > 0;
    }

    static boolean istDowngrade(String configVersion) {
        return istConfigNeuerAlsJar(configVersion, AKTUELLE_CONFIG_VERSION);
    }

    default String configVersion() {
        return AKTUELLE_CONFIG_VERSION;
    }

    default int ipAufbewahrungTage() {
        return 90;
    }

    record LimitSnapshot(int maxPunkte, String autoBannDauer) {
    }

    String praefix();

    String nachricht(String schluessel);

    int verstoesseMaxPunkte();

    String verstoesseAutoBannDauer();

    de.lightrixx.punish.model.VerstossStufe verstossStandardStufe();

    long verstossStufenDauerMillis(de.lightrixx.punish.model.VerstossStufe stufe);

    int verstossStufenPunkte(de.lightrixx.punish.model.VerstossStufe stufe);

    LimitSnapshot limitSnapshot();

    default boolean warnEskalationAktiv() {
        return false;
    }

    default Map<Integer, VerstossStufe> warnEskalationsSchwellen() {
        return Map.of();
    }

    default Optional<VerstossStufe> warnEskalationsStufeFuer(int anzahl) {
        if (!warnEskalationAktiv() || anzahl < 1) {
            return Optional.empty();
        }
        try {
            Map<Integer, VerstossStufe> schwellen = warnEskalationsSchwellen();
            if (schwellen == null || schwellen.isEmpty()) {
                return Optional.empty();
            }
            return Optional.ofNullable(schwellen.get(anzahl));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    static Map<Integer, VerstossStufe> standardWarnSchwellen() {
        return Map.of(3, VerstossStufe.LOW, 5, VerstossStufe.MEDIUM, 10, VerstossStufe.HIGH);
    }

    static Map<Integer, VerstossStufe> normiereWarnSchwellen(List<?> roheSchwellen) {
        if (roheSchwellen == null || roheSchwellen.isEmpty()) {
            return Map.of();
        }
        Map<Integer, VerstossStufe> ergebnis = new LinkedHashMap<>();
        for (Object roh : roheSchwellen) {
            if (!(roh instanceof Map<?, ?> karte)) {
                continue;
            }
            Object abRoh = karte.get("ab");
            long ab;
            if (abRoh instanceof Number nummer) {
                ab = nummer.longValue();
            } else if (abRoh != null) {
                try {
                    ab = Long.parseLong(String.valueOf(abRoh).trim());
                } catch (NumberFormatException e) {
                    continue;
                }
            } else {
                continue;
            }
            if (ab < 1 || ab > 10_000) {
                continue;
            }
            Object stufeRoh = karte.get("stufe");
            Optional<VerstossStufe> stufe = VerstossStufe.parse(
                    stufeRoh == null ? null : String.valueOf(stufeRoh));
            if (stufe.isEmpty()) {
                continue;
            }
            ergebnis.putIfAbsent((int) ab, stufe.get());
        }
        return Map.copyOf(ergebnis);
    }

    long timeoutMaxMinuten();

    long muteMaxMinuten();

    default long freezeMaxMinuten() {
        return muteMaxMinuten();
    }

    long banMaxMinuten();

    default long banMinMinuten() {
        return timeoutMaxMinuten();
    }

    boolean bestaetigungPardonAll();

    boolean bestaetigungKickAll();

    boolean bestaetigungBanAll();

    default int ipGeteiltWarnAb() {
        return 4;
    }

    default int ipGeteiltBlockAb() {
        return 11;
    }

    default int ipGeteiltBestaetigungAb() {
        return 2;
    }

    default boolean bestaetigungMuteAll() {
        return true;
    }

    default boolean bestaetigungTimeoutAll() {
        return true;
    }

    default boolean bestaetigungUnmuteAll() {
        return true;
    }

    default boolean bestaetigungUntimeoutAll() {
        return true;
    }

    boolean schutzFailClosed();

    boolean offlineAllowNeverSeen();

    ZoneId anzeigeZone();

    int historyEintraegeProSeite();

    long reportCooldownSekunden();

    int reportMaxOffenProReporter();

    long reportDuplikatSperreMinuten();

    List<String> tabDauerListe();

    List<String> tabGrundListe();

    default List<String> grundListe(String befehl) {
        return tabGrundListe();
    }

    default List<String> dauerListe(String befehl) {
        return tabDauerListe();
    }

    default String empfohleneDauer(String befehl, String grund) {
        return null;
    }

    default java.util.Map<String, String> vorlagenFuer(String befehl) {
        return java.util.Map.of();
    }

    static String normiereVorschlagBefehl(String befehl) {
        if (befehl == null) {
            return "";
        }
        String normiert = befehl.trim().toLowerCase(java.util.Locale.ROOT);
        while (normiert.startsWith("/")) {
            normiert = normiert.substring(1).trim();
        }
        return normiert;
    }

    static java.util.List<String> vorschlagBefehlKette(String befehl) {
        String normiert = normiereVorschlagBefehl(befehl);
        if (normiert.isEmpty()) {
            return java.util.List.of();
        }
        if (normiert.endsWith("-ip") && normiert.length() > 3) {
            String basis = normiert.substring(0, normiert.length() - 3);
            if (!basis.isBlank()) {
                return java.util.List.of(normiert, basis);
            }
        }
        return java.util.List.of(normiert);
    }

    List<String> muteBlockierteBefehle();

    String standardGrund(String aktion);

    String screen(String typ);

    boolean broadcastAktiv(String typ);

    String broadcastNachricht(String typ);

    default boolean verpassteBroadcastsAktiv() {
        return true;
    }

    default String bannUnbanAntragLink() {
        return "";
    }

    default String bannUnbanAntragText() {
        return "<green>Unban beantragen";
    }

    default boolean altWacheAktiv() {
        return true;
    }

    default long altWacheCooldownMinuten() {
        return 30L;
    }

    default String verpassteKopf() {
        return "<gray>[<yellow>Verpasst<gray>] <white>%anzahl% <gray>verpasste Team-Nachrichten:";
    }

    default String verpasstePrefix() {
        return "<gray>[<yellow>Verpasst<gray>] ";
    }

    String serverName();

    List<String> bekannteServer();

    de.lightrixx.punish.model.ServerScope standardScope();
}
