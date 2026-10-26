package de.lightrixx.punish.velocity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VorschlagListenTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> general() {
        try (InputStream in = VorschlagListenTest.class.getResourceAsStream("/config/general.yml")) {
            assertNotNull(in, "Default-general.yml muss im Jar liegen");
            LoaderOptions optionen = new LoaderOptions();
            optionen.setAllowDuplicateKeys(false);
            Object geladen = new Yaml(new SafeConstructor(optionen)).load(in);
            assertInstanceOf(Map.class, geladen, "general.yml muss ein Mapping sein");
            return (Map<String, Object>) geladen;
        } catch (Exception e) {
            throw new AssertionError("general.yml konnte nicht geparst werden", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> gruende(Map<String, Object> general, String befehl) {
        Map<String, Object> vorschlaege = (Map<String, Object>) general.get("vorschlaege");
        assertNotNull(vorschlaege, "vorschlaege-Sektion fehlt");
        Map<String, Object> sektion = (Map<String, Object>) vorschlaege.get(befehl);
        assertNotNull(sektion, "vorschlaege." + befehl + " fehlt");
        Object gruende = sektion.get("gruende");
        assertInstanceOf(List.class, gruende, "vorschlaege." + befehl + ".gruende fehlt");
        return (List<String>) gruende;
    }

    @SuppressWarnings("unchecked")
    private static List<String> dauern(Map<String, Object> general, String befehl) {
        Map<String, Object> vorschlaege = (Map<String, Object>) general.get("vorschlaege");
        Map<String, Object> sektion = (Map<String, Object>) vorschlaege.get(befehl);
        assertNotNull(sektion, "vorschlaege." + befehl + " fehlt");
        Object dauern = sektion.get("dauern");
        assertInstanceOf(List.class, dauern, "vorschlaege." + befehl + ".dauern fehlt");
        return (List<String>) dauern;
    }

    @Test
    @DisplayName("Ban- und Mute-Gründe unterscheiden sich")
    void banUndMuteHabenEigeneGruende() {
        Map<String, Object> general = general();
        List<String> ban = gruende(general, "ban");
        List<String> mute = gruende(general, "mute");
        assertFalse(ban.isEmpty(), "/ban braucht eigene Gründe");
        assertFalse(mute.isEmpty(), "/mute braucht eigene Gründe");
        assertTrue(ban.contains("Cheating"), "Ban-Gründe müssen Cheating enthalten");
        assertTrue(mute.contains("Spamming"), "Mute-Gründe müssen Spamming enthalten");
        assertFalse(ban.contains("Spamming"), "Ban-Gründe dürfen kein Spamming enthalten");
        assertFalse(mute.contains("Cheating"), "Mute-Gründe dürfen kein Cheating enthalten");
    }

    @Test
    @DisplayName("Timeout, Kick, Warn und Verstoss haben eigene Gründe")
    void restlicheBefehleHabenEigeneGruende() {
        Map<String, Object> general = general();
        assertFalse(gruende(general, "timeout").isEmpty());
        assertFalse(gruende(general, "kick").isEmpty());
        assertFalse(gruende(general, "warn").isEmpty());
        assertFalse(gruende(general, "verstoss").isEmpty());
        assertTrue(gruende(general, "timeout").contains("Cool-down"));
    }

    @Test
    @DisplayName("Ban, Mute und Timeout haben eigene Dauern")
    void befehleHabenEigeneDauern() {
        Map<String, Object> general = general();
        assertFalse(dauern(general, "ban").isEmpty());
        assertFalse(dauern(general, "mute").isEmpty());
        assertFalse(dauern(general, "timeout").isEmpty());
        assertTrue(dauern(general, "mute").contains("10m"), "Mute braucht kurze Dauern");
        assertTrue(dauern(general, "ban").contains("30d"), "Ban braucht lange Dauern");
    }

    @Test
    @DisplayName("Vorlagen verknüpfen Gründe mit Dauern")
    @SuppressWarnings("unchecked")
    void vorlagenVerknuepfenGrundUndDauer() {
        Map<String, Object> general = general();
        Object vorlagen = ((Map<String, Object>) general.get("vorschlaege")).get("vorlagen");
        assertInstanceOf(List.class, vorlagen, "vorlagen müssen aktiv sein");
        List<?> liste = (List<?>) vorlagen;
        assertFalse(liste.isEmpty(), "vorlagen dürfen nicht leer sein");
        for (Object eintrag : liste) {
            assertInstanceOf(Map.class, eintrag);
            Map<?, ?> vorlage = (Map<?, ?>) eintrag;
            assertNotNull(vorlage.get("befehl"), "Vorlage braucht befehl");
            assertNotNull(vorlage.get("grund"), "Vorlage braucht grund");
            assertNotNull(vorlage.get("dauer"), "Vorlage braucht dauer");
        }
    }
}
