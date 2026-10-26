package de.lightrixx.punish.audit;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class AuditMessageKeysAbdeckungTest {

    private static final Pattern PLATZHALTER = Pattern.compile("%([A-Za-z0-9-]+)%");

    private static final Map<String, String> TEXT_AUSNAHMEN = Map.of(
            "chatfilter-blockiert",
            "wird per String-Literal gelesen (BridgeEnforcementListener), ohne MessageKeys-Konstante");

    private static final Map<String, String> BEISPIEL_WERTE = Map.ofEntries(
            Map.entry("spieler", "BeispielSpieler"),
            Map.entry("ziel", "BeispielZiel"),
            Map.entry("ausfuehrer", "ModA"),
            Map.entry("bearbeiter", "ModA"),
            Map.entry("reporter", "Melder123"),
            Map.entry("grund", "Beleidigung"),
            Map.entry("warn-grund", "Spam"),
            Map.entry("dauer", "30 Minuten"),
            Map.entry("verbleibend", "25 Minuten"),
            Map.entry("ablauf", "01.01.2027 12:00"),
            Map.entry("anzahl", "3"),
            Map.entry("ausstehend", "2500"),
            Map.entry("max", "6"),
            Map.entry("min", "60"),
            Map.entry("punkte", "5"),
            Map.entry("stufe", "low"),
            Map.entry("nummer", "7"),
            Map.entry("id", "42"),
            Map.entry("ip", "1.2.3.4"),
            Map.entry("server", "lobby"),
            Map.entry("scope", "global"),
            Map.entry("bekannt", "lobby, survival"),
            Map.entry("status", "angenommen"),
            Map.entry("typ", "ban"),
            Map.entry("filter", "all"),
            Map.entry("seite", "1"),
            Map.entry("seiten", "5"),
            Map.entry("datum", "01.01.2027"),
            Map.entry("text", "Beispieltext"),
            Map.entry("wort", "Beispielwort"),
            Map.entry("quelle", "Wortliste"),
            Map.entry("kanal", "Chat"),
            Map.entry("score", "87%"),
            Map.entry("version", "4.0-beta+EA25b"),
            Map.entry("aktuell", "4.0-beta+EA25b"),
            Map.entry("channel", "Release"),
            Map.entry("detail", "Details"),
            Map.entry("plattform", "paper"),
            Map.entry("warnungen", "2"),
            Map.entry("level", "low"),
            Map.entry("zuletzt", "gestern"),
            Map.entry("banstatus", "aktiv"),
            Map.entry("rest", "10 Minuten"),
            Map.entry("ipstatus", "frei"),
            Map.entry("banns", "1"),
            Map.entry("mutes", "0"),
            Map.entry("timeouts", "0"),
            Map.entry("bridge", "lobby"),
            Map.entry("proxy", "proxy"),
            Map.entry("befehl", "ban"),
            Map.entry("nutzung", "/ban <Spieler> <Dauer> [Grund]"),
            Map.entry("kandidaten", "12"),
            Map.entry("name", "BeispielName"));

    private static Map<String, Object> lade(String pfad) {
        try (InputStream in = AuditMessageKeysAbdeckungTest.class.getResourceAsStream(pfad)) {
            assertNotNull(in, "Ressource fehlt im Test-Classpath: " + pfad);
            Object geladen = new Yaml().load(in);
            assertTrue(geladen instanceof Map, "YAML-Wurzel ist keine Map: " + pfad);
            Map<String, Object> ergebnis = new LinkedHashMap<>();
            for (var eintrag : ((Map<?, ?>) geladen).entrySet()) {
                ergebnis.put(String.valueOf(eintrag.getKey()), eintrag.getValue());
            }
            return ergebnis;
        } catch (Exception e) {
            return fail("YAML konnte nicht geladen werden: " + pfad + " (" + e + ")");
        }
    }

    private static Map<String, String> sammleTexte() {
        Map<String, String> texte = new LinkedHashMap<>();
        Map<String, Object> messages = lade("/config/messages.yml");
        Object abschnitt = messages.get("messages");
        assertTrue(abschnitt instanceof Map, "messages-Abschnitt fehlt");
        for (var eintrag : ((Map<?, ?>) abschnitt).entrySet()) {
            texte.put("messages." + eintrag.getKey(), String.valueOf(eintrag.getValue()));
        }
        Map<String, Object> broadcasts = lade("/config/broadcasts.yml");
        Object bcWurzel = broadcasts.getOrDefault("broadcasts", broadcasts);
        if (bcWurzel instanceof Map<?, ?> bcMap) {
            for (var eintrag : bcMap.entrySet()) {
                if (eintrag.getValue() instanceof Map<?, ?> details) {
                    Object nachricht = details.get("nachricht");
                    if (nachricht != null) {
                        texte.put("broadcasts." + eintrag.getKey(), String.valueOf(nachricht));
                    }
                    for (String extra : List.of("kopf", "prefix")) {
                        Object wert = details.get(extra);
                        if (wert != null) {
                            texte.put("broadcasts." + eintrag.getKey() + "." + extra, String.valueOf(wert));
                        }
                    }
                }
            }
        }
        Map<String, Object> screens = lade("/config/screens.yml");
        Object scWurzel = screens.getOrDefault("screens", screens);
        if (scWurzel instanceof Map<?, ?> scMap) {
            for (var eintrag : scMap.entrySet()) {
                if (eintrag.getValue() instanceof String) {
                    texte.put("screens." + eintrag.getKey(), (String) eintrag.getValue());
                }
            }
        }
        assertFalse(texte.isEmpty(), "Keine Texte gefunden");
        return texte;
    }

    private static Map<String, String> konstanten() {
        Map<String, String> ergebnis = new LinkedHashMap<>();
        for (Field feld : de.lightrixx.punish.core.MessageKeys.class.getDeclaredFields()) {
            int mod = feld.getModifiers();
            if (Modifier.isPublic(mod) && Modifier.isStatic(mod) && Modifier.isFinal(mod)
                    && feld.getType() == String.class) {
                try {
                    ergebnis.put(feld.getName(), (String) feld.get(null));
                } catch (Exception e) {
                    fail("Konstante unlesbar: " + feld.getName());
                }
            }
        }
        assertFalse(ergebnis.isEmpty(), "Keine MessageKeys-Konstanten gefunden");
        return ergebnis;
    }

    private static Set<String> vorhandeneSchluessel() {
        Set<String> schluessel = new LinkedHashSet<>();
        Map<String, Object> messages = lade("/config/messages.yml");
        Object abschnitt = messages.get("messages");
        if (abschnitt instanceof Map<?, ?> map) {
            for (Object key : map.keySet()) {
                schluessel.add(String.valueOf(key));
            }
        }
        Map<String, Object> broadcasts = lade("/config/broadcasts.yml");
        Object bcWurzel = broadcasts.getOrDefault("broadcasts", broadcasts);
        Set<String> bcNamen = new LinkedHashSet<>();
        if (bcWurzel instanceof Map<?, ?> bcMap) {
            for (Object key : bcMap.keySet()) {
                bcNamen.add(String.valueOf(key));
            }
        } else {
            for (Object key : broadcasts.keySet()) {
                bcNamen.add(String.valueOf(key));
            }
        }
        schluessel.addAll(bcNamen);
        schluessel.add("kopf");
        schluessel.add("prefix");
        Map<String, Object> screens = lade("/config/screens.yml");
        Object scWurzel = screens.getOrDefault("screens", screens);
        if (scWurzel instanceof Map<?, ?> scMap) {
            for (var eintrag : scMap.entrySet()) {
                if (eintrag.getValue() instanceof String) {
                    schluessel.add(String.valueOf(eintrag.getKey()));
                }
            }
        } else {
            for (var eintrag : screens.entrySet()) {
                if (eintrag.getValue() instanceof String) {
                    schluessel.add(String.valueOf(eintrag.getKey()));
                }
            }
        }
        Map<String, Object> general = lade("/config/general.yml");
        Object defaults = general.get("defaults");
        if (defaults instanceof Map<?, ?> map) {
            for (Object key : map.keySet()) {
                schluessel.add(String.valueOf(key));
            }
        }
        return schluessel;
    }

    @Test
    void jedeNachrichtParsiertAlsMiniMessage() {
        MiniMessage mini = MiniMessage.miniMessage();
        List<String> fehler = new ArrayList<>();
        for (var eintrag : sammleTexte().entrySet()) {
            try {
                var komponente = mini.deserialize(eintrag.getValue());
                if (komponente == null) {
                    fehler.add(eintrag.getKey() + " (null-Ergebnis)");
                }
            } catch (Exception e) {
                fehler.add(eintrag.getKey() + " (" + e.getMessage() + ")");
            }
        }
        assertTrue(fehler.isEmpty(), "MiniMessage-Fehler:\n" + String.join("\n", fehler));
    }

    @Test
    void keinMessageKeyOhneText() {
        Set<String> schluessel = vorhandeneSchluessel();
        List<String> fehler = new ArrayList<>();
        for (var konstante : konstanten().entrySet()) {
            if (!schluessel.contains(konstante.getValue())) {
                fehler.add(konstante.getKey() + " = '" + konstante.getValue() + "'");
            }
        }
        assertTrue(fehler.isEmpty(), "Schlüssel ohne Text:\n" + String.join("\n", fehler));
    }

    @Test
    void keinToterText() {
        Set<String> werte = new LinkedHashSet<>(konstanten().values());
        List<String> fehler = new ArrayList<>();
        Map<String, Object> messages = lade("/config/messages.yml");
        Object abschnitt = messages.get("messages");
        if (abschnitt instanceof Map<?, ?> map) {
            for (Object key : map.keySet()) {
                String name = String.valueOf(key);
                if (!werte.contains(name) && !TEXT_AUSNAHMEN.containsKey(name)) {
                    fehler.add("messages." + name);
                }
            }
        }
        Map<String, Object> broadcasts = lade("/config/broadcasts.yml");
        Object bcWurzel2 = broadcasts.getOrDefault("broadcasts", broadcasts);
        if (bcWurzel2 instanceof Map<?, ?> bcMap2) {
            for (Object key : bcMap2.keySet()) {
                String name = String.valueOf(key);
                if (!werte.contains(name) && !"verpasst".equals(name)) {
                    fehler.add("broadcasts." + name);
                }
            }
        }
        Map<String, Object> screens = lade("/config/screens.yml");
        Object scWurzel2 = screens.getOrDefault("screens", screens);
        if (scWurzel2 instanceof Map<?, ?> scMap2) {
            for (var eintrag : scMap2.entrySet()) {
                if (eintrag.getValue() instanceof String) {
                    if (!werte.contains(String.valueOf(eintrag.getKey()))) {
                        fehler.add("screens." + eintrag.getKey());
                    }
                }
            }
        }
        assertTrue(fehler.isEmpty(), "Tote Texte:\n" + String.join("\n", fehler));
    }

    @Test
    void platzhalterWerdenAufgeloest() {
        MiniMessage mini = MiniMessage.miniMessage();
        List<String> fehler = new ArrayList<>();
        for (var eintrag : sammleTexte().entrySet()) {
            String roh = eintrag.getValue();
            Matcher matcher = PLATZHALTER.matcher(roh);
            StringBuffer ersetzt = new StringBuffer();
            while (matcher.find()) {
                String name = matcher.group(1);
                String wert = BEISPIEL_WERTE.get(name);
                if (wert == null) {
                    fehler.add(eintrag.getKey() + " (unbekannter Platzhalter: %" + name + "%)");
                    wert = "Beispielwert";
                }
                matcher.appendReplacement(ersetzt, Matcher.quoteReplacement(wert));
            }
            matcher.appendTail(ersetzt);
            String aufgeloest = ersetzt.toString();
            Matcher rest = PLATZHALTER.matcher(aufgeloest);
            if (rest.find()) {
                fehler.add(eintrag.getKey() + " (Rest-Token: " + rest.group() + ")");
                continue;
            }
            try {
                mini.deserialize(aufgeloest);
            } catch (Exception e) {
                fehler.add(eintrag.getKey() + " (nach Ersetzung: " + e.getMessage() + ")");
            }
        }
        assertTrue(fehler.isEmpty(), "Platzhalter-Fehler:\n" + String.join("\n", fehler));
    }
}
