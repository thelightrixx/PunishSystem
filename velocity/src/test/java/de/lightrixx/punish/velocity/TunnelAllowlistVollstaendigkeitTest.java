package de.lightrixx.punish.velocity;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class TunnelAllowlistVollstaendigkeitTest {

    private static String pluginYml(String modul) {
        Path pfad = Path.of("..", modul, "src", "main", "resources", "plugin.yml");
        try {
            return Files.readString(pfad, StandardCharsets.UTF_8);
        } catch (Exception e) {
            fail("plugin.yml von " + modul + " nicht lesbar (" + pfad.toAbsolutePath() + "): " + e);
            return "";
        }
    }

    private record BefehlEintrag(String name, List<String> aliase, String permission) {
    }

    private static Map<String, BefehlEintrag> befehle(String text) {
        Map<String, BefehlEintrag> ergebnis = new LinkedHashMap<>();
        String aktuell = null;
        List<String> aliase = new ArrayList<>();
        String permission = null;
        boolean inCommands = false;
        for (String zeile : text.split("\\R")) {
            if (!zeile.startsWith(" ") && !zeile.startsWith("\t")) {
                if (aktuell != null) {
                    ergebnis.put(aktuell, new BefehlEintrag(aktuell, List.copyOf(aliase), permission));
                    aktuell = null;
                }
                inCommands = zeile.trim().equals("commands:");
                continue;
            }
            if (!inCommands) {
                continue;
            }
            if (zeile.startsWith("  ") && !zeile.startsWith("   ") && zeile.trim().endsWith(":")) {
                if (aktuell != null) {
                    ergebnis.put(aktuell, new BefehlEintrag(aktuell, List.copyOf(aliase), permission));
                }
                aktuell = zeile.trim().substring(0, zeile.trim().length() - 1);
                aliase = new ArrayList<>();
                permission = null;
            } else if (aktuell != null && zeile.trim().startsWith("permission:")) {
                permission = zeile.trim().substring("permission:".length()).trim();
            } else if (aktuell != null && zeile.trim().startsWith("aliases:")) {
                String rest = zeile.trim().substring("aliases:".length()).trim();
                if (rest.startsWith("[") && rest.endsWith("]")) {
                    for (String alias : rest.substring(1, rest.length() - 1).split(",")) {
                        String a = alias.trim();
                        if (!a.isEmpty()) {
                            aliase.add(a);
                        }
                    }
                }
            }
        }
        if (aktuell != null) {
            ergebnis.put(aktuell, new BefehlEintrag(aktuell, List.copyOf(aliase), permission));
        }
        return ergebnis;
    }

    private static final java.util.Set<String> LOKAL_NUR_BRIDGE = java.util.Set.of(
            "punish-gui", "punish-diagnostics");

    private static void pruefeModul(String modul) {
        Map<String, BefehlEintrag> befehle = befehle(pluginYml(modul));
        assertTrue(befehle.size() >= 30, modul + ": zu wenige Befehle geparst (" + befehle.size() + ")");
        List<String> fehler = new ArrayList<>();
        for (BefehlEintrag eintrag : befehle.values()) {
            String erlaubt = BrueckenKanal.bridgeErlaubnisFuer(eintrag.name());
            if (LOKAL_NUR_BRIDGE.contains(eintrag.name())) {
                if (erlaubt != null) {
                    fehler.add("/" + eintrag.name()
                            + " ist bridge-lokal und darf nicht im Tunnel landen");
                }
                continue;
            }
            if (erlaubt == null) {
                fehler.add("/" + eintrag.name() + " fehlt in der Tunnel-Allowlist");
                continue;
            }
            if (eintrag.permission() != null && !erlaubt.equalsIgnoreCase(eintrag.permission())) {
                fehler.add("/" + eintrag.name() + ": Allowlist " + erlaubt
                        + " != plugin.yml " + eintrag.permission());
            }
        }
        assertTrue(fehler.isEmpty(),
                modul + ": Allowlist-Abweichungen:\n" + String.join("\n", fehler));
    }

    @Test
    void bridgePluginYmlVollstaendigInAllowlist() {
        pruefeModul("bridge");
    }

    @Test
    void bridge121PluginYmlVollstaendigInAllowlist() {
        pruefeModul("bridge-1.21");
    }

    @Test
    void beidePluginYmlGleicheBefehlsmenge() {
        Map<String, BefehlEintrag> bridge = befehle(pluginYml("bridge"));
        Map<String, BefehlEintrag> bridge121 = befehle(pluginYml("bridge-1.21"));
        assertEquals(bridge.keySet(), bridge121.keySet(), "Befehlsmengen bridge vs bridge-1.21");
        List<String> abweichungen = new ArrayList<>();
        for (String name : bridge.keySet()) {
            String p1 = bridge.get(name).permission();
            String p2 = bridge121.get(name).permission();
            if (p1 != null && !p1.equalsIgnoreCase(p2)) {
                abweichungen.add(name + ": " + p1 + " != " + p2);
            }
            if (!bridge.get(name).aliase().equals(bridge121.get(name).aliase())) {
                abweichungen.add(name + ": Aliase " + bridge.get(name).aliase()
                        + " != " + bridge121.get(name).aliase());
            }
        }
        assertTrue(abweichungen.isEmpty(), "Permission-/Alias-Drift:\n" + String.join("\n", abweichungen));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> allowlist() {
        try {
            Field feld = BrueckenKanal.class.getDeclaredField("ERLAUBTE_BRIDGE_BEFEHLE");
            feld.setAccessible(true);
            Object wert = feld.get(null);
            assertNotNull(wert, "Allowlist ist null");
            return (Map<String, String>) wert;
        } catch (Exception e) {
            fail("Allowlist nicht lesbar: " + e);
            return Map.of();
        }
    }

    @Test
    void aliaseWidersprechenDerAllowlistNicht() {
        Map<String, BefehlEintrag> befehle = befehle(pluginYml("bridge"));
        Map<String, String> allowlist = allowlist();
        List<String> fehler = new ArrayList<>();
        for (BefehlEintrag eintrag : befehle.values()) {
            if (LOKAL_NUR_BRIDGE.contains(eintrag.name())) {
                continue;
            }
            if (!allowlist.containsKey(eintrag.name())) {
                fehler.add("Befehl /" + eintrag.name() + " fehlt in der Allowlist");
                continue;
            }
            for (String alias : eintrag.aliase()) {
                String aliasRecht = allowlist.get(alias);
                // Die Bridge leitet Aliase auf den kanonischen Befehlsnamen weiter; ein eigener
                // Allowlist-Eintrag für den Alias ist zulässig, muss aber dasselbe Recht tragen.
                if (aliasRecht != null && eintrag.permission() != null
                        && !aliasRecht.equalsIgnoreCase(eintrag.permission())) {
                    fehler.add("Alias /" + alias + " bildet auf " + aliasRecht
                            + " ab, Befehl /" + eintrag.name() + " auf " + eintrag.permission());
                }
            }
        }
        assertTrue(fehler.isEmpty(),
                "Alias-/Allowlist-Abweichungen:\n" + String.join("\n", fehler));
    }

    @Test
    void allowlistOhneToteEintraege() {
        Map<String, BefehlEintrag> befehle = befehle(pluginYml("bridge"));
        java.util.Set<String> alleNamen = new java.util.LinkedHashSet<>(befehle.keySet());
        for (BefehlEintrag eintrag : befehle.values()) {
            alleNamen.addAll(eintrag.aliase());
        }
        List<String> tot = new ArrayList<>();
        for (String schluessel : allowlist().keySet()) {
            if (!alleNamen.contains(schluessel)) {
                tot.add(schluessel);
            }
        }
        assertTrue(tot.isEmpty(),
                "Allowlist-Einträge ohne Befehl/Alias in plugin.yml: " + tot);
        assertTrue(BrueckenKanal.bridgeErlaubnisFuer("ban") != null);
        assertTrue(BrueckenKanal.bridgeErlaubnisFuer("psversion") != null);
        assertTrue(BrueckenKanal.bridgeErlaubnisFuer("unban") != null);
    }

    @Test
    void punishReloadNichtInAllowlist() {
        assertTrue(BrueckenKanal.bridgeErlaubnisFuer("punish-reload") == null,
                "punish-reload hat keinen Bridge-Befehl und darf nicht tunneln (B-008)");
        assertTrue(BrueckenKanal.bridgeErlaubnisFuer("punish-reload confirm") == null);
    }
}
