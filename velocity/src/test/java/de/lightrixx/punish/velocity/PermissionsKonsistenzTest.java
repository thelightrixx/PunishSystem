package de.lightrixx.punish.velocity;

import de.lightrixx.punish.core.Permissions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class PermissionsKonsistenzTest {

    private static Path projektWurzel() {
        Path start = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath();
        for (Path kandidat = start; kandidat != null; kandidat = kandidat.getParent()) {
            Path pom = kandidat.resolve("pom.xml");
            if (!Files.isRegularFile(pom)) {
                continue;
            }
            String inhalt;
            try {
                inhalt = Files.readString(pom, StandardCharsets.UTF_8);
            } catch (Exception e) {
                continue;
            }
            if (inhalt.contains("<artifactId>punish-parent</artifactId>")
                    && inhalt.contains("<packaging>pom</packaging>")) {
                return kandidat;
            }
        }
        fail("Projektwurzel (pom.xml mit punish-parent) nicht gefunden, Start: " + start);
        return start;
    }

    private static String pluginYml(String modul) {
        Path pfad = projektWurzel().resolve(Path.of(modul, "src", "main", "resources", "plugin.yml"));
        try {
            return Files.readString(pfad, StandardCharsets.UTF_8);
        } catch (Exception e) {
            fail("plugin.yml von " + modul + " nicht lesbar (" + pfad + "): " + e);
            return "";
        }
    }

    private static Set<String> permissionSchluessel(String text) {
        Set<String> schluessel = new HashSet<>();
        boolean aktiv = false;
        for (String zeile : text.split("\\R")) {
            if (!zeile.startsWith(" ")) {
                aktiv = zeile.startsWith("permissions:");
                continue;
            }
            if (aktiv && zeile.startsWith("  ") && !zeile.startsWith("   ")) {
                String roh = zeile.trim();
                int doppelpunkt = roh.indexOf(':');
                if (doppelpunkt > 0) {
                    schluessel.add(roh.substring(0, doppelpunkt));
                }
            }
        }
        return schluessel;
    }

    private static void pruefeDreiQuellen(String knoten) {
        boolean inPermissions = false;
        for (Permissions perm : Permissions.values()) {
            if (perm.node().equalsIgnoreCase(knoten)) {
                inPermissions = true;
                break;
            }
        }
        assertTrue(inPermissions, knoten + " fehlt in Permissions.java");
        for (String modul : List.of("bridge", "bridge-1.21")) {
            assertTrue(permissionSchluessel(pluginYml(modul)).contains(knoten),
                    knoten + " fehlt in " + modul + "/plugin.yml");
        }
    }

    @Test
    void updateInAllenQuellen() {
        pruefeDreiQuellen("punish.update");
    }

    @Test
    void chatfilterStammInAllenQuellen() {
        pruefeDreiQuellen("punish.chatfilter");
    }

    @Test
    void chatfilterSyncInAllenQuellen() {
        pruefeDreiQuellen("punish.chatfilter.sync");
    }

    @Test
    void chatfilterBypassInAllenQuellen() {
        pruefeDreiQuellen("punish.chatfilter.bypass");
    }
}
