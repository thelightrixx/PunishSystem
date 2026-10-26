package de.lightrixx.punish.update;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gegen die am 2026-10-24 von hangar.papermc.io abgerufene reale API-Antwort
 * (GET /api/v1/projects/ViaVersion/ViaVersion/versions/5.12.1, ungekuerzt in
 * audit/logs/010-hangar-fixture.json). /latestrelease liefert text/plain.
 */
class HangarEchteApiTest {

    private static final String LATESTRELEASE_KLARTEXT = "5.12.1\n";

    private static final String ECHTES_VERSIONS_JSON = "{\"createdAt\":\"2026-10-08T13:42:39.251141Z\",\"id\":31499,\"projectId\":31,\"name\":\"5.12.1\",\"visibility\":\"public\",\"description\":\"See [GitHub](https://github.com/ViaVersion/ViaVersion) for release notes.\",\"stats\":{\"totalDownloads\":8332,\"platformDownloads\":{\"PAPER\":7619,\"VELOCITY\":713}},\"author\":\"kennytv\",\"reviewState\":\"reviewed\",\"channel\":{\"createdAt\":\"2022-12-31T20:04:25.916259Z\",\"name\":\"Release\",\"description\":null,\"color\":\"#14b8a6\",\"flags\":[\"SENDS_NOTIFICATIONS\",\"PINNED\",\"FROZEN\"]},\"pinnedStatus\":\"CHANNEL\",\"downloads\":{\"PAPER\":{\"fileInfo\":{\"name\":\"ViaVersion-5.12.1.jar\",\"sizeBytes\":6503216,\"sha256Hash\":\"bab94b846e1d2bf516c070562d0271b32382afd41870bf0d3a7993b73a36f791\"},\"externalUrl\":null,\"downloadUrl\":\"https://hangarcdn.papermc.io/plugins/ViaVersion/ViaVersion/versions/5.12.1/PAPER/ViaVersion-5.12.1.jar\"},\"VELOCITY\":{\"fileInfo\":{\"name\":\"ViaVersion-5.12.1.jar\",\"sizeBytes\":6503216,\"sha256Hash\":\"bab94b846e1d2bf516c070562d0271b32382afd41870bf0d3a7993b73a36f791\"},\"externalUrl\":null,\"downloadUrl\":\"https://hangarcdn.papermc.io/plugins/ViaVersion/ViaVersion/versions/5.12.1/PAPER/ViaVersion-5.12.1.jar\"}},\"pluginDependencies\":{\"PAPER\":[{\"name\":\"ViaBackwards\",\"projectId\":12,\"required\":false,\"externalUrl\":null,\"platform\":\"PAPER\"}],\"memberNames\":null}";

    private static final String PAARUNG_JSON = "{\"name\":\"4.0+EA01\",\"channel\":{\"name\":\"Release\"},"
            + "\"downloads\":{\"PAPER\":{\"fileInfo\":{\"name\":\"a.jar\",\"sizeBytes\":1,\"sha256Hash\":\""
            + "1111111111111111111111111111111111111111111111111111111111111111\"},"
            + "\"externalUrl\":null,\"downloadUrl\":\"https://hangarcdn.papermc.io/a.jar\"},"
            + "\"VELOCITY\":{\"fileInfo\":{\"name\":\"b.jar\",\"sizeBytes\":1,\"sha256Hash\":\""
            + "2222222222222222222222222222222222222222222222222222222222222222\"},"
            + "\"externalUrl\":null,\"downloadUrl\":\"https://hangarcdn.papermc.io/b.jar\"}}}";

    private static HangarUpdateService.HttpAbruf antworten(String latest, String versions) {
        return new HangarUpdateService.HttpAbruf() {
            @Override
            public String leseText(String url) {
                if (url.contains("/latestrelease")) {
                    return latest;
                }
                return versions;
            }

            @Override
            public byte[] ladeBytes(String url, long maxBytes) {
                return new byte[]{1};
            }
        };
    }

    @Test
    void latestreleaseAlsKlartextWirdVerarbeitet() throws Exception {
        HangarUpdateService dienst = new HangarUpdateService(
                "ViaVersion", "ViaVersion", "Release",
                antworten(LATESTRELEASE_KLARTEXT, ECHTES_VERSIONS_JSON));
        HangarUpdateService.ReleaseInfo info = dienst.pruefe();
        assertEquals("5.12.1", info.version());
        assertEquals("Release", info.channel());
        assertEquals(2, info.downloads().size());
    }

    @Test
    void echtesDownloadFormatLiefertHashPlattformUndUrl() {
        List<HangarUpdateService.Artefakt> artefakte = HangarUpdateService.parseDownloads(
                ECHTES_VERSIONS_JSON, "ViaVersion", "ViaVersion", "5.12.1");
        assertEquals(2, artefakte.size());
        HangarUpdateService.Artefakt paper = artefakte.get(0);
        HangarUpdateService.Artefakt velocity = artefakte.get(1);
        assertEquals("PAPER", paper.plattform());
        assertEquals("VELOCITY", velocity.plattform());
        assertEquals("bab94b846e1d2bf516c070562d0271b32382afd41870bf0d3a7993b73a36f791", paper.sha256());
        assertEquals("bab94b846e1d2bf516c070562d0271b32382afd41870bf0d3a7993b73a36f791", velocity.sha256());
        assertTrue(paper.url().contains("/PAPER/"), paper.url());
    }

    @Test
    void hashUndPlattformBleibenDemRichtigenArtefaktZugeordnet() {
        List<HangarUpdateService.Artefakt> artefakte = HangarUpdateService.parseDownloads(
                PAARUNG_JSON, "autor", "slug", "4.0+EA01");
        assertEquals(2, artefakte.size());
        assertEquals("PAPER", artefakte.get(0).plattform());
        assertEquals("1111111111111111111111111111111111111111111111111111111111111111",
                artefakte.get(0).sha256());
        assertEquals("https://hangarcdn.papermc.io/a.jar", artefakte.get(0).url());
        assertEquals("VELOCITY", artefakte.get(1).plattform());
        assertEquals("2222222222222222222222222222222222222222222222222222222222222222",
                artefakte.get(1).sha256());
        assertEquals("https://hangarcdn.papermc.io/b.jar", artefakte.get(1).url());
    }

    @Test
    void downloadHostCdnIstErlaubt() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                HangarUpdateService.pruefeHttpsNachRedirect(
                        java.net.URI.create("https://hangarcdn.papermc.io/plugins/x/y.jar")));
    }

    @Test
    void versionMitUnerlaubtenZeichenWirdAbgelehnt() {
        assertThrows(IllegalArgumentException.class, () -> HangarUpdateService.parseLatestRelease(
                "{\"name\":\"4.0<click:run_command>evil\",\"channel\":{\"name\":\"Release\"},\"downloads\":[]}",
                "autor", "slug", "Release"));
        assertThrows(IllegalArgumentException.class, () -> new HangarUpdateService(
                "autor", "slug", "Release", antworten("nicht die Version", ECHTES_VERSIONS_JSON)).pruefe());
    }
}
