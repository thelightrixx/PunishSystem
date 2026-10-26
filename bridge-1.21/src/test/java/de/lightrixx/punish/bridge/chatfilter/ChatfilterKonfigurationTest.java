package de.lightrixx.punish.bridge.chatfilter;

import de.lightrixx.punish.bridge.chatfilter.ChatfilterKonfiguration.Modus;
import de.lightrixx.punish.chatfilter.FilterKanal;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class ChatfilterKonfigurationTest {

    @TempDir
    private File datenOrdner;

    private JavaPlugin plugin;

    private ChatfilterKonfiguration konfiguration;

    @BeforeEach
    void aufsetzen() {
        plugin = Mockito.mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(datenOrdner);
        konfiguration = new ChatfilterKonfiguration(plugin);
    }

    private void schreibeConfig(String inhalt) throws Exception {
        File configOrdner = new File(datenOrdner, "config");
        configOrdner.mkdirs();
        Files.writeString(new File(configOrdner, "chatfilter.yml").toPath(), inhalt,
                StandardCharsets.UTF_8);
    }

    @Test
    void werte_werdenGelesen() throws Exception {
        schreibeConfig("""
                chatfilter:
                  aktiv: true
                  schwelle: 0.8
                  ki-timeout-ms: 200
                  max-zeichen: 300
                  bypass-permission: "mein.bypass"
                  kanaele:
                    chat: "ki"
                    privat: "stufe1"
                """);
        konfiguration.laden();
        assertTrue(konfiguration.aktiv());
        assertEquals(0.8d, konfiguration.schwelle());
        assertEquals(200L, konfiguration.kiTimeoutMillis());
        assertEquals(300, konfiguration.maxZeichen());
        assertEquals("mein.bypass", konfiguration.bypassPermission());
        assertEquals(Modus.KI, konfiguration.modusFuer(FilterKanal.CHAT));
        assertEquals(Modus.STUFE1, konfiguration.modusFuer(FilterKanal.PRIVATNACHRICHT));
        assertEquals(Modus.STUFE1, konfiguration.modusFuer(FilterKanal.SCHILD));
    }

    @Test
    void reload_uebernimmtAenderung() throws Exception {
        schreibeConfig("chatfilter:\n  aktiv: true\n  schwelle: 0.7\n");
        konfiguration.laden();
        assertTrue(konfiguration.aktiv());
        schreibeConfig("chatfilter:\n  aktiv: false\n  schwelle: 0.9\n");
        konfiguration.laden();
        assertFalse(konfiguration.aktiv());
        assertEquals(0.9d, konfiguration.schwelle());
        assertEquals(Modus.STUFE1, konfiguration.modusFuer(FilterKanal.CHAT));
    }

    @Test
    void ungueltig_faelltAufDefaults() throws Exception {
        schreibeConfig("chatfilter:\n  aktiv: true\n  schwelle: 2.5\n  kanaele:\n    chat: \"unsinn\"\n");
        konfiguration.laden();
        assertEquals(0.7d, konfiguration.schwelle());
        assertEquals(Modus.STUFE1, konfiguration.modusFuer(FilterKanal.CHAT));
    }

    @Test
    void fehlendeDatei_istDeaktiviert() {
        konfiguration.laden();
        assertFalse(konfiguration.aktiv());
        assertFalse(konfiguration.hatLadeFehler(), "Eine fehlende Datei ist kein Lesefehler.");
    }

    @Test
    void korrupteDatei_setztLadeFehler() throws Exception {
        schreibeConfig("chatfilter: [kaputt\n  aktiv: true\n");
        konfiguration.laden();
        assertTrue(konfiguration.hatLadeFehler(),
                "Eine syntaktisch defekte chatfilter.yml muss als Ladefehler gelten.");
        assertFalse(konfiguration.aktiv(),
                "Nach einem Ladefehler darf der Filter nicht mit Defaults aktiv sein.");
    }

    @Test
    void fehlendeWortliste_wirdAusJarMitgeliefert() throws Exception {
        schreibeConfig("chatfilter:\n  aktiv: true\n");
        byte[] standard = "# test\nboesewort\n".getBytes(StandardCharsets.UTF_8);
        when(plugin.getResource("config/wortliste.txt"))
                .thenReturn(new java.io.ByteArrayInputStream(standard));
        konfiguration.laden();
        File erwartet = new File(new File(datenOrdner, "chatfilter"), "wortliste.txt");
        assertTrue(erwartet.isFile());
        assertArrayEquals(standard, Files.readAllBytes(erwartet.toPath()));
    }

    @Test
    void vorhandeneWortliste_wirdNichtUeberschrieben() throws Exception {
        schreibeConfig("chatfilter:\n  aktiv: true\n");
        File ordner = new File(datenOrdner, "chatfilter");
        ordner.mkdirs();
        File vorhanden = new File(ordner, "wortliste.txt");
        Files.writeString(vorhanden.toPath(), "eigen\n", StandardCharsets.UTF_8);
        konfiguration.laden();
        assertEquals("eigen\n", Files.readString(vorhanden.toPath(), StandardCharsets.UTF_8));
    }

    private void schreibeProxySync(String inhalt) throws Exception {
        File syncOrdner = new File(datenOrdner, "config-sync");
        syncOrdner.mkdirs();
        Files.writeString(new File(syncOrdner, "general.yml").toPath(), inhalt,
                StandardCharsets.UTF_8);
    }

    @Test
    void proxySync_gewinntGegenLokal() throws Exception {
        schreibeConfig("""
                chatfilter:
                  aktiv: true
                  schwelle: 0.8
                  ki-timeout-ms: 200
                  max-zeichen: 300
                  kanaele:
                    chat: "stufe1"
                """);
        schreibeProxySync("""
                chatfilter:
                  schwelle: 0.9
                  ki-timeout-ms: 250
                  max-zeichen: 400
                  kanaele:
                    chat: "ki"
                """);
        konfiguration.laden();
        assertTrue(konfiguration.aktiv());
        assertEquals(0.9d, konfiguration.schwelle());
        assertEquals(250L, konfiguration.kiTimeoutMillis());
        assertEquals(400, konfiguration.maxZeichen());
        assertEquals(Modus.KI, konfiguration.modusFuer(FilterKanal.CHAT));
    }

    @Test
    void ohneProxySync_bleibtLokal() throws Exception {
        schreibeConfig("chatfilter:\n  aktiv: true\n  schwelle: 0.8\n");
        konfiguration.laden();
        assertEquals(0.8d, konfiguration.schwelle());
    }

    @Test
    void dateiAufloesen_relativUndAbsolut() {
        File relativ = konfiguration.dateiAufloesen("wortliste.txt");
        assertEquals(new File(new File(datenOrdner, "chatfilter"), "wortliste.txt"), relativ);
        File absolut = new File(datenOrdner, "anders.txt").getAbsoluteFile();
        assertEquals(absolut, konfiguration.dateiAufloesen(absolut.getAbsolutePath()));
    }
}
