package de.lightrixx.punish.audit;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeModulParitaetTest {

    private static final String PAKET = "de/lightrixx/punish/bridge/";

    private static final Set<String> ERLAUBTE_UNTERSCHIEDE = Set.of(
            PAKET + "BanCache.java",
            PAKET + "BridgeBrigadierBefehle.java",
            PAKET + "BridgeDiagnosticsBefehl.java",
            PAKET + "BridgeKonfiguration.java",
            PAKET + "FreezeCache.java",
            PAKET + "IpMuteCache.java",
            PAKET + "MuteCache.java",
            PAKET + "PunishBridge.java",
            PAKET + "TimeoutCache.java",
            PAKET + "gui/GuiDatenDienst.java",
            PAKET + "gui/GuiSitzung.java",
            PAKET + "papi/PlatzhalterCache.java");

    @Test
    void nurErlaubteHauptklassenWeichenAb() throws IOException {
        Path wurzel = wurzel();
        Map<String, String> basis = lese(wurzel.resolve("bridge/src/main/java"));
        Map<String, String> einEinundzwanzig = lese(wurzel.resolve("bridge-1.21/src/main/java"));

        assertEquals(basis.keySet(), einEinundzwanzig.keySet(),
                "Beide Bridge-Module müssen denselben Klassensatz haben.");

        List<String> abweichend = basis.keySet().stream()
                .filter(pfad -> !basis.get(pfad).equals(einEinundzwanzig.get(pfad)))
                .sorted()
                .toList();
        List<String> unerwartet = abweichend.stream()
                .filter(pfad -> !ERLAUBTE_UNTERSCHIEDE.contains(pfad))
                .toList();
        assertTrue(unerwartet.isEmpty(),
                "Neue Drift zwischen bridge und bridge-1.21: " + unerwartet);
    }

    /**
     * Gleiche Nachrichtenschluessel muessen in allen drei ausgelieferten Bundles denselben
     * Text haben. Sonst haengt die Ausgabe eines Befehls davon ab, ob der Proxy oder eine
     * Bridge sie rendert, und Textfehler bleiben in einem Bundle unbemerkt.
     */
    @Test
    void messagesWerteSindUeberAlleModuleKonsistent() throws IOException {
        Path wurzel = wurzel();
        Map<String, String> velocity = leseNachrichten(
                wurzel.resolve("velocity/src/main/resources/config/messages.yml"));
        Map<String, String> bridge = leseNachrichten(
                wurzel.resolve("bridge/src/main/resources/config/messages.yml"));
        Map<String, String> bridge121 = leseNachrichten(
                wurzel.resolve("bridge-1.21/src/main/resources/config/messages.yml"));

        assertFalse(velocity.isEmpty(), "messages.yml ohne Nachrichten gelesen.");

        List<String> abweichend = new ArrayList<>();
        for (Map.Entry<String, String> eintrag : velocity.entrySet()) {
            vergleiche(abweichend, "bridge", eintrag.getKey(), eintrag.getValue(),
                    bridge.get(eintrag.getKey()));
            vergleiche(abweichend, "bridge-1.21", eintrag.getKey(), eintrag.getValue(),
                    bridge121.get(eintrag.getKey()));
        }
        assertTrue(abweichend.isEmpty(),
                "Abweichende Nachrichtentexte zwischen den Modulen: " + abweichend);
    }

    private static void vergleiche(List<String> abweichend, String modul, String schluessel,
                                   String erwartet, String tatsaechlich) {
        if (tatsaechlich != null && !erwartet.equals(tatsaechlich)) {
            abweichend.add(modul + "/" + schluessel + ": " + tatsaechlich);
        }
    }

    private static Map<String, String> leseNachrichten(Path datei) throws IOException {
        Map<String, String> nachrichten = new LinkedHashMap<>();
        for (String zeile : Files.readAllLines(datei, StandardCharsets.UTF_8)) {
            if (!zeile.startsWith("  ") || zeile.startsWith("   ") || zeile.trim().startsWith("#")) {
                continue;
            }
            int trenner = zeile.indexOf(':', 2);
            if (trenner < 0) {
                continue;
            }
            nachrichten.put(zeile.substring(2, trenner).trim(),
                    zeile.substring(trenner + 1).trim());
        }
        return nachrichten;
    }

    private static Map<String, String> lese(Path basis) throws IOException {
        Map<String, String> dateien = new LinkedHashMap<>();
        try (Stream<Path> pfade = Files.walk(basis)) {
            for (Path pfad : pfade.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .sorted()
                    .toList()) {
                String relativ = basis.relativize(pfad).toString().replace('\\', '/');
                dateien.put(relativ,
                        Files.readString(pfad, StandardCharsets.UTF_8).replace("\r\n", "\n"));
            }
        }
        return dateien;
    }

    private static Path wurzel() {
        Path start = Path.of("").toAbsolutePath();
        if (Files.isDirectory(start.resolve("bridge"))
                && Files.isDirectory(start.resolve("bridge-1.21"))) {
            return start;
        }
        Path eltern = start.getParent();
        if (eltern != null && Files.isDirectory(eltern.resolve("bridge"))
                && Files.isDirectory(eltern.resolve("bridge-1.21"))) {
            return eltern;
        }
        throw new IllegalStateException("Projektwurzel mit bridge und bridge-1.21 nicht gefunden: " + start);
    }
}
