package de.lightrixx.punish.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class RangBuendelTest {

    @AfterEach
    void zuruecksetzen() {
        RangBuendel.laden(Map.of());
    }

    private static Predicate<String> mitRechten(String... knoten) {
        Set<String> menge = Set.of(knoten);
        return menge::contains;
    }

    @Test
    void direktesRecht_gilt() {
        RangBuendel.laden(Map.of());
        assertTrue(RangBuendel.gewaehrt(mitRechten("punish.mute"), "punish.mute"));
        assertFalse(RangBuendel.gewaehrt(mitRechten("punish.mute"), "punish.ban"));
    }

    @Test
    void buendel_gilt() {
        RangBuendel.laden(Map.of("moderator", List.of("punish.mute", "punish.warn")));
        assertTrue(RangBuendel.gewaehrt(mitRechten("punish.moderator"), "punish.mute"));
        assertTrue(RangBuendel.gewaehrt(mitRechten("punish.moderator"), "punish.warn"));
        assertFalse(RangBuendel.gewaehrt(mitRechten("punish.moderator"), "punish.ban"));
        assertFalse(RangBuendel.gewaehrt(mitRechten("punish.admin"), "punish.mute"));
    }

    @Test
    void wildcard_imBuendel_gilt() {
        RangBuendel.laden(Map.of("präsident", List.of("punish.*")));
        assertTrue(RangBuendel.gewaehrt(mitRechten("punish.präsident"), "punish.ban"));
        assertTrue(RangBuendel.gewaehrt(mitRechten("punish.präsident"), "punish.chatfilter.bypass"));
    }

    @Test
    void direktWildcard_gilt() {
        RangBuendel.laden(Map.of());
        assertTrue(RangBuendel.gewaehrt(mitRechten("punish.*"), "punish.ban"));
    }

    @Test
    void fremdeKnoten_werdenNichtExpandiert() {
        RangBuendel.laden(Map.of("moderator", List.of("minecraft.command.gamemode")));
        assertFalse(RangBuendel.gewaehrt(mitRechten("punish.moderator"), "minecraft.command.gamemode"));
        assertTrue(RangBuendel.gewaehrt(mitRechten("minecraft.command.gamemode"), "minecraft.command.gamemode"));
    }

    @Test
    void ungueltig_istFalse() {
        RangBuendel.laden(Map.of("moderator", List.of("punish.mute")));
        assertFalse(RangBuendel.gewaehrt(null, "punish.mute"));
        assertFalse(RangBuendel.gewaehrt(mitRechten("punish.moderator"), null));
        assertFalse(RangBuendel.gewaehrt(mitRechten("punish.moderator"), "  "));
        Predicate<String> fehlerhaft = knoten -> {
            throw new IllegalStateException("kaputt");
        };
        assertFalse(RangBuendel.gewaehrt(fehlerhaft, "punish.mute"));
    }

    @Test
    void ladenAusAbschnitt() {
        RangBuendel.ladenAusAbschnitt(Map.of(
                "Moderator", List.of("punish.mute", "", "  "),
                "leer", List.of()));
        assertEquals(Set.of("moderator"), RangBuendel.stand().keySet());
        assertTrue(RangBuendel.gewaehrt(mitRechten("punish.moderator"), "punish.MUTE"));
    }
}
