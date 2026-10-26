package de.lightrixx.punish.storage.mysql;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MysqlSchemaTest {

    @Test
    void alleTabellenSindRegistriert() {
        assertEquals(17, MysqlSchema.ALLE_TABELLEN.size());
        for (String tabelle : List.of("punish_bans", "punish_mutes", "punish_timeouts", "punish_freezes",
                "punish_ipbans", "punish_ipmutes", "punish_iptimeouts",
                "punish_warns", "punish_verstoesse", "punish_notizen",
                "punish_reports", "punish_history", "punish_history_archiv", "punish_spieler",
                "punish_ip_historie", "punish_missed_broadcasts", "punish_missed_cursor")) {
            assertTrue(MysqlSchema.ALLE_TABELLEN.contains(tabelle), "Fehlt: " + tabelle);
        }
        assertFalse(MysqlSchema.ALLE_TABELLEN.contains(MysqlSchema.T_META),
                "Meta-Tabelle darf nicht in der Leerstands-Prüfung enthalten sein.");
    }

    @Test
    void initialisierenErstelltAlleTabellen() throws Exception {
        MysqlVerbindung mysql = mock(MysqlVerbindung.class);
        Connection verbindung = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(mysql.verbindung()).thenReturn(verbindung);
        when(verbindung.createStatement()).thenReturn(statement);
        mitMetaTabelle(verbindung, null);

        MysqlSchema.initialisieren(mysql);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(statement, org.mockito.Mockito.times(18)).execute(sql.capture());
        List<String> alle = new ArrayList<>(sql.getAllValues());
        for (String tabelle : MysqlSchema.ALLE_TABELLEN) {
            boolean gefunden = alle.stream().anyMatch(s -> s.contains("CREATE TABLE IF NOT EXISTS " + tabelle));
            assertTrue(gefunden, "Kein CREATE für: " + tabelle);
        }
        assertTrue(alle.stream().anyMatch(s -> s.contains("CREATE TABLE IF NOT EXISTS " + MysqlSchema.T_META)),
                "Kein CREATE für Meta-Tabelle");
        for (String ddl : alle) {
            assertTrue(ddl.contains("utf8mb4"), "Kein utf8mb4 in: " + ddl.substring(0, 60));
        }
    }

    @Test
    void migrationVonVersionEinsFuelltIpHistorie() throws Exception {
        MysqlVerbindung mysql = mock(MysqlVerbindung.class);
        Connection verbindung = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(mysql.verbindung()).thenReturn(verbindung);
        when(verbindung.createStatement()).thenReturn(statement);
        mitMetaTabelle(verbindung, "1");

        MysqlSchema.initialisieren(mysql);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(statement).executeUpdate(sql.capture());
        assertTrue(sql.getValue().contains("INSERT INTO " + MysqlSchema.T_IP_HISTORIE),
                "Migration füllt die IP-Historie: " + sql.getValue());
        assertTrue(sql.getValue().contains(MysqlSchema.T_SPIELER),
                "Migration liest den bestehenden Spieler-Cache: " + sql.getValue());
    }

    @Test
    void abweichendeSchemaVersionVerweigertStart() throws Exception {
        MysqlVerbindung mysql = mock(MysqlVerbindung.class);
        Connection verbindung = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(mysql.verbindung()).thenReturn(verbindung);
        when(verbindung.createStatement()).thenReturn(statement);
        mitMetaTabelle(verbindung, "99");

        IllegalStateException fehler = assertThrows(IllegalStateException.class,
                () -> MysqlSchema.initialisieren(mysql));
        assertTrue(fehler.getMessage().contains("99"), "Meldung nennt vorhandene Version: " + fehler.getMessage());
    }

    @Test
    void migrationsStatusWirdGelesenUndGesetzt() throws Exception {
        MysqlVerbindung mysql = mock(MysqlVerbindung.class);
        Connection verbindung = mock(Connection.class);
        when(mysql.verbindung()).thenReturn(verbindung);
        PreparedStatement select = mock(PreparedStatement.class);
        ResultSet ergebnis = mock(ResultSet.class);
        when(verbindung.prepareStatement(anyString())).thenReturn(select);
        when(select.executeQuery()).thenReturn(ergebnis);
        when(ergebnis.next()).thenReturn(true, false);
        when(ergebnis.getString(1)).thenReturn(MysqlSchema.MIGRATION_LAEUFT);

        assertTrue(MysqlSchema.migrationOffen(mysql), "Status 'running' gilt als offene Migration");

        MysqlSchema.markiereMigration(mysql, MysqlSchema.MIGRATION_FERTIG);
        verify(select).executeUpdate();
    }

    /**
     * Mockt die Meta-Tabelle: Liefert {@link vorhandeneVersion} oder "keine Zeile" (null),
     * wenn diese null ist (frische Installation).
     */
    private static void mitMetaTabelle(Connection verbindung, String vorhandeneVersion) throws Exception {
        PreparedStatement select = mock(PreparedStatement.class);
        ResultSet ergebnis = mock(ResultSet.class);
        PreparedStatement upsert = mock(PreparedStatement.class);
        when(verbindung.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            return sql.startsWith("SELECT") ? select : upsert;
        });
        when(select.executeQuery()).thenReturn(ergebnis);
        if (vorhandeneVersion == null) {
            when(ergebnis.next()).thenReturn(false);
        } else {
            when(ergebnis.next()).thenReturn(true, false);
            when(ergebnis.getString(1)).thenReturn(vorhandeneVersion);
        }
    }
}
