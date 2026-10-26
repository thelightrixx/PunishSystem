package de.lightrixx.punish.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class C3PluginYmlKonsistenzTest {

    private static String pluginYml() throws Exception {
        try (InputStream in = C3PluginYmlKonsistenzTest.class.getResourceAsStream("/plugin.yml")) {
            if (in == null) {
                throw new IllegalStateException("plugin.yml nicht auf dem Test-Classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> schluesselDerSektion(String text, String sektion) {
        List<String> schluessel = new ArrayList<>();
        boolean aktiv = false;
        for (String zeile : text.split("\\R")) {
            if (!zeile.startsWith(" ")) {
                aktiv = zeile.startsWith(sektion + ":");
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

    @Test
    @DisplayName("F3: keine doppelten Permission-Keys in plugin.yml")
    void keineDoppeltenPermissionSchluessel() throws Exception {
        List<String> schluessel = schluesselDerSektion(pluginYml(), "permissions");
        Set<String> gesehen = new HashSet<>();
        List<String> doppelt = new ArrayList<>();
        for (String schluesselName : schluessel) {
            if (!gesehen.add(schluesselName)) {
                doppelt.add(schluesselName);
            }
        }
        assertEquals(List.of(), doppelt, "doppelte Permission-Keys in plugin.yml");
    }

    @Test
    @DisplayName("A-006: kein punish.*-Node mit default: op (Rechte nur via LuckPerms/Bündel)")
    void keineOpDefaults() throws Exception {
        List<String> auffällig = new ArrayList<>();
        for (String zeile : pluginYml().split("\\R")) {
            String getrimmt = zeile.trim();
            if (getrimmt.startsWith("punish.") && getrimmt.contains("default: op")) {
                auffällig.add(getrimmt);
            }
        }
        assertEquals(List.of(), auffällig, "Permissions mit default: op in plugin.yml");
    }

    @Test
    @DisplayName("A-006: einziges default:true ist punish.report (Spieler-Befehl, zusätzlich in allen Bündeln)")
    void nurReportHatDefaultTrue() throws Exception {
        List<String> trueDefaults = new ArrayList<>();
        for (String zeile : pluginYml().split("\\R")) {
            String getrimmt = zeile.trim();
            if (getrimmt.startsWith("punish.") && getrimmt.contains("default: true")) {
                trueDefaults.add(getrimmt);
            }
        }
        assertEquals(1, trueDefaults.size(), "genau ein default:true erwartet: " + trueDefaults);
        assertTrue(trueDefaults.get(0).startsWith("punish.report:"),
                "default:true nur für punish.report: " + trueDefaults);
    }

    @Test
    @DisplayName("F3: Command punish-gui trägt permission: punish.gui")
    void punishGuiCommandHatPermission() throws Exception {
        boolean inBlock = false;
        boolean permissionGefunden = false;
        for (String zeile : pluginYml().split("\\R")) {
            if (!zeile.startsWith(" ")) {
                inBlock = false;
                continue;
            }
            if (zeile.startsWith("  punish-gui:")) {
                inBlock = true;
                continue;
            }
            if (inBlock && zeile.startsWith("  ") && !zeile.startsWith("   ")) {
                inBlock = false;
                continue;
            }
            if (inBlock && zeile.trim().startsWith("permission:")) {
                String wert = zeile.trim().substring("permission:".length()).trim();
                if ("punish.gui".equalsIgnoreCase(wert)) {
                    permissionGefunden = true;
                }
            }
        }
        assertTrue(permissionGefunden,
                "punish-gui muss in plugin.yml mit permission: punish.gui deklariert sein");
    }

    @Test
    @DisplayName("A-007: im Code geprüfte Nodes punish.*, punish.all, punish.user und punish.ignore sind deklariert")
    void codeGepruefteNodesSindDeklariert() throws Exception {
        List<String> schluessel = schluesselDerSektion(pluginYml(), "permissions");
        assertTrue(schluessel.contains("punish.*"), "punish.* fehlt in plugin.yml");
        assertTrue(schluessel.contains("punish.all"), "punish.all fehlt in plugin.yml");
        assertTrue(schluessel.contains("punish.user"), "punish.user fehlt in plugin.yml");
        assertTrue(schluessel.contains("punish.ignore"), "punish.ignore fehlt in plugin.yml");
    }
}
