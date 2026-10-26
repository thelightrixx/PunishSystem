package de.lightrixx.punish.velocity;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LizenzRessourcenTest {

    @Test
    void drittlizenzHinweiseSindImJar() throws IOException {
        String hinweise = lese("/META-INF/THIRD-PARTY-NOTICES.txt");
        assertTrue(hinweise.contains("HikariCP"), "HikariCP muss gelistet sein");
        assertTrue(hinweise.contains("MySQL Connector/J"), "mysql-connector-j muss gelistet sein");
    }

    @Test
    void apacheLizenztextIstImJar() throws IOException {
        String lizenz = lese("/META-INF/LICENSE-HikariCP.txt");
        assertTrue(lizenz.contains("Apache License"), "Apache-2.0-Text fehlt");
        assertTrue(lizenz.contains("Version 2.0, January 2004"));
    }

    private static String lese(String pfad) throws IOException {
        try (InputStream in = LizenzRessourcenTest.class.getResourceAsStream(pfad)) {
            assertNotNull(in, "Ressource fehlt im Jar: " + pfad);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
