package de.lightrixx.punish.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import de.lightrixx.punish.net.Protokoll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class ModellVerteilungTest {

    @TempDir
    File datenOrdner;

    private ProxyServer proxy;
    private VelocityKonfiguration konfiguration;
    private List<byte[]> gesendet;
    private ModellVerteilung verteilung;
    private RegisteredServer server;

    @BeforeEach
    void aufsetzen() {
        proxy = Mockito.mock(ProxyServer.class);
        konfiguration = Mockito.mock(VelocityKonfiguration.class);
        when(konfiguration.chatfilterModellSync()).thenReturn(true);
        gesendet = new java.util.concurrent.CopyOnWriteArrayList<>();
        server = Mockito.mock(RegisteredServer.class);
        when(server.getServerInfo()).thenReturn(
                new ServerInfo("lobby", new InetSocketAddress("127.0.0.1", 25566)));
        verteilung = new ModellVerteilung(proxy, LoggerFactory.getLogger("test"),
                konfiguration, datenOrdner, (ziel, paket) -> gesendet.add(paket));
    }

    private void legeDateienAb(byte[] modell, byte[] vocab) throws Exception {
        File ordner = new File(datenOrdner, "chatfilter");
        ordner.mkdirs();
        Files.write(new File(ordner, "modell_int8.onnx").toPath(), modell);
        Files.write(new File(ordner, "vocab.txt").toPath(), vocab);
    }

    private String sha256(byte[] daten) {
        return ModellVerteilung.hashBytes(daten);
    }

    @Test
    void ohneDateien_inaktiv() {
        verteilung.laden();
        assertFalse(verteilung.aktiv());
    }

    @Test
    void chunkGroesse_transportsicher() {
        assertTrue(ModellVerteilung.CHUNK_GROESSE <= 32767,
                "Custom-Payloads über 32 KB werden von Paper verworfen (Kick).");
        assertTrue(ModellVerteilung.CHUNK_GROESSE
                <= Protokoll.MAX_MODELL_CHUNK_BYTES);
    }

    @Test
    void schalterAus_inaktiv() throws Exception {
        legeDateienAb("modell".getBytes(StandardCharsets.UTF_8),
                "vocab".getBytes(StandardCharsets.UTF_8));
        when(konfiguration.chatfilterModellSync()).thenReturn(false);
        verteilung.laden();
        assertFalse(verteilung.aktiv());
    }

    @Test
    void gleicherStand_sendetNichts() throws Exception {
        byte[] modell = "modell-inhalt".getBytes(StandardCharsets.UTF_8);
        byte[] vocab = "vocab-inhalt".getBytes(StandardCharsets.UTF_8);
        legeDateienAb(modell, vocab);
        verteilung.laden();
        assertTrue(verteilung.aktiv());
        verteilung.standEmpfangen(server, sha256(modell), sha256(vocab));
        Thread.sleep(200L);
        assertTrue(gesendet.isEmpty());
    }

    @Test
    void abweichenderStand_uebertraegtBeideDateien() throws Exception {
        byte[] modell = new byte[700000];
        for (int i = 0; i < modell.length; i++) {
            modell[i] = (byte) (i % 251);
        }
        byte[] vocab = "vocab-inhalt".getBytes(StandardCharsets.UTF_8);
        legeDateienAb(modell, vocab);
        verteilung.laden();
        verteilung.standEmpfangen(server, "", "");
        long ende = System.currentTimeMillis() + 30000L;
        while (System.currentTimeMillis() < ende) {
            long fertige = gesendet.stream().filter(paket -> {
                try {
                    return Protokoll.paket(paket).typ() == Protokoll.MODELL_FERTIG;
                } catch (Exception e) {
                    return false;
                }
            }).count();
            if (fertige >= 2) {
                break;
            }
            Thread.sleep(100L);
        }
        List<String> metaNamen = new ArrayList<>();
        java.util.Map<String, java.util.Map<Integer, byte[]>> bloecke = new java.util.HashMap<>();
        java.util.Map<String, String> fertigHash = new java.util.HashMap<>();
        for (byte[] paket : gesendet) {
            int typ = Protokoll.paket(paket).typ();
            if (typ == Protokoll.MODELL_META) {
                var meta = Protokoll.leseModellMeta(Protokoll.paket(paket).nutzdaten());
                metaNamen.add(meta.dateiname());
            } else if (typ == Protokoll.MODELL_BLOCK) {
                var block = Protokoll.leseModellBlock(Protokoll.paket(paket).nutzdaten());
                bloecke.computeIfAbsent(block.dateiname(), k -> new java.util.HashMap<>())
                        .put(block.index(), block.daten());
            } else if (typ == Protokoll.MODELL_FERTIG) {
                var fertig = Protokoll.leseModellFertig(Protokoll.paket(paket).nutzdaten());
                fertigHash.put(fertig.dateiname(), fertig.sha256());
            }
        }
        assertEquals(List.of("modell_int8.onnx", "vocab.txt"), metaNamen);
        for (var eintrag : List.of(
                java.util.Map.entry("modell_int8.onnx", modell),
                java.util.Map.entry("vocab.txt", vocab))) {
            var teile = bloecke.get(eintrag.getKey());
            assertNotNull(teile, eintrag.getKey());
            var gesamt = new java.io.ByteArrayOutputStream();
            for (int i = 0; i < teile.size(); i++) {
                assertNotNull(teile.get(i), "Block " + i);
                gesamt.write(teile.get(i), 0, teile.get(i).length);
            }
            assertArrayEquals(eintrag.getValue(), gesamt.toByteArray());
            assertEquals(sha256(eintrag.getValue()), fertigHash.get(eintrag.getKey()));
        }
        verteilung.schliessen();
    }

    @Test
    void doppelterSync_wirdNichtParallelGestartet() throws Exception {
        byte[] modell = new byte[300000];
        byte[] vocab = "v".getBytes(StandardCharsets.UTF_8);
        legeDateienAb(modell, vocab);
        verteilung.laden();
        verteilung.standEmpfangen(server, "", "");
        verteilung.standEmpfangen(server, "", "");
        long ende = System.currentTimeMillis() + 20000L;
        long fertige = 0;
        while (System.currentTimeMillis() < ende) {
            fertige = zaehleTyp(Protokoll.MODELL_FERTIG);
            if (fertige >= 2) {
                break;
            }
            Thread.sleep(100L);
        }
        Thread.sleep(300L);
        long metas = zaehleTyp(Protokoll.MODELL_META);
        fertige = zaehleTyp(Protokoll.MODELL_FERTIG);
        assertEquals(2, metas, "Genau ein Transfer (Modell + Vokabular), war " + metas + " Metas");
        assertEquals(2, fertige, "Beide Dateien müssen abgeschlossen werden, waren " + fertige + " Fertig");
        verteilung.schliessen();
    }

    private long zaehleTyp(int typ) {
        return gesendet.stream().filter(paket -> {
            try {
                return Protokoll.paket(paket).typ() == typ;
            } catch (Exception e) {
                return false;
            }
        }).count();
    }

    private ModellVerteilung mitDownload(ModellVerteilung.Download download) {
        return new ModellVerteilung(proxy, LoggerFactory.getLogger("test"), konfiguration,
                datenOrdner, (ziel, paket) -> gesendet.add(paket), download);
    }

    private boolean warteAufAktiv(ModellVerteilung verteilung, long millis) throws Exception {
        long ende = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < ende) {
            if (verteilung.aktiv()) {
                return true;
            }
            Thread.sleep(50L);
        }
        return verteilung.aktiv();
    }

    @Test
    void laden_ohneDateien_keinNetzwerkzugriff() {
        when(konfiguration.chatfilterAutoDownload()).thenReturn(true);
        when(konfiguration.chatfilterAktiv()).thenReturn(true);
        AtomicInteger downloads = new AtomicInteger();
        ModellVerteilung test = mitDownload((log, url, max) -> {
            downloads.incrementAndGet();
            return null;
        });
        test.laden();
        assertFalse(test.aktiv());
        assertEquals(0, downloads.get(), "laden() darf keinen Download auslösen");
        test.schliessen();
    }

    @Test
    void starten_beiInaktivemChatfilter_keinDownload() throws Exception {
        when(konfiguration.chatfilterAutoDownload()).thenReturn(true);
        when(konfiguration.chatfilterAktiv()).thenReturn(false);
        AtomicInteger downloads = new AtomicInteger();
        ModellVerteilung test = mitDownload((log, url, max) -> {
            downloads.incrementAndGet();
            return null;
        });
        test.starten();
        Thread.sleep(300L);
        assertFalse(test.aktiv());
        assertEquals(0, downloads.get(), "Bei chatfilter.aktiv=false kein Auto-Download");
        test.schliessen();
    }

    @Test
    void starten_blockiertNicht() throws Exception {
        when(konfiguration.chatfilterAutoDownload()).thenReturn(true);
        when(konfiguration.chatfilterAktiv()).thenReturn(true);
        AtomicInteger downloads = new AtomicInteger();
        ModellVerteilung test = mitDownload((log, url, max) -> {
            downloads.incrementAndGet();
            try {
                Thread.sleep(1500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        });
        long start = System.nanoTime();
        test.starten();
        long dauerMs = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(dauerMs < 1000L, "starten() blockierte " + dauerMs + " ms");
        long ende = System.currentTimeMillis() + 5000L;
        while (downloads.get() == 0 && System.currentTimeMillis() < ende) {
            Thread.sleep(25L);
        }
        assertEquals(1, downloads.get(), "Download muss im Hintergrund laufen");
        test.schliessen();
    }

    @Test
    void starten_mitDateien_sofortAktiv() throws Exception {
        legeDateienAb("modell".getBytes(StandardCharsets.UTF_8),
                "vocab".getBytes(StandardCharsets.UTF_8));
        ModellVerteilung test = mitDownload((log, url, max) -> null);
        test.starten();
        assertTrue(test.aktiv());
        test.schliessen();
    }

    @Test
    void starten_nachDownload_verteiltEinmalAnAlle() throws Exception {
        when(konfiguration.chatfilterAutoDownload()).thenReturn(true);
        when(konfiguration.chatfilterAktiv()).thenReturn(true);
        when(proxy.getAllServers()).thenReturn(List.of(server));
        ModellVerteilung test = mitDownload((log, url, max) -> {
            try {
                legeDateienAb("modell".getBytes(StandardCharsets.UTF_8),
                        "vocab".getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
        test.starten();
        assertTrue(warteAufAktiv(test, 5000L), "Modell nach Download nicht aktiv");
        long ende = System.currentTimeMillis() + 10000L;
        long metas = 0;
        while (System.currentTimeMillis() < ende && metas < 2) {
            metas = gesendet.stream().filter(paket -> {
                try {
                    return Protokoll.paket(paket).typ() == Protokoll.MODELL_META;
                } catch (Exception e) {
                    return false;
                }
            }).count();
            Thread.sleep(50L);
        }
        assertTrue(metas >= 2, "Nach Auto-Download fehlt der Sync an die Bridges (Metas: " + metas + ")");
        Thread.sleep(300L);
        long nachlauf = gesendet.stream().filter(paket -> {
            try {
                return Protokoll.paket(paket).typ() == Protokoll.MODELL_META;
            } catch (Exception e) {
                return false;
            }
        }).count();
        assertEquals(2, nachlauf, "Sync darf nur einmal ausgelöst werden");
        test.schliessen();
    }

    @Test
    void umleitungsZielMussErlaubtSein() {
        assertTrue(ModellVerteilung.istErlaubtesZiel(
                java.net.URI.create("https://huggingface.co/x"), "huggingface.co"));
        assertTrue(ModellVerteilung.istErlaubtesZiel(
                java.net.URI.create("https://cdn-lfs.huggingface.co/x"), "huggingface.co"));
        assertTrue(ModellVerteilung.istErlaubtesZiel(
                java.net.URI.create("https://cas-bridge.xethub.hf.co/x"), "huggingface.co"));
        assertTrue(ModellVerteilung.istErlaubtesZiel(
                java.net.URI.create("https://mirror.example/x"), "mirror.example"));
        assertFalse(ModellVerteilung.istErlaubtesZiel(
                java.net.URI.create("https://evil.example/x"), "huggingface.co"));
        assertFalse(ModellVerteilung.istErlaubtesZiel(
                java.net.URI.create("http://huggingface.co/x"), "huggingface.co"));
    }
}
