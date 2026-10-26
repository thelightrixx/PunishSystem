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

import java.util.ArrayList;
import java.util.List;
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
    void betaBefehle_tragenBetaMarker() {
        Konfiguration config = config();
        var befehl = new PunishHelpCommand(new MessageService(config), config,
                mock(ProtectionService.class), mock(PlayerCache.class), mock(SpielerVerzeichnis.class));
        List<Component> empfangen = new ArrayList<>();
        var sender = senderMitRechten(empfangen, Set.of("punish.help", "punish.alts", "punish.chatfilter"));

        befehl.ausfuehren(sender, new String[0]);

        String text = textAller(empfangen);
        assertTrue(text.contains("alts (Beta)"), "Alts-Eintrag ohne Beta-Marker: " + text);
        assertTrue(text.contains("chatfilter (Beta)"), "Chatfilter-Eintrag ohne Beta-Marker: " + text);
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
