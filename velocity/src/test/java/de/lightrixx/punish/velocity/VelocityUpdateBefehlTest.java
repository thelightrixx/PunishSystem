package de.lightrixx.punish.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.storage.PlayerCache;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VelocityUpdateBefehlTest {

    private static VelocityUpdateBefehl baueBefehl() {
        return new VelocityUpdateBefehl(Mockito.mock(MessageService.class),
                Mockito.mock(VelocityKonfiguration.class),
                Mockito.mock(ProtectionService.class),
                Mockito.mock(PlayerCache.class),
                Mockito.mock(AuftragsPlaner.class),
                Mockito.mock(SpielerVerzeichnis.class),
                () -> "4.0-RC+Test");
    }

    private static UpdateVerteilung baueVerteilung() {
        return new UpdateVerteilung(Mockito.mock(ProxyServer.class),
                LoggerFactory.getLogger("VelocityUpdateBefehlTest"),
                (server, paket) -> { });
    }

    @Test
    void setterLehntNullAb() {
        VelocityUpdateBefehl befehl = baueBefehl();
        NullPointerException fehler = assertThrows(NullPointerException.class,
                () -> befehl.setUpdateVerteilung(null));
        assertTrue(String.valueOf(fehler.getMessage()).contains("Update-Verteilung"),
                "Meldung muss die Update-Verteilung benennen: " + fehler.getMessage());
    }

    @Test
    void setterAkzeptiertVerteilung() {
        VelocityUpdateBefehl befehl = baueBefehl();
        assertDoesNotThrow(() -> befehl.setUpdateVerteilung(baueVerteilung()));
    }

    private static Path projektWurzel() {
        Path start = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath();
        for (Path kandidat = start; kandidat != null; kandidat = kandidat.getParent()) {
            Path pom = kandidat.resolve("pom.xml");
            if (!Files.isRegularFile(pom)) {
                continue;
            }
            String inhalt;
            try {
                inhalt = Files.readString(pom);
            } catch (Exception e) {
                continue;
            }
            if (inhalt.contains("<artifactId>punish-parent</artifactId>")
                    && inhalt.contains("<packaging>pom</packaging>")) {
                return kandidat;
            }
        }
        throw new IllegalStateException("Projektwurzel nicht gefunden, Start: " + start);
    }

    @Test
    void verteilungWirdVorRegisterErzeugt() throws Exception {
        String quelle = Files.readString(projektWurzel().resolve(
                Path.of("velocity", "src", "main", "java", "de", "lightrixx", "punish",
                        "velocity", "PunishVelocity.java")));
        int zuweisung = quelle.indexOf("this.updateVerteilung = updateVerteilung;");
        int register = quelle.indexOf("VelocityBefehle.register(");
        assertTrue(zuweisung > 0, "Zuweisung an das Update-Verteilungs-Feld fehlt.");
        assertTrue(register > 0, "Aufruf VelocityBefehle.register(...) fehlt.");
        assertTrue(zuweisung < register, "UpdateVerteilung muss vor dem Befehls-Register "
                + "erzeugt werden, sonst erhält der Update-Befehl null (toter Update-Push).");
    }
    @Test
    void fehlgeschlageneBereitstellungWirdAnAufruferGemeldet() throws Exception {
        var methode = VelocityUpdateBefehl.class.getDeclaredMethod("ersetzeEigeneJar", byte[].class, String.class);
        methode.setAccessible(true);
        var fehler = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> methode.invoke(baueBefehl(), (Object) null, "4.0"));
        org.junit.jupiter.api.Assertions.assertInstanceOf(IllegalStateException.class, fehler.getCause());
        assertTrue(fehler.getCause().getMessage().contains("nicht bereitgestellt"));
    }
    private static final String REPOSITORY = "thelightrixx/PunishSystem";
    private static final String HASH = "a".repeat(64);

    private static java.util.List<String> dateinamen(String version) {
        String[] teile = version.split("\\+", 2);
        return java.util.List.of("bridge-1.21", "velocity", "bridge").stream()
                .map(v -> "PunishSystem-v" + teile[0] + "-(" + v + ")"
                        + (teile.length == 2 ? "+" + teile[1].replace('-', '_') : "") + ".jar").toList();
    }

    private static String release(String version, boolean draft, boolean prerelease) {
        var assets = new java.util.ArrayList<String>();
        var namen = new java.util.ArrayList<>(dateinamen(version));
        namen.add("SHA256SUMS.txt");
        for (String name : namen) {
            assets.add("{\"name\":\"" + name + "\",\"browser_download_url\":\"https://github.com/"
                    + REPOSITORY + "/releases/download/v" + version + "/" + name
                    + "\",\"digest\":null}");
        }
        return "{\"body\":\"Beschreibung mit {Klammern} und \\\"name\\\": \\\"99.0\\\"\","
                + "\"tag_name\":\"v" + version + "\",\"draft\":" + draft + ",\"prerelease\":" + prerelease
                + ",\"assets\":[" + String.join(",", assets) + "]}";
    }

    private static String summen(String version) {
        return dateinamen(version).stream().map(n -> HASH + "  " + n + "\n")
                .collect(java.util.stream.Collectors.joining());
    }

    private static de.lightrixx.punish.update.HangarUpdateService.HttpAbruf githubAntwort(
            String releases, String summen) {
        return new de.lightrixx.punish.update.HangarUpdateService.HttpAbruf() {
            @Override
            public String leseText(String url) {
                if (url.startsWith("https://api.github.com/repos/" + REPOSITORY + "/releases")) {
                    return releases;
                }
                if (url.endsWith("/SHA256SUMS.txt")) {
                    return summen;
                }
                throw new AssertionError("Unerwarteter Abruf: " + url);
            }

            @Override
            public byte[] ladeBytes(String url, long maxBytes) {
                throw new AssertionError("Prüfung darf keine JAR herunterladen.");
            }
        };
    }

    private static VelocityKonfiguration githubConfig(String kanal) {
        var config = Mockito.mock(VelocityKonfiguration.class);
        Mockito.when(config.updateGithubRepository()).thenReturn(REPOSITORY);
        Mockito.when(config.updateChannel()).thenReturn(kanal);
        return config;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void fehlerBeimDrittenDownloadVerhindertJedeTeilBereitstellung(boolean netzfehler) throws Exception {
        String version = "4.1.0";
        byte[] gueltig = "gepruefte-testdaten".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash = UpdateVerteilung.hashStill(gueltig);
        var nachrichten = Mockito.mock(MessageService.class);
        var planer = Mockito.mock(AuftragsPlaner.class);
        org.mockito.stubbing.Answer<Object> sofort = inv -> {
            inv.<Runnable>getArgument(0).run();
            return null;
        };
        Mockito.doAnswer(sofort).when(planer).aufgabe(Mockito.any(Runnable.class));
        Mockito.doAnswer(sofort).when(planer).aufgabeAsync(Mockito.any(Runnable.class));
        var befehl = new VelocityUpdateBefehl(nachrichten, githubConfig("Release"),
                Mockito.mock(ProtectionService.class), Mockito.mock(PlayerCache.class), planer,
                Mockito.mock(SpielerVerzeichnis.class), () -> "4.0.0");
        var verteilung = Mockito.mock(UpdateVerteilung.class);
        befehl.setUpdateVerteilung(verteilung);
        var anzahl = new java.util.concurrent.atomic.AtomicInteger();
        var antwort = githubAntwort(release(version, false, false), summen(version).replace(HASH, hash));
        befehl.setHttpAbruf(new de.lightrixx.punish.update.HangarUpdateService.HttpAbruf() {
            @Override
            public String leseText(String url) throws Exception {
                return antwort.leseText(url);
            }

            @Override
            public byte[] ladeBytes(String url, long maxBytes) throws Exception {
                if (anzahl.incrementAndGet() == 3) {
                    if (netzfehler) {
                        throw new java.io.IOException("Download unterbrochen");
                    }
                    return new byte[]{0};
                }
                return gueltig;
            }
        });
        var sender = Mockito.mock(de.lightrixx.punish.core.BefehlsSender.class);
        befehl.ausfuehren(sender, new String[]{"apply", "confirm"});
        assertEquals(3, anzahl.get());
        Mockito.verifyNoInteractions(verteilung);
        Mockito.verify(nachrichten).senden(Mockito.eq(sender),
                Mockito.eq(de.lightrixx.punish.core.MessageKeys.UPDATE_FEHLER), Mockito.anyMap());
        Mockito.verify(nachrichten, Mockito.never()).senden(Mockito.eq(sender),
                Mockito.eq(de.lightrixx.punish.core.MessageKeys.UPDATE_BEREITGESTELLT), Mockito.anyMap());
    }

    @Test
    void githubOrdnetAlleDreiVariantenUnabhaengigVonReihenfolgeUndBeschreibungZu() throws Exception {
        String version = "4.1.0-beta+031126-1400";
        var info = VelocityUpdateBefehl.pruefeGithub(githubConfig("Beta"),
                githubAntwort("[" + release(version, false, true) + "]", summen(version)));

        assertEquals(version, info.version());
        assertEquals("Beta", info.channel());
        assertEquals(java.util.List.of("VELOCITY", "BRIDGE", "BRIDGE-1.21"),
                info.downloads().stream().map(de.lightrixx.punish.update.HangarUpdateService.Artefakt::plattform).toList());
        assertTrue(info.downloads().stream().allMatch(a -> HASH.equals(a.sha256())));
    }

    @Test
    void githubFindetReleaseCandidateMitBuildnummerVorDerPreview() throws Exception {
        String version = "4.0.0-RC+131126-2200";
        String json = release(version, false, true);
        String manifest = summen(version);
        for (String variante : java.util.List.of("velocity", "bridge", "bridge-1.21")) {
            String lokal = "PunishSystem-v4.0.0-RC-(" + variante + ")+131126_2200.jar";
            String github = "PunishSystem-v" + version + "-" + variante + ".jar";
            json = json.replace(lokal, github);
            manifest = manifest.replace(lokal, github);
        }
        String urlVersion = version.replace("+", "%2B");
        json = json.replace("/releases/download/v" + version, "/releases/download/v" + urlVersion)
                .replace("/PunishSystem-v" + version, "/PunishSystem-v" + urlVersion);
        String preview = "4.0.0-preview+111126-1741";
        var info = VelocityUpdateBefehl.pruefeGithub(githubConfig("Beta"),
                githubAntwort("[" + release(preview, false, true) + "," + json + "]", manifest));

        assertEquals(version, info.version());
        assertTrue(de.lightrixx.punish.update.UpdateVergleich.neuerVerfuegbar(preview, info.version()));
        assertEquals(java.util.List.of("VELOCITY", "BRIDGE", "BRIDGE-1.21"),
                info.downloads().stream().map(de.lightrixx.punish.update.HangarUpdateService.Artefakt::plattform).toList());
    }

    @Test
    void githubStableIgnoriertDraftsUndVorabversionen() throws Exception {
        String antwort = "[" + release("99.0.0", true, false) + ","
                + release("5.0.0-beta", false, true) + "," + release("4.1.0", false, false) + "]";
        var info = VelocityUpdateBefehl.pruefeGithub(githubConfig("Release"),
                githubAntwort(antwort, summen("4.1.0")));
        assertEquals("4.1.0", info.version());
    }

    @Test
    void githubLehntFehlendePruefsummenAb() {
        assertThrows(IllegalStateException.class, () -> VelocityUpdateBefehl.pruefeGithub(githubConfig("Release"),
                githubAntwort(release("4.1.0", false, false), "")));
    }

    @Test
    void githubLehntFremdeDownloadQuelleAb() {
        String antwort = release("4.1.0", false, false).replace(
                "https://github.com/" + REPOSITORY, "https://github.com/fremd/anderes");
        assertThrows(IllegalArgumentException.class, () -> VelocityUpdateBefehl.pruefeGithub(githubConfig("Release"),
                githubAntwort(antwort, summen("4.1.0"))));
    }

    @Test
    void githubLehntWiderspruchZwischenApiDigestUndManifestAb() {
        String antwort = release("4.1.0", false, false).replace("\"digest\":null",
                "\"digest\":\"sha256:" + "b".repeat(64) + "\"");
        assertThrows(IllegalStateException.class, () -> VelocityUpdateBefehl.pruefeGithub(githubConfig("Release"),
                githubAntwort(antwort, summen("4.1.0"))));
    }

    @Test
    void githubLehntUnvollstaendigesReleaseAb() {
        String antwort = release("4.1.0", false, false).replace("-(bridge-1.21).jar", "-(unbekannt).jar");
        assertThrows(IllegalStateException.class, () -> VelocityUpdateBefehl.pruefeGithub(githubConfig("Release"),
                githubAntwort(antwort, summen("4.1.0"))));
    }

    @Test
    void githubOrdnetAuchBestehendeReleaseDateinamenEindeutigZu() throws Exception {
        String json = release("4.0-beta", false, true);
        String manifest = summen("4.0-beta");
        for (String variante : java.util.List.of("velocity", "bridge", "bridge-1.21")) {
            json = json.replace("-(" + variante + ").jar", "-" + variante + ".jar");
            manifest = manifest.replace("-(" + variante + ").jar", "-" + variante + ".jar");
        }
        var info = VelocityUpdateBefehl.pruefeGithub(githubConfig("Beta"),
                githubAntwort("[" + json + "]", manifest));
        assertEquals(java.util.List.of("VELOCITY", "BRIDGE", "BRIDGE-1.21"),
                info.downloads().stream().map(de.lightrixx.punish.update.HangarUpdateService.Artefakt::plattform).toList());
    }

    @Test
    void updateVerteiltNurDieGemeldeteVariante() throws Exception {
        var server = Mockito.mock(com.velocitypowered.api.proxy.server.RegisteredServer.class);
        var info = Mockito.mock(com.velocitypowered.api.proxy.server.ServerInfo.class);
        Mockito.when(server.getServerInfo()).thenReturn(info);
        Mockito.when(info.getName()).thenReturn("lobby");
        var pakete = new java.util.concurrent.CopyOnWriteArrayList<byte[]>();
        var verteilung = new UpdateVerteilung(Mockito.mock(ProxyServer.class),
                LoggerFactory.getLogger("UpdateVerteilungTest"), (ziel, paket) -> pakete.add(paket));
        try {
            verteilung.legeAb("4.1.0", new byte[]{1, 2, 3}, new byte[]{4, 5});
            verteilung.pushAn(server, "lobby", "");
            verteilung.pushAn(server, "lobby", "bridge-1.21");
            var feld = UpdateVerteilung.class.getDeclaredField("pool");
            feld.setAccessible(true);
            var pool = (java.util.concurrent.ExecutorService) feld.get(verteilung);
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(3, pakete.size(), "Genau eine Variante mit META, BLOCK und FERTIG");
            var meta = de.lightrixx.punish.net.Protokoll.leseUpdateMeta(
                    de.lightrixx.punish.net.Protokoll.paket(pakete.getFirst()).nutzdaten());
            assertEquals("bridge-1.21", meta.variante());
            assertEquals(2, meta.groesse());
            assertEquals(UpdateVerteilung.hashStill(new byte[]{4, 5}), meta.sha256());
            verteilung.quittungEmpfangen(server, new de.lightrixx.punish.net.Protokoll.UpdateQuittung(
                    "bridge-1.21", "4.0.0", true));
            assertTrue(verteilung.quittungen().isEmpty(), "Alte Quittung darf den neuen Push nicht bestätigen");
            verteilung.quittungEmpfangen(server, new de.lightrixx.punish.net.Protokoll.UpdateQuittung(
                    "bridge-1.21", "4.1.0", true));
            assertEquals(java.util.Map.of("lobby", true), verteilung.quittungen());
        } finally {
            verteilung.schliessen();
        }
    }
}
