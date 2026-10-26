package de.lightrixx.punish.audit;

import com.sun.net.httpserver.HttpServer;
import de.lightrixx.punish.velocity.ModellVerteilung;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNull;

class C4HttpsPflichtTest {

    @Test
    @DisplayName("F4: Download über http:// muss abgelehnt werden (HTTPS-Pflicht)")
    void httpWirdAbgelehnt() throws Exception {
        java.util.concurrent.atomic.AtomicInteger anfragen =
                new java.util.concurrent.atomic.AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/modell.bin", austausch -> {
            anfragen.incrementAndGet();
            byte[] koerper = "ersatzmodell".getBytes(StandardCharsets.UTF_8);
            austausch.sendResponseHeaders(200, koerper.length);
            austausch.getResponseBody().write(koerper);
            austausch.close();
        });
        server.start();
        try {
            Method ladeUrl = ModellVerteilung.class.getDeclaredMethod("ladeUrl",
                    org.slf4j.Logger.class, String.class, long.class);
            ladeUrl.setAccessible(true);
            org.slf4j.Logger still = org.slf4j.LoggerFactory.getLogger(C4HttpsPflichtTest.class);
            byte[] ergebnis = (byte[]) ladeUrl.invoke(null, still,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/modell.bin", 1024L);
            assertNull(ergebnis, "http://-Download muss ohne Netzwerkzugriff abgelehnt werden");
            org.junit.jupiter.api.Assertions.assertEquals(0, anfragen.get(),
                    "Die Ablehnung muss vor dem HTTP-Aufruf erfolgen, Anfragen: " + anfragen.get());
        } finally {
            server.stop(0);
        }
    }
}
