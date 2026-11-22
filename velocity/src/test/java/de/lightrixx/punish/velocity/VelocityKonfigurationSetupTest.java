package de.lightrixx.punish.velocity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class VelocityKonfigurationSetupTest {

    @ParameterizedTest
    @ValueSource(strings = {"general.yml", "messages.yml", "screens.yml", "broadcasts.yml", "gui.yml"})
    void configDateienErhaltenVorlagenKommentareUndBehaltenEigeneSchluessel(String name,
            @org.junit.jupiter.api.io.TempDir Path ordner) throws Exception {
        var dateien = new KonfigurationsDateien(ordner.toFile());
        Files.createDirectories(ordner.resolve("config"));
        var datei = ordner.resolve("config").resolve(name);
        String alt = "# Mein eigener Abschnitt\neigene-option: 'beibehalten'\n";
        Files.writeString(datei, alt);
        dateien.sicherstellen();
        String neu = Files.readString(datei);
        assertEquals("beibehalten", ((Map<?, ?>) KonfigurationsDateien.safeYaml().load(neu)).get("eigene-option"));
        assertTrue(neu.contains("# Mein eigener Abschnitt"));
        try (var in = getClass().getResourceAsStream("/config/" + name)) {
            String vorlage = new String(java.util.Objects.requireNonNull(in).readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            var standards = (Map<?, ?>) KonfigurationsDateien.safeYaml().load(vorlage);
            assertTrue(((Map<?, ?>) KonfigurationsDateien.safeYaml().load(neu)).keySet().containsAll(standards.keySet()));
            assertTrue(neu.contains(vorlage.lines().filter(zeile -> zeile.startsWith("#")).findFirst().orElseThrow()));
        }
        assertEquals(alt, Files.readString(datei.resolveSibling(name + ".vor-config-update.bak")));
        var zeit = Files.getLastModifiedTime(datei);
        dateien.sicherstellen();
        assertEquals(neu, Files.readString(datei));
        assertEquals(zeit, Files.getLastModifiedTime(datei));
    }

    @Test
    void configAbgleichBehaeltWerteListenNullMehrzeilerUndStrafdaten(
            @org.junit.jupiter.api.io.TempDir Path ordner) throws Exception {
        Files.createDirectories(ordner.resolve("config"));
        String alt = """
                prefix: '<blue>Mein Netzwerk'
                vpn-schutz:
                  aktiv: false
                  team-meldungen: false
                  quellen: []
                  freigaben: ['203.0.113.7/32']
                netzwerk:
                  tunnel-secret: ''
                  bridge-secrets: {lobby: 'lokaler-wert'}
                eigene-option:
                  leer: null
                  zahl: 0
                  text: |
                    Erste Zeile mit Umlauten: Äöß
                    Zweite Zeile
                """.replace("\n", "\r\n");
        var datei = Files.writeString(ordner.resolve("config/general.yml"), alt);
        var strafen = Files.writeString(ordner.resolve("bans.yml"), "bestand: beibehalten\n");
        var config = new VelocityKonfiguration(org.mockito.Mockito.mock(
                com.velocitypowered.api.proxy.ProxyServer.class), ordner.toFile());
        config.laden();
        assertFalse(config.vpnSchutzAktiv());
        assertFalse(config.vpnSchutzTeamMeldungen());
        assertEquals(java.util.List.of(), config.vpnSchutzQuellen());
        assertEquals(java.util.List.of("203.0.113.7/32"), config.vpnSchutzFreigaben());
        String neu = Files.readString(datei);
        assertTrue(neu.contains("# Jeder vom VPN-Schutz abgelehnte Login wird geloggt"));
        assertTrue(neu.contains("\r\n"));
        var werte = (Map<?, ?>) KonfigurationsDateien.safeYaml().load(neu);
        var eigene = (Map<?, ?>) werte.get("eigene-option");
        assertTrue(eigene.containsKey("leer"));
        assertEquals(null, eigene.get("leer"));
        assertEquals(0, eigene.get("zahl"));
        assertEquals("Erste Zeile mit Umlauten: Äöß\nZweite Zeile\n", eigene.get("text"));
        assertEquals("lokaler-wert", ((Map<?, ?>) ((Map<?, ?>) werte.get("netzwerk")).get("bridge-secrets")).get("lobby"));
        assertEquals("bestand: beibehalten\n", Files.readString(strafen));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "vpn-schutz: [", "- liste", "prefix: eins\nprefix: zwei\n"})
    void defekteConfigsWerdenNichtUeberschrieben(String inhalt,
            @org.junit.jupiter.api.io.TempDir Path ordner) throws Exception {
        Files.createDirectories(ordner.resolve("config"));
        var datei = Files.writeString(ordner.resolve("config/general.yml"), inhalt);
        new KonfigurationsDateien(ordner.toFile()).sicherstellen();
        assertEquals(inhalt, Files.readString(datei));
        assertFalse(Files.exists(ordner.resolve("config/general.yml.vor-config-update.bak")));
    }

    @Test
    void befehlsUeberschreibungIstStandardmaessigAktivUndAbschaltbar(
            @org.junit.jupiter.api.io.TempDir java.io.File ordner) throws Exception {
        var config = new VelocityKonfiguration(org.mockito.Mockito.mock(
                com.velocitypowered.api.proxy.ProxyServer.class), ordner);
        config.sicherstellen();
        var datei = ordner.toPath().resolve("config/general.yml");
        java.nio.file.Files.writeString(datei, "prefix: '<red>Punish'\n");
        config.laden();
        assertTrue(config.befehleUeberschreiben());

        java.nio.file.Files.writeString(datei, "befehle:\n  ueberschreiben: false\n");
        config.laden();
        assertFalse(config.befehleUeberschreiben());

        java.nio.file.Files.writeString(datei, "befehle:\n  ueberschreiben: true\n");
        config.laden();
        assertTrue(config.befehleUeberschreiben());
    }

    @Test
    void ersetztLeerenBlockUndBehaeltKommentar() {
        String alt = "netzwerk:\n"
                + "  server-name: \"\"\n"
                + "  bridge-secrets: {}\n"
                + "\n"
                + "# --- Zeit\n"
                + "anzeige-zone: UTC\n";

        String neu = VelocityKonfiguration.ersetzeBridgeSecrets(alt, Map.of("lobby", "abc"));

        assertTrue(neu.contains("  bridge-secrets:\n    \"lobby\": \"abc\"\n\n# --- Zeit"),
                "Block muss ersetzt und der Kommentar erhalten bleiben: " + neu);
        assertTrue(neu.contains("anzeige-zone: UTC"));
    }

    @Test
    void ersetztVorhandeneEintraegeUndErhaeltCrlf() {
        String alt = "netzwerk:\r\n"
                + "  bridge-secrets:\r\n"
                + "    \"lobby\": \"alt\"\r\n"
                + "\r\n"
                + "# --- Zeit\r\n";
        Map<String, String> neu = new LinkedHashMap<>();
        neu.put("lobby", "neu");
        neu.put("survival", "zwei");

        String text = VelocityKonfiguration.ersetzeBridgeSecrets(alt, neu);

        assertTrue(text.contains("  bridge-secrets:\r\n"), text);
        assertTrue(text.contains("    \"lobby\": \"neu\"\r\n"), text);
        assertTrue(text.contains("    \"survival\": \"zwei\"\r\n"), text);
        assertTrue(text.contains("\r\n# --- Zeit\r\n"), text);
        assertFalse(text.contains("\"lobby\": \"alt\""), text);
    }

    @Test
    void leereMapSchreibtInlineBlock() {
        String neu = VelocityKonfiguration.ersetzeBridgeSecrets(
                "netzwerk:\n  bridge-secrets: {}\n# x\n", Map.of());

        assertTrue(neu.contains("  bridge-secrets: {}\n# x\n"), neu);
    }
    @Test
    void bestehendeKonfigurationErhaeltHinweiseZumAutomatischenUpdate(
            @org.junit.jupiter.api.io.TempDir java.io.File ordner) throws Exception {
        var config = new VelocityKonfiguration(org.mockito.Mockito.mock(
                com.velocitypowered.api.proxy.ProxyServer.class), ordner);
        config.sicherstellen();
        java.nio.file.Files.writeString(ordner.toPath().resolve("config/messages.yml"),
                "messages:\n  update-erfolgreich-neustart: 'Nur neustarten'\n"
                        + "  update-bereitgestellt: 'JARs manuell austauschen'\n");
        config.laden();
        org.junit.jupiter.api.Assertions.assertEquals("Nur neustarten", config.nachricht(
                de.lightrixx.punish.core.MessageKeys.UPDATE_ERFOLGREICH_NEUSTART));
        String hinweis = config.nachricht(de.lightrixx.punish.core.MessageKeys.UPDATE_INSTALLATION_VORGEMERKT);
        assertTrue(hinweis.contains("automatisch"));
        assertTrue(hinweis.contains("status"));
        String einrichtung = config.nachricht(de.lightrixx.punish.core.MessageKeys.UPDATE_START_HOOK_FEHLT);
        assertTrue(einrichtung.contains("noch nicht aktiv"));
        assertTrue(einrichtung.contains("sh plugins/update-pending/apply-update.sh"));
    }
}
