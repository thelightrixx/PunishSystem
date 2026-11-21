package de.lightrixx.punish.command;

import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.storage.PlayerCache;
import de.lightrixx.punish.util.MiniMessageUtil;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PunishHelpCommandBetaTest {

    private Konfiguration config() {
        Konfiguration config = mock(Konfiguration.class);
        when(config.praefix()).thenReturn("[Punish]");
        when(config.nachricht(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return switch (key) {
                case "punish-help-kopf" -> "Kopf";
                case "punish-help-eintrag" -> "<white>%befehl% %nutzung%";
                case "punish-help-leer" -> "Leer";
                case "punish-help-fuss" -> "Fuss";
                default -> key;
            };
        });
        return config;
    }

    private BefehlsSender senderMitRechten(List<Component> empfangen, Set<String> rechte) {
        return new BefehlsSender() {
            @Override
            public void nachricht(Component nachricht) {
                empfangen.add(nachricht);
            }

            @Override
            public boolean hatRecht(String knoten) {
                return rechte.contains(knoten);
            }

            @Override
            public boolean istSpieler() {
                return false;
            }

            @Override
            public UUID spielerUuid() {
                return null;
            }

            @Override
            public String name() {
                return "Konsole";
            }
        };
    }

    private String textAller(List<Component> empfangen) {
        var mini = MiniMessageUtil.miniMessage();
        StringBuilder alle = new StringBuilder();
        for (Component zeile : empfangen) {
            alle.append(mini.serialize(zeile)).append('\n');
        }
        return alle.toString();
    }

    @Test
    void alleInternenBefehle_ohneBetaMarker() {
        Konfiguration config = config();
        var befehl = new PunishHelpCommand(new MessageService(config), config,
                mock(ProtectionService.class), mock(PlayerCache.class), mock(SpielerVerzeichnis.class));
        List<Component> empfangen = new ArrayList<>();
        var sender = senderMitRechten(empfangen, Set.of("punish.help", "punish.alts", "punish.chatfilter", "punish.gui"));

        befehl.ausfuehren(sender, new String[0]);

        String text = textAller(empfangen);
        assertTrue(text.contains("alts "), "Alts-Eintrag fehlt: " + text);
        assertTrue(text.contains("chatfilter "), "Chatfilter-Eintrag fehlt: " + text);
        assertTrue(text.contains("punish-gui (psgui)"), "GUI-Eintrag fehlt: " + text);
        assertFalse(text.contains("Beta"), "Interner Befehl als Beta markiert: " + text);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "punish-help-fuss|<gray>Beta: <white>/chatfilter|<gray>Weitere Befehle: <white>/chatfilter",
            "alts-kopf|<yellow>%spieler% <gray>(Beta: IP-Heuristik, kein Beweis)|<yellow>BetaSpieler <gray>(IP-Heuristik, kein Beweis)",
            "chatfilter-syntax|<red>/chatfilter <gray>(Beta: KI-Stufe)|<red>/chatfilter <gray>(KI-Stufe)",
            "chatfilter-an|<green>Chatfilter eingeschaltet. <gray>(Beta)|<green>Chatfilter eingeschaltet.",
            "chatfilter-aus|<yellow>Chatfilter ausgeschaltet. <gray>(Beta)|<yellow>Chatfilter ausgeschaltet.",
            "chatfilter-ai-an|<green>Chatfilter-KI eingeschaltet. <gray>(Beta)|<green>Chatfilter-KI eingeschaltet.",
            "chatfilter-ai-aus|<yellow>Chatfilter-KI ausgeschaltet. <gray>(Beta)|<yellow>Chatfilter-KI ausgeschaltet.",
            "chatfilter-an|<green>Eigener Text für %spieler% (Beta)|<green>Eigener Text für BetaSpieler",
            "chatfilter-an|<green>Eigener Text für %spieler%|<green>Eigener Text für BetaSpieler",
            "punish-version|<white>v4.0-beta|<white>v4.0-beta",
            "report-info|<white>%spieler% (Beta)|<white>BetaSpieler (Beta)"
    })
    void alteBetaHinweiseEntfallenBeiDerAusgabe(String schluessel, String vorlage, String erwartet) {
        Konfiguration config = config();
        when(config.nachricht(schluessel)).thenReturn(vorlage);

        var nachricht = new MessageService(config).komponenteOhnePraefix(schluessel,
                Map.of("spieler", "BetaSpieler"));

        assertEquals(MiniMessageUtil.miniMessage().deserialize(erwartet), nachricht);
    }

    @Test
    void stabileBefehle_ohneBetaMarker() {
        Konfiguration config = config();
        var befehl = new PunishHelpCommand(new MessageService(config), config,
                mock(ProtectionService.class), mock(PlayerCache.class), mock(SpielerVerzeichnis.class));
        List<Component> empfangen = new ArrayList<>();
        var sender = senderMitRechten(empfangen, Set.of("punish.help", "punish.ban", "punish.mute"));

        befehl.ausfuehren(sender, new String[0]);

        String text = textAller(empfangen);
        assertTrue(text.contains("ban "), "Ban-Eintrag fehlt: " + text);
        assertFalse(text.contains("ban (Beta)"), "Stabiler Befehl fälschlich als Beta markiert: " + text);
        assertFalse(text.contains("mute (Beta)"), "Stabiler Befehl fälschlich als Beta markiert: " + text);
    }
}
