package de.lightrixx.punish.velocity;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VelocityKonfigurationSetupTest {

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
    void bestehendeKonfigurationErhaeltNeuenHinweisZurManuellenUpdateInstallation(
            @org.junit.jupiter.api.io.TempDir java.io.File ordner) throws Exception {
        var config = new VelocityKonfiguration(org.mockito.Mockito.mock(
                com.velocitypowered.api.proxy.ProxyServer.class), ordner);
        config.sicherstellen();
        java.nio.file.Files.writeString(ordner.toPath().resolve("config/messages.yml"),
                "messages:\n  update-erfolgreich-neustart: 'Nur neustarten'\n");
        config.laden();
        org.junit.jupiter.api.Assertions.assertEquals("Nur neustarten", config.nachricht(
                de.lightrixx.punish.core.MessageKeys.UPDATE_ERFOLGREICH_NEUSTART));
        String hinweis = config.nachricht(de.lightrixx.punish.core.MessageKeys.UPDATE_BEREITGESTELLT);
        assertTrue(hinweis.contains("Vor dem Neustart"));
        assertTrue(hinweis.contains("ersetzen"));
        assertTrue(hinweis.contains("update-pending"));
    }
}
