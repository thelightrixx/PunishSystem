package de.lightrixx.punish.bridge;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class VorschlagListenTest {

    @TempDir
    private File datenOrdner;

    private BridgeKonfiguration ladenMitSync(String syncGeneral) throws Exception {
        JavaPlugin plugin = Mockito.mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(datenOrdner);
        File configOrdner = new File(datenOrdner, "config");
        configOrdner.mkdirs();
        Files.writeString(new File(configOrdner, "general.yml").toPath(),
                "netzwerk:\n  server-name: \"\"\n  server-liste: []\n",
                StandardCharsets.UTF_8);
        if (syncGeneral != null) {
            File syncOrdner = new File(datenOrdner, TextEmpfaenger.SYNC_ORDNER);
            syncOrdner.mkdirs();
            Files.writeString(new File(syncOrdner, "general.yml").toPath(), syncGeneral,
                    StandardCharsets.UTF_8);
        }
        BridgeKonfiguration konfiguration = new BridgeKonfiguration(plugin);
        konfiguration.laden();
        return konfiguration;
    }

    @Test
    @DisplayName("Proxy-Vorschläge gewinnen gegen den Bridge-Default")
    void proxyVorschlaegeGewinnen() throws Exception {
        BridgeKonfiguration konfiguration = ladenMitSync("""
                vorschlaege:
                  ban:
                    gruende: ["SyncBanGrund"]
                    dauern: ["9d"]
                  mute:
                    gruende: ["SyncMuteGrund"]
                """);
        assertEquals(List.of("SyncBanGrund"), konfiguration.grundListe("ban"));
        assertEquals(List.of("9d"), konfiguration.dauerListe("ban"));
        assertEquals(List.of("SyncMuteGrund"), konfiguration.grundListe("mute"));
    }

    @Test
    @DisplayName("Ohne Sync greifen eingebaute Standardlisten")
    void ohneSyncGreiftCodeFallback() throws Exception {
        BridgeKonfiguration konfiguration = ladenMitSync(null);
        assertEquals(
                List.of("Beleidigung", "Spamming", "Cheating", "Teaming", "Bugusing", "Hacking"),
                konfiguration.grundListe("ban"));
        assertEquals(List.of("10m", "1h", "6h", "1d", "7d", "30d", "perm"),
                konfiguration.dauerListe("ban"));
    }
}
