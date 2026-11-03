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

        org.junit.jupiter.api.Assertions.assertFalse(io.warteAufIdle(200, TimeUnit.MILLISECONDS));
        assertTrue(io.anzahlFehler() >= 1);
        org.junit.jupiter.api.Assertions.assertFalse(io.istGesund());
        org.junit.jupiter.api.Assertions.assertFalse(io.herunterfahren(0, TimeUnit.SECONDS));
    }

    @Test
    void migrationImportBrauchtMysqlModus() {
        BanRepository repository = new BanRepository(mysql, io);
        assertThrows(NullPointerException.class, () -> repository.migrationImport(null));
        List<BanRecord> leer = List.of();
        assertEquals(0, repository.migrationImport(leer));
    }

    @Test
    void commitAbbruchWirdZurueckgerolltUndVorAufhebungWiederholt() throws Exception {
        var commits = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(inv -> {
            if (commits.incrementAndGet() == 1) {
                throw new java.sql.SQLException("Verbindung beim Commit getrennt", "08006");
            }
            return null;
        }).when(verbindung).commit();
        var repository = new BanRepository(mysql, io);
        var uuid = UUID.randomUUID();
        assertTrue(repository.bannenWennFrei(new BanRecord(uuid, "Spieler", "Grund", "Mod",
                1L, null, false, ServerScope.global())));
        assertTrue(repository.entbannen(uuid, ServerScope.global()).isPresent());
        assertTrue(io.warteAufIdle(5, TimeUnit.SECONDS));
        var reihenfolge = org.mockito.Mockito.inOrder(verbindung, prepared);
        reihenfolge.verify(prepared).executeUpdate();
        reihenfolge.verify(verbindung).commit();
        reihenfolge.verify(verbindung).rollback();
        reihenfolge.verify(verbindung).close();
        reihenfolge.verify(prepared).executeUpdate();
        reihenfolge.verify(verbindung).commit();
        reihenfolge.verify(prepared).executeUpdate();
        assertEquals(2, commits.get());
        assertEquals(1, io.anzahlFehler());
    }
    @Test
    void voruebergehenderSqlFehlerWirdVorSpaetererAufhebungWiederholt() throws Exception {
        var loeschen = mock(PreparedStatement.class);
        when(verbindung.prepareStatement(anyString())).thenAnswer(inv ->
                inv.<String>getArgument(0).startsWith("DELETE") ? loeschen : prepared);
        var reihenfolge = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var versuche = new java.util.concurrent.atomic.AtomicInteger();
        when(prepared.executeUpdate()).thenAnswer(inv -> {
            if (versuche.incrementAndGet() == 1) {
                throw new java.sql.SQLException("Vorübergehend nicht erreichbar", "08006");
            }
            reihenfolge.add("bannen");
            return 1;
        });
        when(loeschen.executeUpdate()).thenAnswer(inv -> {
            reihenfolge.add("entbannen");
            return 1;
        });
        var repository = new BanRepository(mysql, io);
        var uuid = UUID.randomUUID();
        assertTrue(repository.bannenWennFrei(new BanRecord(uuid, "Steve", "Test", "Mod",
                1L, null, false, ServerScope.global())));
        assertTrue(repository.entbannen(uuid, ServerScope.global()).isPresent());
        assertTrue(io.warteAufIdle(5, TimeUnit.SECONDS));
        assertEquals(List.of("bannen", "entbannen"), reihenfolge);
        assertEquals(2, versuche.get());
        assertEquals(1, io.anzahlFehler());
        assertTrue(repository.finden(uuid, ServerScope.global()).isEmpty());
    }
}
