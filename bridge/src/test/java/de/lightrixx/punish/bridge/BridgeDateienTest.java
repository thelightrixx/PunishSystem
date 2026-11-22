package de.lightrixx.punish.bridge;

import de.lightrixx.punish.bridge.chatfilter.ChatfilterKonfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.bukkit.plugin.java.JavaPlugin;
import de.lightrixx.punish.storage.YamlUtil;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BridgeDateienTest {

    @ParameterizedTest
    @ValueSource(strings = {"general.yml", "messages.yml", "screens.yml", "broadcasts.yml", "gui.yml"})
    void vorhandeneBridgeConfigsErhaltenStandardsUndKommentare(String name, @TempDir Path ordner) throws Exception {
        var plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(ordner.toFile());
        when(plugin.getResource(anyString())).thenAnswer(aufruf ->
                getClass().getResourceAsStream("/" + aufruf.getArgument(0, String.class)));
        Files.createDirectories(ordner.resolve("config"));
        var datei = Files.writeString(ordner.resolve("config").resolve(name),
                "# Eigener Zusatz\neigene-option: 'beibehalten'\n");
        new BridgeKonfiguration(plugin).sicherstellen();
        String neu = Files.readString(datei);
        var yaml = new org.yaml.snakeyaml.Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(
                new org.yaml.snakeyaml.LoaderOptions()));
        var werte = (java.util.Map<?, ?>) yaml.load(neu);
        assertEquals("beibehalten", werte.get("eigene-option"));
        assertTrue(neu.contains("# Eigener Zusatz"));
        try (var in = getClass().getResourceAsStream("/config/" + name)) {
            String vorlage = new String(java.util.Objects.requireNonNull(in).readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            var standards = (java.util.Map<?, ?>) yaml.load(vorlage);
            assertTrue(werte.keySet().containsAll(standards.keySet()));
            assertTrue(neu.contains(vorlage.lines().filter(zeile -> zeile.startsWith("#")).findFirst().orElseThrow()));
        }
        assertEquals("# Eigener Zusatz\neigene-option: 'beibehalten'\n",
                Files.readString(datei.resolveSibling(name + ".vor-config-update.bak")));
        var zeit = Files.getLastModifiedTime(datei);
        new BridgeKonfiguration(plugin).sicherstellen();
        assertEquals(neu, Files.readString(datei));
        assertEquals(zeit, Files.getLastModifiedTime(datei));
    }

    @Test
    void abgleichBehaeltLeereListenNullUndYamlMergeWerte(@TempDir Path ordner) throws Exception {
        String vorlage = "# Neuer Kommentar\nnetzwerk:\n  server-name: standard\n  tunnel-secret: ''\n"
                + "liste: [standard]\nwert: standard\nneu: true\n";
        var datei = Files.writeString(ordner.resolve("general.yml"),
                "basis: &basis\n  server-name: lobby\nnetzwerk:\n  <<: *basis\n"
                        + "  tunnel-secret: lokaler-wert\n  1: nummer\n  '1': text\nliste: []\nwert: null\n");
        assertTrue(new YamlUtil().aktualisiereKonfiguration(datei.toFile(), vorlage));
        var werte = (java.util.Map<?, ?>) new org.yaml.snakeyaml.Yaml(
                new org.yaml.snakeyaml.constructor.SafeConstructor(new org.yaml.snakeyaml.LoaderOptions()))
                .load(Files.readString(datei));
        var netzwerk = (java.util.Map<?, ?>) werte.get("netzwerk");
        assertEquals("lobby", netzwerk.get("server-name"));
        assertEquals("lokaler-wert", netzwerk.get("tunnel-secret"));
        assertEquals("nummer", netzwerk.get(1));
        assertEquals("text", netzwerk.get("1"));
        assertEquals(java.util.List.of(), werte.get("liste"));
        assertTrue(werte.containsKey("wert"));
        assertEquals(null, werte.get("wert"));
        assertEquals(true, werte.get("neu"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "netzwerk: [", "- liste", "wert: eins\nwert: zwei\n"})
    void defekteBridgeConfigsBleibenUnveraendert(String inhalt, @TempDir Path ordner) throws Exception {
        var datei = Files.writeString(ordner.resolve("general.yml"), inhalt);
        assertThrows(RuntimeException.class,
                () -> new YamlUtil().aktualisiereKonfiguration(datei.toFile(), "wert: standard\n"));
        assertEquals(inhalt, Files.readString(datei));
        assertFalse(Files.exists(ordner.resolve("general.yml.vor-config-update.bak")));
    }

    @Test
    void neuesWortWirdNichtAnDieVorherigeZeileGehaengt(@TempDir Path ordner) throws Exception {
        Path wortliste = Files.writeString(ordner.resolve("wortliste.txt"), "vorher");

        BridgeDateien.fuegeWortHinzu(wortliste.toFile(), "nachher");

        assertEquals("vorher" + System.lineSeparator() + "nachher" + System.lineSeparator(),
                Files.readString(wortliste));
    }

    @Test
    void nichtLesbarerDateiStandBleibtVonLeerenDateienUnterscheidbar(@TempDir Path ordner) {
        assertEquals(java.util.Optional.of(""), BridgeDateien.chatfilterStand(ordner.toFile(), null));
        var config = mock(ChatfilterKonfiguration.class);
        when(config.dateiAufloesen(anyString())).thenThrow(new IllegalStateException("Testfehler"));
        when(config.wortlisteDateiname()).thenReturn("wortliste.txt");

        assertTrue(BridgeDateien.chatfilterStand(ordner.toFile(), config).isEmpty());
    }
}
