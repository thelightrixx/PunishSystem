package de.lightrixx.punish.storage.mysql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysqlKonfigurationTest {

    @Test
    void defaultsWerdenGesetzt() {
        MysqlKonfiguration config = new MysqlKonfiguration(null, 0, "punishsystem", "punish", null, false, 0);
        assertEquals("127.0.0.1", config.host());
        assertEquals(3306, config.port());
        assertEquals("", config.passwort());
        assertEquals(10, config.poolMax());
    }

    @Test
    void jdbcUrlEnthaeltHostUndDatenbank() {
        MysqlKonfiguration config = new MysqlKonfiguration("db.example", 3307, "punish", "user", "secret",
                false, 5);
        String url = config.jdbcUrl();
        assertTrue(url.startsWith("jdbc:mysql://db.example:3307/punish"));
        assertTrue(url.contains("useSSL=false"));
    }

    @Test
    void jdbcUrlNutztUtf8() {
        MysqlKonfiguration config = new MysqlKonfiguration("db.example", 3306, "punish", "user", "secret",
                true, 5);
        String url = config.jdbcUrl();
        assertTrue(url.contains("useUnicode=true"));
        assertTrue(url.contains("characterEncoding=UTF-8"));
    }

    @Test
    void leereDatenbankWirdAbgelehnt() {
        assertThrows(IllegalArgumentException.class,
                () -> new MysqlKonfiguration("h", 3306, "  ", "user", "", false, 5));
    }

    @Test
    void speicherTypParsing() {
        assertEquals(SpeicherTyp.YAML, SpeicherTyp.ausWert(null));
        assertEquals(SpeicherTyp.YAML, SpeicherTyp.ausWert("yaml"));
        assertEquals(SpeicherTyp.YAML, SpeicherTyp.ausWert("unbekannt"));
        assertEquals(SpeicherTyp.MYSQL, SpeicherTyp.ausWert("mysql"));
        assertEquals(SpeicherTyp.MYSQL, SpeicherTyp.ausWert(" MySQL "));
    }
}
