package de.lightrixx.punish.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class LegacyMigrationVollstaendigTest {

    @TempDir
    Path temp;

    @Test
    void migrationErhaeltDatenschutzUndRangGewicht() throws Exception {
        Path daten = temp.resolve("daten");
        Files.createDirectories(daten);
        Files.writeString(daten.resolve("config.yml"), """
                datenschutz:
                  ip-aufbewahrung-tage: 7
                rang-gewicht:
                  vip: 50
                limits:
                  max-ban: 30d
                """, StandardCharsets.UTF_8);

        VelocityKonfiguration konfiguration = new VelocityKonfiguration(mock(ProxyServer.class), daten.toFile());
        konfiguration.migriereLegacyFallsNoetig();

        Path general = daten.resolve("config/general.yml");
        assertTrue(Files.isRegularFile(general), "general.yml muss angelegt werden");
        Object geladen = new Yaml().load(Files.readString(general, StandardCharsets.UTF_8));
        assertTrue(geladen instanceof Map);
        Map<?, ?> karte = (Map<?, ?>) geladen;
        assertEquals(Map.of("ip-aufbewahrung-tage", 7), karte.get("datenschutz"));
        assertEquals(Map.of("vip", 50), karte.get("rang-gewicht"));
        assertEquals(Map.of("max-ban", "30d"), karte.get("limits"));
    }
}
