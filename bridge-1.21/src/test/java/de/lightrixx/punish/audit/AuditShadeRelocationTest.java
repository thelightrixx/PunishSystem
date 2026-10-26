package de.lightrixx.punish.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditShadeRelocationTest {

    private static final String RELOCIERTER_TREIBER = "de.lightrixx.punish.lib.mysql.cj.jdbc.Driver";

    private static Path projektWurzel() {
        String basedir = System.getProperty("basedir");
        Path start = (basedir == null || basedir.isBlank())
                ? Path.of("").toAbsolutePath()
                : Path.of(basedir).toAbsolutePath();
        for (Path kandidat = start; kandidat != null; kandidat = kandidat.getParent()) {
            if (Files.isRegularFile(kandidat.resolve("pom.xml"))
                    && Files.isDirectory(kandidat.resolve("common"))) {
                return kandidat;
            }
        }
        throw new IllegalStateException("Projektwurzel nicht gefunden (ab " + start + ").");
    }

    @Test
    @DisplayName("C-P1.1 gefixt: <relocation> für com.mysql/com.zaxxer in velocity/bridge/bridge-1.21")
    void cP11RelocationVorhanden() throws Exception {
        Path wurzel = projektWurzel();
        for (String modul : new String[]{"velocity", "bridge", "bridge-1.21"}) {
            String pom = modul + "/pom.xml";
            Path datei = wurzel.resolve(pom);
            assertTrue(Files.exists(datei), "POM muss lesbar sein: " + pom);
            String inhalt = Files.readString(datei);
            assertTrue(inhalt.contains("<relocation"), "Relocation fehlt in: " + pom);
            assertTrue(inhalt.contains("<pattern>com.mysql</pattern>")
                            && inhalt.contains("<shadedPattern>de.lightrixx.punish.lib.mysql</shadedPattern>"),
                    "com.mysql -> de.lightrixx.punish.lib.mysql fehlt in: " + pom);
            assertTrue(inhalt.contains("<pattern>com.zaxxer</pattern>")
                            && inhalt.contains("<shadedPattern>de.lightrixx.punish.lib.zaxxer</shadedPattern>"),
                    "com.zaxxer -> de.lightrixx.punish.lib.zaxxer fehlt in: " + pom);
        }

        Path quelle = wurzel.resolve(
                "common/src/main/java/de/lightrixx/punish/storage/mysql/MysqlVerbindung.java");
        assertTrue(Files.readString(quelle).contains(RELOCIERTER_TREIBER),
                "MysqlVerbindung muss den relocierten Treibernamen laden (Ladepfad lebendig)");
    }
}
