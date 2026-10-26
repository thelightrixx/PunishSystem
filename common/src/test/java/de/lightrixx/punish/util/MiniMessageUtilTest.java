package de.lightrixx.punish.util;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MiniMessageUtilTest {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    @Test
    void ersetztSichertSpielerEingabenGegenTagInjection() {
        String ersetzt = MiniMessageUtil.ersetzen(
                "<red>Der Spieler <white>%spieler% <red>wurde nicht gefunden.",
                Map.of("spieler", "<green>hacker"));
        assertTrue(ersetzt.contains("\\<green>hacker"),
                "Spieler-Eingabe mit gültigem Tag-Namen muss escapet sein: " + ersetzt);
        assertDoesNotThrow(() -> MINI.deserialize(ersetzt));
    }

    @Test
    void ersetztRohLaesstVerschachteltesMiniMessageFuerStatusZu() {
        String vorlage = "<gray>[%status%<gray>] <white>%name%<gray>: %detail%";
        Map<String, String> ph = Map.of(
                "status", "<green>OK", "name", "Version", "detail", "4.0-test");

        String escapet = MiniMessageUtil.ersetzen(vorlage, ph);
        assertTrue(escapet.contains("\\<green>OK"),
                "Ohne Roh-Schlüssel darf <green> nicht roh eingesetzt werden: " + escapet);

        String roh = MiniMessageUtil.ersetzen(vorlage, ph, Set.of("status"));
        assertEquals("<gray>[<green>OK<gray>] <white>Version<gray>: 4.0-test", roh);
        assertDoesNotThrow(() -> MINI.deserialize(roh));
    }

    @Test
    void ersetztRohLaesstBanUndIpStatusFuerAltsZu() {
        String eintrag = MiniMessageUtil.ersetzen(
                "<gray>• <white>%spieler% <gray>| Ban: %banstatus%",
                Map.of("spieler", "Steve", "banstatus", "<green>kein Ban"),
                Set.of("banstatus"));
        assertEquals("<gray>• <white>Steve <gray>| Ban: <green>kein Ban", eintrag);
        assertDoesNotThrow(() -> MINI.deserialize(eintrag));

        String kopf = MiniMessageUtil.ersetzen(
                "<yellow>Alts (%anzahl%)%ipstatus%",
                Map.of("anzahl", "2", "ipstatus", "<red>[IP gebannt: global]"),
                Set.of("ipstatus"));
        assertEquals("<yellow>Alts (2)<red>[IP gebannt: global]", kopf);
        assertDoesNotThrow(() -> MINI.deserialize(kopf));
    }

    @Test
    void legacyParagrafCodesWerdenEntschaerftStattZuWerfen() {
        MiniMessage sicher = MiniMessageUtil.miniMessage();
        var komponente = assertDoesNotThrow(() -> sicher.deserialize("\u00A7cHallo \u00A7lWelt"));
        assertEquals("&cHallo &lWelt", sicher.serialize(komponente));

        String ersetzt = MiniMessageUtil.ersetzen("<gray>Grund: <white>%grund%",
                Map.of("grund", "\u00A74Regelversto\u00DF"));
        assertDoesNotThrow(() -> sicher.deserialize(ersetzt));
    }

    @Test
    void paragrafOhneLegacyZeichenBleibtUnveraendert() {
        MiniMessage sicher = MiniMessageUtil.miniMessage();
        assertEquals("\u00A7 5 StGB", sicher.serialize(sicher.deserialize("\u00A7 5 StGB")));
    }
}
