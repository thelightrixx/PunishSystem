package de.lightrixx.punish.storage.mysql;

import de.lightrixx.punish.model.BanRecord;
import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.storage.BanRepository;
import de.lightrixx.punish.storage.IoExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MysqlBanRepositoryTest {

    private IoExecutor io;
    private MysqlVerbindung mysql;
    private Connection verbindung;
    private Statement statement;
    private PreparedStatement prepared;
    private ResultSet ergebnis;

    @BeforeEach
    void setup() throws Exception {
        io = new IoExecutor();
        mysql = mock(MysqlVerbindung.class);
        verbindung = mock(Connection.class);
        statement = mock(Statement.class);
        prepared = mock(PreparedStatement.class);
        ergebnis = mock(ResultSet.class);
        when(mysql.verbindung()).thenReturn(verbindung);
        when(verbindung.createStatement()).thenReturn(statement);
        when(verbindung.prepareStatement(anyString())).thenReturn(prepared);
        when(verbindung.getAutoCommit()).thenReturn(true);
    }

    @AfterEach
    void teardown() {
        io.herunterfahren(5, TimeUnit.SECONDS);
    }

    @Test
    void ladenLiestBannsAusMysql() throws Exception {
        UUID uuid = UUID.randomUUID();
        when(statement.executeQuery(anyString())).thenReturn(ergebnis);
        when(ergebnis.next()).thenReturn(true, false);
        when(ergebnis.getString("uuid")).thenReturn(uuid.toString());
        when(ergebnis.getString("scope")).thenReturn("global");
        when(ergebnis.getString("spieler_name")).thenReturn("Steve");
        when(ergebnis.getString("grund")).thenReturn("Cheating");
        when(ergebnis.getString("ausfuehrer")).thenReturn("Konsole");
        when(ergebnis.getLong("erstellt_am")).thenReturn(1000L);
        when(ergebnis.getObject("ablauf_am")).thenReturn(null);
        when(ergebnis.getBoolean("auto_bann")).thenReturn(false);

        BanRepository repository = new BanRepository(mysql, io);
        assertTrue(repository.istMysql());
        repository.laden();

        assertEquals(1, repository.alle().size());
        assertTrue(repository.finden(uuid, ServerScope.global()).isPresent());
    }

    @Test
    void bannenSchreibtNachMysql() throws Exception {
        when(statement.executeQuery(anyString())).thenReturn(ergebnis);
        when(ergebnis.next()).thenReturn(false);

        BanRepository repository = new BanRepository(mysql, io);
        repository.laden();
        boolean gesetzt = repository.bannenWennFrei(new BanRecord(UUID.randomUUID(), "Alex",
                "Hacking", "Konsole", System.currentTimeMillis(), null, false, ServerScope.global()));

        assertTrue(gesetzt);
        assertTrue(io.warteAufIdle(5, TimeUnit.SECONDS));
        verify(prepared).executeUpdate();
        verify(verbindung).commit();
    }

    @Test
    void einzelAenderungSchreibtNurEineZeile() throws Exception {
        when(statement.executeQuery(anyString())).thenReturn(ergebnis);
        when(ergebnis.next()).thenReturn(false);

        BanRepository repository = new BanRepository(mysql, io);
        repository.laden();
        UUID uuid = UUID.randomUUID();
        repository.bannenWennFrei(new BanRecord(uuid, "Alex",
                "Hacking", "Konsole", System.currentTimeMillis(), null, false, ServerScope.global()));

        assertTrue(io.warteAufIdle(5, TimeUnit.SECONDS));
        verify(prepared).executeUpdate();
        org.mockito.Mockito.verify(statement, org.mockito.Mockito.never())
                .execute("DELETE FROM " + MysqlSchema.T_BANS);
        org.mockito.Mockito.verify(prepared, org.mockito.Mockito.never()).executeBatch();
    }

    @Test
    void schreibfehlerWirdAlsIoFehlerSichtbar() throws Exception {
        when(statement.executeQuery(anyString())).thenReturn(ergebnis);
        when(ergebnis.next()).thenReturn(false);
        when(prepared.executeUpdate()).thenThrow(new java.sql.SQLException("DB weg"));

        BanRepository repository = new BanRepository(mysql, io);
        repository.laden();
        repository.bannenWennFrei(new BanRecord(UUID.randomUUID(), "Alex",
                "Hacking", "Konsole", System.currentTimeMillis(), null, false, ServerScope.global()));

        assertTrue(io.warteAufIdle(5, TimeUnit.SECONDS));
        assertEquals(1, io.anzahlFehler());
    }

    @Test
    void migrationImportBrauchtMysqlModus() {
        BanRepository repository = new BanRepository(mysql, io);
        assertThrows(NullPointerException.class, () -> repository.migrationImport(null));
        List<BanRecord> leer = List.of();
        assertEquals(0, repository.migrationImport(leer));
    }
}
