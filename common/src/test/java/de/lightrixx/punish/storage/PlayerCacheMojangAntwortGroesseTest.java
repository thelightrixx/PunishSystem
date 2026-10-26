package de.lightrixx.punish.storage;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlayerCacheMojangAntwortGroesseTest {

    @Test
    void kleineAntwortWirdGelesen() throws Exception {
        byte[] inhalt = "{\"id\":\"069a79f444e94726a5befca90e38aaf5\"}".getBytes(StandardCharsets.UTF_8);
        assertEquals(new String(inhalt, StandardCharsets.UTF_8),
                PlayerCache.leseBegrenzt(new ByteArrayInputStream(inhalt), 8192));
    }

    @Test
    void zuGrosseAntwortWirdAbgewiesen() {
        InputStream zuGross = new ByteArrayInputStream(new byte[9000]);
        assertThrows(IOException.class,
                () -> PlayerCache.leseBegrenzt(zuGross, 8192),
                "Unbegrenztes Lesen einer HTTP-Antwort ermöglicht Speicher-DoS; das Limit muss greifen.");
    }
}
