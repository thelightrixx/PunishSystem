package de.lightrixx.punish.audit;

import de.lightrixx.punish.model.IpBanRecord;
import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.storage.IoExecutor;
import de.lightrixx.punish.storage.IpBanRepository;
import de.lightrixx.punish.storage.YamlSpeicher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IpPermRaceTest {

    @Test
    void parallelePermBannsGleicherScopeEinRecord(@TempDir File ordner) throws Exception {
        YamlSpeicher speicher = new YamlSpeicher() {
            @Override
            public Map<String, Object> laden(File datei) {
                return Map.of();
            }

            @Override
            public void speichern(Map<String, Object> daten, File datei) {
            }
        };
        IoExecutor io = new IoExecutor();
        try {
            IpBanRepository repo = new IpBanRepository(ordner, speicher, io);
            repo.laden();
            int threads = 8;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch fertig = new CountDownLatch(threads);
            AtomicInteger erfolge = new AtomicInteger();
            List<Throwable> fehler = java.util.Collections.synchronizedList(new ArrayList<>());
            for (int i = 0; i < threads; i++) {
                final int nr = i;
                new Thread(() -> {
                    try {
                        start.await();
                        long jetzt = System.currentTimeMillis();
                        if (repo.bannenWennExaktFrei(new IpBanRecord("192.0.2.70",
                                "Grund" + nr, "Mod" + nr, jetzt, null, ServerScope.global()))) {
                            erfolge.incrementAndGet();
                        }
                    } catch (Throwable t) {
                        fehler.add(t);
                    } finally {
                        fertig.countDown();
                    }
                }).start();
            }
            start.countDown();
            assertTrue(fertig.await(10, TimeUnit.SECONDS), "Threads müssen fertig werden");
            assertTrue(fehler.isEmpty(), () -> "Worker-Fehler dürfen nicht maskiert werden: " + fehler);
            assertEquals(1, erfolge.get(), "Nur ein paralleler Perm-Bann darf gewinnen");
            assertEquals(1, repo.alleFuer("192.0.2.70").size(), "Nur ein Record je exaktem Schlüssel");
        } finally {
            io.herunterfahren(5, TimeUnit.SECONDS);
        }
    }
}
