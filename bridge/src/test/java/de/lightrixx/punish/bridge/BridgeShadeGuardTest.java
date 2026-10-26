package de.lightrixx.punish.bridge;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BridgeShadeGuardTest {

    private static Path modulVerzeichnis() {
        String basedir = System.getProperty("basedir");
        Path start = (basedir == null || basedir.isBlank())
                ? Path.of("").toAbsolutePath()
                : Path.of(basedir).toAbsolutePath();
        for (Path kandidat = start; kandidat != null; kandidat = kandidat.getParent()) {
            Path pom = kandidat.resolve("pom.xml");
            if (!Files.isRegularFile(pom)) {
                continue;
            }
            try {
                if (Files.readString(pom).contains("<artifactId>punish-bridge")) {
                    return kandidat;
                }
            } catch (Exception ignored) {
                // weiter suchen
            }
        }
        throw new IllegalStateException("Bridge-Modulverzeichnis nicht gefunden (ab " + start + ").");
    }

    @Test
    void shadeExcludesHaltenStraflogikAusBridge() throws Exception {
        Path pom = modulVerzeichnis().resolve("pom.xml");
        assertTrue(Files.exists(pom), "Modul-POM muss lesbar sein: " + pom);
        String inhalt = Files.readString(pom);
        assertTrue(inhalt.contains("de/lightrixx/punish/command/**"),
                "Shade muss command/** exkludieren.");
        assertTrue(inhalt.contains("de/lightrixx/punish/service/**"),
                "Shade muss service/** exkludieren.");
        assertTrue(inhalt.contains("de/lightrixx/punish/hook/**"),
                "Shade muss hook/** exkludieren.");
        assertTrue(inhalt.contains("slf4j-api"),
                "Bridge muss slf4j-api deklarieren.");
    }

    @Test
    void allowlistKlassenBleibenVerfuegbar() {
        assertDoesNotThrow(() -> Class.forName("de.lightrixx.punish.net.Protokoll", true,
                BridgeShadeGuardTest.class.getClassLoader()));
        assertDoesNotThrow(() -> Class.forName("de.lightrixx.punish.model.ServerScope", true,
                BridgeShadeGuardTest.class.getClassLoader()));
        assertDoesNotThrow(() -> Class.forName("de.lightrixx.punish.model.CachedPlayer", true,
                BridgeShadeGuardTest.class.getClassLoader()));
        assertDoesNotThrow(() -> Class.forName("de.lightrixx.punish.model.ParseResult", true,
                BridgeShadeGuardTest.class.getClassLoader()));
        assertDoesNotThrow(() -> Class.forName("de.lightrixx.punish.model.VerstossStufe", true,
                BridgeShadeGuardTest.class.getClassLoader()));
        assertDoesNotThrow(() -> Class.forName("de.lightrixx.punish.model.IpBanRecord", true,
                BridgeShadeGuardTest.class.getClassLoader()));
        assertDoesNotThrow(() -> Class.forName("de.lightrixx.punish.storage.PlayerCache", true,
                BridgeShadeGuardTest.class.getClassLoader()));
        assertDoesNotThrow(() -> Class.forName("de.lightrixx.punish.storage.YamlSpeicher", true,
                BridgeShadeGuardTest.class.getClassLoader()));
        assertDoesNotThrow(() -> Class.forName("de.lightrixx.punish.storage.IoExecutor", true,
                BridgeShadeGuardTest.class.getClassLoader()));
        assertDoesNotThrow(() -> Class.forName("de.lightrixx.punish.storage.StoreLock", true,
                BridgeShadeGuardTest.class.getClassLoader()));
        assertDoesNotThrow(() -> Class.forName("de.lightrixx.punish.storage.Karten", true,
                BridgeShadeGuardTest.class.getClassLoader()));
        assertDoesNotThrow(() -> Class.forName("de.lightrixx.punish.storage.DateiSicherung", true,
                BridgeShadeGuardTest.class.getClassLoader()));
        assertDoesNotThrow(() -> Class.forName("de.lightrixx.punish.util.TimeParser", true,
                BridgeShadeGuardTest.class.getClassLoader()));
        assertDoesNotThrow(() -> Class.forName("de.lightrixx.punish.util.ZeitFormat", true,
                BridgeShadeGuardTest.class.getClassLoader()));
        assertDoesNotThrow(() -> Class.forName("de.lightrixx.punish.util.MiniMessageUtil", true,
                BridgeShadeGuardTest.class.getClassLoader()));
        assertDoesNotThrow(() -> Class.forName("de.lightrixx.punish.util.TabHinweis", true,
                BridgeShadeGuardTest.class.getClassLoader()));
        assertDoesNotThrow(() -> Class.forName("de.lightrixx.punish.core.Konfiguration", true,
                BridgeShadeGuardTest.class.getClassLoader()));
        assertDoesNotThrow(() -> Class.forName("de.lightrixx.punish.core.SpielerVerzeichnis", true,
                BridgeShadeGuardTest.class.getClassLoader()));
        assertDoesNotThrow(() -> Class.forName("de.lightrixx.punish.core.OfflineVerzeichnis", true,
                BridgeShadeGuardTest.class.getClassLoader()));
        assertDoesNotThrow(() -> Class.forName("de.lightrixx.punish.core.OnlineSpieler", true,
                BridgeShadeGuardTest.class.getClassLoader()));
        assertDoesNotThrow(() -> Class.forName("de.lightrixx.punish.core.AuftragsPlaner", true,
                BridgeShadeGuardTest.class.getClassLoader()));
        assertDoesNotThrow(() -> Class.forName("de.lightrixx.punish.core.GeplanteAufgabe", true,
                BridgeShadeGuardTest.class.getClassLoader()));
    }

    /**
     * Prüft die kompilierten Klassen (target/classes) inklusive innerer/anonymer Klassen und
     * Methodenrümpfe über den Konstantenpool. Das fertige Shade-Jar wird nicht geprüft, weil es
     * im Test-Lauf noch nicht existiert.
     */
    @Test
    void keineBridgeKlasseReferenziertExkludiertes() throws Exception {
        Path klassen = modulVerzeichnis().resolve("target/classes");
        assertTrue(Files.isDirectory(klassen), "Klassenverzeichnis muss existieren (erst bauen): " + klassen);
        java.util.List<Path> klassenDateien = new java.util.ArrayList<>();
        try (var stream = Files.walk(klassen)) {
            stream.filter(p -> p.toString().endsWith(".class")).forEach(klassenDateien::add);
        }
        java.util.Set<String> eigeneKlassen = new java.util.HashSet<>();
        for (Path datei : klassenDateien) {
            eigeneKlassen.add(internerName(klassen, datei));
        }

        java.util.List<String> verstoesse = new java.util.ArrayList<>();
        for (Path datei : klassenDateien) {
            verstoesse.addAll(pruefeKonstantenpool(datei, eigeneKlassen));
            String name = internerName(klassen, datei).replace('/', '.');
            Class<?> klasse;
            try {
                klasse = Class.forName(name, false, BridgeShadeGuardTest.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                if (name.contains("$")) {
                    // Synthetische Klassen sind nicht immer per Binärnamen ladbar; der
                    // Konstantenpool-Scan oben deckt sie ab.
                    continue;
                }
                verstoesse.add(name + " laedt nicht: " + e);
                continue;
            } catch (NoClassDefFoundError | ExceptionInInitializerError e) {
                verstoesse.add(name + " laedt nicht: " + e);
                continue;
            }
            pruefeTyp(klasse.getSuperclass(), name, verstoesse);
            for (Class<?> iface : klasse.getInterfaces()) {
                pruefeTyp(iface, name, verstoesse);
            }
            for (var feld : klasse.getDeclaredFields()) {
                try {
                    pruefeTyp(feld.getType(), name, verstoesse);
                } catch (NoClassDefFoundError e) {
                    verstoesse.add(name + " Feld verweist auf Exkludiertes: " + e.getMessage());
                }
            }
            for (var methode : klasse.getDeclaredMethods()) {
                try {
                    pruefeTyp(methode.getReturnType(), name, verstoesse);
                    for (Class<?> param : methode.getParameterTypes()) {
                        pruefeTyp(param, name, verstoesse);
                    }
                } catch (NoClassDefFoundError e) {
                    verstoesse.add(name + " Methode verweist auf Exkludiertes: " + e.getMessage());
                }
            }
            for (var konstruktor : klasse.getDeclaredConstructors()) {
                try {
                    for (Class<?> param : konstruktor.getParameterTypes()) {
                        pruefeTyp(param, name, verstoesse);
                    }
                } catch (NoClassDefFoundError e) {
                    verstoesse.add(name + " Konstruktor verweist auf Exkludiertes: " + e.getMessage());
                }
            }
        }
        assertTrue(verstoesse.isEmpty(),
                "Bridge referenziert exkludierte Common-Klassen:\n" + String.join("\n", verstoesse));
    }

    private static String internerName(Path klassen, Path datei) {
        String relativ = klassen.relativize(datei).toString()
                .replace(java.io.File.separatorChar, '/');
        return relativ.substring(0, relativ.length() - ".class".length());
    }

    private static java.util.List<String> pruefeKonstantenpool(Path datei,
            java.util.Set<String> eigeneKlassen) throws java.io.IOException {
        String inhalt = new String(Files.readAllBytes(datei),
                java.nio.charset.StandardCharsets.ISO_8859_1);
        java.util.List<String> verstoesse = new java.util.ArrayList<>();
        for (String paket : java.util.List.of("de/lightrixx/punish/command/",
                "de/lightrixx/punish/service/", "de/lightrixx/punish/hook/")) {
            int start = inhalt.indexOf(paket);
            while (start >= 0) {
                int ende = start;
                while (ende < inhalt.length() && istNamensZeichen(inhalt.charAt(ende))) {
                    ende++;
                }
                String name = inhalt.substring(start, ende);
                if (name.length() > paket.length() && !eigeneKlassen.contains(name)) {
                    verstoesse.add(datei.getFileName()
                            + " verweist auf exkludiertes " + name
                            + " (auch in Methodenrumpf/Lambda)");
                }
                start = inhalt.indexOf(paket, ende + 1);
            }
        }
        return verstoesse;
    }

    private static boolean istNamensZeichen(char zeichen) {
        return (zeichen >= 'a' && zeichen <= 'z')
                || (zeichen >= 'A' && zeichen <= 'Z')
                || (zeichen >= '0' && zeichen <= '9')
                || zeichen == '_' || zeichen == '$' || zeichen == '/';
    }

    private static void pruefeTyp(Class<?> typ, String herkunft, java.util.List<String> verstoesse) {
        if (typ == null || typ.isPrimitive()) {
            return;
        }
        while (typ.isArray()) {
            typ = typ.getComponentType();
        }
        String name = typ.getName();
        if (!name.startsWith("de.lightrixx.punish.")) {
            return;
        }
        if (name.startsWith("de.lightrixx.punish.command.")
                || name.startsWith("de.lightrixx.punish.service.")
                || name.startsWith("de.lightrixx.punish.hook.")) {
            verstoesse.add(herkunft + " -> " + name);
            return;
        }
        if (name.startsWith("de.lightrixx.punish.storage.")
                && (name.contains("Repository") || name.contains("LogWriter"))) {
            verstoesse.add(herkunft + " -> " + name);
            return;
        }
        if (name.startsWith("de.lightrixx.punish.storage.mysql.")
                && !name.equals("de.lightrixx.punish.storage.mysql.SpeicherTyp")
                && !name.equals("de.lightrixx.punish.storage.mysql.MysqlKonfiguration")) {
            verstoesse.add(herkunft + " -> " + name);
            return;
        }
        if (name.startsWith("de.lightrixx.punish.model.")
                && !name.equals("de.lightrixx.punish.model.CachedPlayer")
                && !name.equals("de.lightrixx.punish.model.ServerScope")
                && !name.equals("de.lightrixx.punish.model.ParseResult")
                && !name.equals("de.lightrixx.punish.model.VerstossStufe")
                && !name.equals("de.lightrixx.punish.model.IpBanRecord")) {
            verstoesse.add(herkunft + " -> " + name);
            return;
        }
        if (name.equals("de.lightrixx.punish.core.MessageKeys")
                || name.equals("de.lightrixx.punish.core.Permissions")
                || name.equals("de.lightrixx.punish.core.BefehlsSender")
                || name.equals("de.lightrixx.punish.util.BuildNumber")
                || name.equals("de.lightrixx.punish.util.PluginVersion")) {
            verstoesse.add(herkunft + " -> " + name);
        }
    }
}
