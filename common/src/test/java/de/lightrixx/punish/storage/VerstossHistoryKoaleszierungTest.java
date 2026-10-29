package de.lightrixx.punish.storage;

import de.lightrixx.punish.model.HistoryType;
import de.lightrixx.punish.model.VerstossStufe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class VerstossHistoryKoaleszierungTest {

    static final class FakeSpeicher implements YamlSpeicher {
        final Map<String, Map<String, Object>> dateien = new ConcurrentHashMap<>();

        @Override
        public Map<String, Object> laden(File datei) {
            Map<String, Object> daten = dateien.get(datei.getAbsolutePath());
            return daten == null ? Map.of() : daten;
        }

        @Override
        public void speichern(Map<String, Object> daten, File datei) {
            dateien.put(datei.getAbsolutePath(), daten);
        }
    }

    @TempDir
    File temp;

    private IoExecutor ioExecutor;
    private FakeSpeicher speicher;

    @BeforeEach
    void setup() {
        ioExecutor = new IoExecutor();
        speicher = new FakeSpeicher();
    }

    @AfterEach
    void teardown() {
        ioExecutor.herunterfahren(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Verstoß-Save wird nicht durch den History-Save derselben UUID verworfen")
    void historySaveVerwirftVerstoessSaveNicht() throws Exception {
        VerstossRepository verstossRepository = new VerstossRepository(temp, speicher, ioExecutor);
        HistoryRepository historyRepository = new HistoryRepository(temp, speicher, ioExecutor);
        UUID uuid = UUID.randomUUID();

        CountDownLatch blockiert = new CountDownLatch(1);
        CountDownLatch angekommen = new CountDownLatch(1);
        ioExecutor.ausfuehren(() -> {
            angekommen.countDown();
            try {
                blockiert.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(angekommen.await(5, TimeUnit.SECONDS),
                "IO-Thread muss den blockierten Auftrag angenommen haben.");

        verstossRepository.hinzufuegen(uuid, "Steve", "Spam", "Mod", VerstossStufe.MEDIUM);
        historyRepository.hinzufuegenNeu(HistoryType.VERSTOSS_ADD, "Mod", uuid, "Steve", "Spam", null);

        blockiert.countDown();
        assertTrue(ioExecutor.warteAufIdle(5, TimeUnit.SECONDS));

        File verstoessDatei = new File(new File(temp, "verstoesse"), uuid + ".yml");
        File historyDatei = new File(new File(temp, "history"), uuid + ".yml");
        assertTrue(speicher.dateien.containsKey(verstoessDatei.getAbsolutePath()),
                "Verstoß-Datei muss persistiert werden, obwohl direkt danach ein History-Save geplant wurde.");
        assertTrue(speicher.dateien.containsKey(historyDatei.getAbsolutePath()),
                "History-Datei muss persistiert werden.");
    }
}
