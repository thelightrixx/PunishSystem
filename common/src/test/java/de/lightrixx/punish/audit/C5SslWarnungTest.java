package de.lightrixx.punish.audit;

import de.lightrixx.punish.storage.mysql.MysqlKonfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class C5SslWarnungTest {

    @Test
    @DisplayName("F5: useSSL=false löst Start-Warnung aus")
    void ohneSslWarnungNoetig() {
        assertTrue(new MysqlKonfiguration("127.0.0.1", 3306, "punish", "user", "pw", false, 5)
                .sslWarnungNoetig(), "ohne TLS muss der Start warnen");
    }

    @Test
    @DisplayName("F5: useSSL=true löst keine Warnung aus")
    void mitSslKeineWarnung() {
        assertFalse(new MysqlKonfiguration("127.0.0.1", 3306, "punish", "user", "pw", true, 5)
                .sslWarnungNoetig());
    }
}
