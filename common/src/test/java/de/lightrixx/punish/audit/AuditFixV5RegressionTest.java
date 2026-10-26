package de.lightrixx.punish.audit;

import de.lightrixx.punish.model.MuteRecord;
import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.storage.IoExecutor;
import de.lightrixx.punish.storage.LogWriter;
import de.lightrixx.punish.storage.MuteRepository;
import de.lightrixx.punish.storage.YamlSpeicher;
import de.lightrixx.punish.util.MiniMessageUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AuditFixV5RegressionTest {

    static final class FakeSpeicher implements YamlSpeicher {
        @Override
        public Map<String, Object> laden(File datei) {
            return Map.of();
        }

        @Override
        public void speichern(Map<String, Object> daten, File datei) {
        }
    }

    private IoExecutor io;

    @AfterEach
    void shutdown() {
        if (io != null) {
            try {
                io.herunterfahren(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
            io = null;
        }
    }

    @Test
    void logWriterSaniertAnsiUndSteuerzeichen(@TempDir File temp) throws Exception {
        io = new IoExecutor();
        LogWriter writer = new LogWriter(temp, io);
        Method sanieren = LogWriter.class.getDeclaredMethod("sanieren", String.class);
        sanieren.setAccessible(true);
        String dreckig = "Hallo\nWelt\r\u001B[2J\u0000Test\u007F!";
        String sauber = (String) sanieren.invoke(null, dreckig);
        assertFalse(sauber.contains("\n"), "kein newline im Log");
        assertFalse(sauber.contains("\r"), "kein CR im Log");
        assertFalse(sauber.contains("\u001B"), "kein ESC im Log");
        assertFalse(sauber.contains("\u0000"), "kein NUL im Log");
        assertFalse(sauber.contains("\u007F"), "kein DEL im Log");
        assertTrue(sauber.contains("Hallo"), "Inhalt bleibt erhalten");
        assertTrue(sauber.contains("Test"), "Inhalt bleibt erhalten");
        writer.schliessen();
    }

    @Test
    void miniMessageEscapedSpielerInput() {
        String vorlage = "<red>%grund%</red>";
        String boese = "<click:run_command:/op>klick</click> <red>rot";
        String ersetzt = MiniMessageUtil.ersetzen(vorlage, Map.of("grund", boese));
        assertTrue(ersetzt.contains("\\<"), "Spieler-Tags müssen mit Backslash escaped sein");
        assertTrue(ersetzt.startsWith("<red>"), "Vorlagen-Tags bleiben erhalten");
        assertTrue(ersetzt.endsWith("</red>") || ersetzt.endsWith("<red>"), "Vorlagen-Rahmen bleibt erhalten");
    }

    @Test
    void muteFindeAbdeckendNutztDirektLookup(@TempDir File temp) {
        io = new IoExecutor();
        MuteRepository repo = new MuteRepository(temp, new FakeSpeicher(), io);
        UUID uuid = UUID.randomUUID();
        long jetzt = System.currentTimeMillis();
        MuteRecord global = new MuteRecord(uuid, "Steve", "Spam", "Mod",
                jetzt, jetzt + 3_600_000L, ServerScope.global());
        assertTrue(repo.mutenWennFrei(global));
        Optional<MuteRecord> aufLobby = repo.findeAbdeckend(uuid, ServerScope.server("lobby"), jetzt);
        assertTrue(aufLobby.isPresent(), "global muss server-scopes abdecken (O(1)-Pfad)");
        assertEquals("Spam", aufLobby.get().grund());
        Optional<MuteRecord> exaktGlobal = repo.findeAbdeckend(uuid, ServerScope.global(), jetzt);
        assertTrue(exaktGlobal.isPresent(), "exakter global-Lookup muss treffen");
    }

    @Test
    void verstossHatRechtIstNullSicher() {
        var messageService = org.mockito.Mockito.mock(de.lightrixx.punish.service.MessageService.class);
        var config = org.mockito.Mockito.mock(de.lightrixx.punish.core.Konfiguration.class);
        var protection = org.mockito.Mockito.mock(de.lightrixx.punish.service.ProtectionService.class);
        var cache = org.mockito.Mockito.mock(de.lightrixx.punish.storage.PlayerCache.class);
        var service = org.mockito.Mockito.mock(de.lightrixx.punish.service.VerstossService.class);
        var verzeichnis = org.mockito.Mockito.mock(de.lightrixx.punish.core.SpielerVerzeichnis.class);
        var cmd = new de.lightrixx.punish.command.VerstossCommand(
                messageService, config, protection, cache, service, null, verzeichnis);
        assertFalse(cmd.hatRecht(null, new String[]{"add"}), "sender==null darf nie Recht haben");
        assertFalse(cmd.hatRecht(null, new String[0]), "sender==null darf nie Recht haben");
    }

    @Test
    void historyUndStatusHatRechtIstNullSicher() {
        var messageService = org.mockito.Mockito.mock(de.lightrixx.punish.service.MessageService.class);
        var config = org.mockito.Mockito.mock(de.lightrixx.punish.core.Konfiguration.class);
        var protection = org.mockito.Mockito.mock(de.lightrixx.punish.service.ProtectionService.class);
        var cache = org.mockito.Mockito.mock(de.lightrixx.punish.storage.PlayerCache.class);
        var verzeichnis = org.mockito.Mockito.mock(de.lightrixx.punish.core.SpielerVerzeichnis.class);
        var historyService = org.mockito.Mockito.mock(de.lightrixx.punish.service.HistoryService.class);
        var historyCmd = new de.lightrixx.punish.command.PunishHistoryCommand(
                messageService, config, protection, cache, historyService, verzeichnis);
        assertFalse(historyCmd.hatRecht(null, new String[]{"self"}));
        var verstossService = org.mockito.Mockito.mock(de.lightrixx.punish.service.VerstossService.class);
        var banRepository = org.mockito.Mockito.mock(de.lightrixx.punish.storage.BanRepository.class);
        var muteRepository = org.mockito.Mockito.mock(de.lightrixx.punish.storage.MuteRepository.class);
        var timeoutRepository = org.mockito.Mockito.mock(de.lightrixx.punish.storage.TimeoutRepository.class);
        var ipBanRepository = org.mockito.Mockito.mock(de.lightrixx.punish.storage.IpBanRepository.class);
        var verstossRepository = org.mockito.Mockito.mock(de.lightrixx.punish.storage.VerstossRepository.class);
        var statusCmd = new de.lightrixx.punish.command.PunishStatusCommand(
                messageService, config, protection, cache,
                verstossService, banRepository, muteRepository, timeoutRepository,
                ipBanRepository, verstossRepository, verzeichnis);
        assertFalse(statusCmd.hatRecht(null, new String[]{"self"}));
    }
}
