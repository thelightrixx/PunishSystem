package de.lightrixx.punish.storage.mysql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MysqlKonfigurationUrlTest {

    @Test
    void datenbankNameMitUrlZeichenWirdAbgelehnt() {
        assertThrows(IllegalArgumentException.class, () -> new MysqlKonfiguration(
                "127.0.0.1", 3306, "db?allowMultiQueries=true", "u", "p", false, 5));
        assertThrows(IllegalArgumentException.class, () -> new MysqlKonfiguration(
                "127.0.0.1", 3306, "db&autoDeserialize=true", "u", "p", false, 5));
        assertThrows(IllegalArgumentException.class, () -> new MysqlKonfiguration(
                "127.0.0.1", 3306, "db/../../x", "u", "p", false, 5));
        assertThrows(IllegalArgumentException.class, () -> new MysqlKonfiguration(
                "127.0.0.1", 3306, "db#x", "u", "p", false, 5));
    }

    @Test
    void hostMitUrlZeichenWirdAbgelehnt() {
        assertThrows(IllegalArgumentException.class, () -> new MysqlKonfiguration(
                "evil.example/x?y", 3306, "db", "u", "p", false, 5));
        assertThrows(IllegalArgumentException.class, () -> new MysqlKonfiguration(
                "evil.example&y", 3306, "db", "u", "p", false, 5));
    }

    @Test
    void publicKeyAbrufNurOhneTls() {
        assertEquals("true", wert(new MysqlKonfiguration(
                "127.0.0.1", 3306, "db", "u", "p", false, 5), "allowPublicKeyRetrieval"));
        assertEquals("false", wert(new MysqlKonfiguration(
                "127.0.0.1", 3306, "db", "u", "p", true, 5), "allowPublicKeyRetrieval"),
                "Mit TLS darf der Public-Key-Abruf nicht nötig sein und öffnet nur eine MITM-Fläche.");
    }

    private static String wert(MysqlKonfiguration konfiguration, String schluessel) {
        for (String teil : konfiguration.jdbcUrl().split("[?&]")) {
            int gleich = teil.indexOf('=');
            if (gleich > 0 && teil.substring(0, gleich).equals(schluessel)) {
                return teil.substring(gleich + 1);
            }
        }
        return "";
    }

    @Test
    void ipv6UndNormaleNamenBleibenGueltig() {
        assertEquals("jdbc:mysql://[::1]:3306/db?useSSL=false", new MysqlKonfiguration(
                "[::1]", 3306, "db", "u", "p", false, 5).jdbcUrl()
                .split("&")[0]);
        assertEquals("jdbc:mysql://db-host:3306/my_db.1?useSSL=false", new MysqlKonfiguration(
                "db-host", 3306, "my_db.1", "u", "p", false, 5).jdbcUrl()
                .split("&")[0]);
    }
}
