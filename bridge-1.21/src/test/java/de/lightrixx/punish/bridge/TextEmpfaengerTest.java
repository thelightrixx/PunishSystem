package de.lightrixx.punish.bridge;

import de.lightrixx.punish.bridge.chatfilter.ChatfilterKonfiguration;
import de.lightrixx.punish.net.Protokoll;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class TextEmpfaengerTest {

    @TempDir
    File datenOrdner;

    private JavaPlugin plugin;
    private ChatfilterKonfiguration cfConfig;
    private TextEmpfaenger empfaenger;
    private final AtomicReference<Boolean> quittung = new AtomicReference<>();
    private final AtomicReference<String> quittungsHash = new AtomicReference<>();
    private final AtomicBoolean neuGeladen = new AtomicBoolean(false);

    @BeforeEach
    void aufsetzen() {
        plugin = Mockito.mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(datenOrdner);
        cfConfig = new ChatfilterKonfiguration(plugin);
        empfaenger = new TextEmpfaenger(plugin, cfConfig, new TextEmpfaenger.Rueckmeldung() {
            @Override
            public void quittung(String paketHash, boolean erfolg) {
                quittungsHash.set(paketHash);
                quittung.set(erfolg);
            }

            @Override
            public void neuLaden() {
                neuGeladen.set(true);
            }
        });
    }

    private static String sha256(byte[] daten) throws Exception {
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        return java.util.HexFormat.of().formatHex(digest.digest(daten));
    }

    private Map<String, byte[]> sendeRunde(Map<String, byte[]> dateien) throws Exception {
        for (var eintrag : dateien.entrySet()) {
            String name = eintrag.getKey();
            byte[] daten = eintrag.getValue();
            empfaenger.empfangeMeta(new Protokoll.TextMeta(name, daten.length, sha256(daten), 8192, 1));
            empfaenger.empfangeBlock(new Protokoll.TextBlock(name, 0, daten));
        }
        return dateien;
    }

    @Test
    void runde_wirdUebernommenUndAngewendet() throws Exception {
        Map<String, byte[]> dateien = new LinkedHashMap<>();
        dateien.put("general.yml", "prefix: neu\n".getBytes(StandardCharsets.UTF_8));
        dateien.put("messages.yml", "messages:\n  a: b\n".getBytes(StandardCharsets.UTF_8));
        dateien.put("screens.yml", "screens:\n  a: b\n".getBytes(StandardCharsets.UTF_8));
        dateien.put("broadcasts.yml", "broadcasts:\n  a: b\n".getBytes(StandardCharsets.UTF_8));
        dateien.put("wortliste.txt", "boesewort\n".getBytes(StandardCharsets.UTF_8));
        sendeRunde(dateien);
        empfaenger.empfangeFertig(new Protokoll.TextFertig(Protokoll.textPaketHash(dateien)));
        assertEquals(Boolean.TRUE, quittung.get());
        assertTrue(neuGeladen.get());
        for (String name : new String[]{"general.yml", "messages.yml", "screens.yml", "broadcasts.yml"}) {
            File cache = new File(new File(datenOrdner, "config-sync"), name);
            assertTrue(cache.isFile(), name);
            assertArrayEquals(dateien.get(name), Files.readAllBytes(cache.toPath()));
        }
        File wortliste = new File(new File(datenOrdner, "chatfilter"), "wortliste.txt");
        assertTrue(wortliste.isFile());
        assertArrayEquals(dateien.get("wortliste.txt"), Files.readAllBytes(wortliste.toPath()));
    }

    @Test
    void falscherPaketHash_wirdVerworfen() throws Exception {
        Map<String, byte[]> dateien = new LinkedHashMap<>();
        dateien.put("messages.yml", "messages:\n  a: b\n".getBytes(StandardCharsets.UTF_8));
        sendeRunde(dateien);
        empfaenger.empfangeFertig(new Protokoll.TextFertig("0".repeat(64)));
        assertEquals(Boolean.FALSE, quittung.get());
        assertFalse(neuGeladen.get());
        assertFalse(new File(new File(datenOrdner, "config-sync"), "messages.yml").exists());
    }

    @Test
    void overlay_proxyGewinntServernameBleibtLokal() throws Exception {
        File config = new File(datenOrdner, "config");
        config.mkdirs();
        Files.writeString(new File(config, "general.yml").toPath(),
                "prefix: \"lokal\"\nnetzwerk:\n  server-name: \"lobby\"\n",
                StandardCharsets.UTF_8);
        for (String name : new String[]{"messages.yml", "screens.yml", "broadcasts.yml", "gui.yml"}) {
            Files.writeString(new File(config, name).toPath(), "x: 1\n", StandardCharsets.UTF_8);
        }
        File sync = new File(datenOrdner, "config-sync");
        sync.mkdirs();
        Files.writeString(new File(sync, "general.yml").toPath(),
                "prefix: \"proxy\"\nnetzwerk:\n  server-liste: []\n",
                StandardCharsets.UTF_8);
        BridgeKonfiguration konfiguration = new BridgeKonfiguration(plugin);
        konfiguration.laden();
        assertEquals("proxy", konfiguration.praefix());
        assertEquals("lobby", konfiguration.serverName());
    }
}
