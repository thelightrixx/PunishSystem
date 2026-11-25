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
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PunishVersionCommandTest {

    private static Konfiguration config() {
        Konfiguration config = mock(Konfiguration.class);
        when(config.praefix()).thenReturn("[Punish]");
        when(config.nachricht(anyString())).thenAnswer(inv -> switch ((String) inv.getArgument(0)) {
            case "punish-version" -> "<gray>PunishSystem <white>%version% <gray>by <white>lightrixx";
            case "punish-version-server" -> "<gray>Server <white>%server%<gray>: <white>%version%";
            case "punish-version-server-leer" -> "<gray>Keine Bridge-Version gemeldet.";
            case "punish-version-verwendung" -> "<yellow>Verwendung: <white>/punish-version [server|proxy]";
            default -> "";
        });
        return config;
    }

    private static PunishVersionCommand befehl(Supplier<String> proxyVersion,
                                               Supplier<Map<String, String>> serverVersionen) {
        Konfiguration config = config();
        return new PunishVersionCommand(new MessageService(config), config,
                mock(ProtectionService.class), mock(PlayerCache.class), mock(SpielerVerzeichnis.class),
                proxyVersion, serverVersionen);
    }

    private static BefehlsSender sender(List<Component> empfangen) {
        return new BefehlsSender() {
            @Override
            public void nachricht(Component nachricht) {
                empfangen.add(nachricht);
            }

            @Override
            public boolean hatRecht(String knoten) {
                return true;
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

    private static String text(List<Component> empfangen) {
        var mini = MiniMessageUtil.miniMessage();
        StringBuilder alle = new StringBuilder();
        for (Component zeile : empfangen) {
            alle.append(mini.stripTags(mini.serialize(zeile))).append('\n');
        }
        return alle.toString();
    }

    private static List<Component> ausfuehren(PunishVersionCommand befehl, String... args) {
        List<Component> empfangen = new ArrayList<>();
        befehl.ausfuehren(sender(empfangen), args);
        return empfangen;
    }

    @Test
    void ohneArgument_zeigtProxyUndServerVersionen() {
        String text = text(ausfuehren(befehl(() -> "4.5.1",
                () -> Map.of("lobby", "v4.5.1", "survival", "4.5.0"))));

        assertTrue(text.contains("PunishSystem v4.5.1"), text);
        assertTrue(text.contains("Server lobby: v4.5.1"), text);
        assertTrue(text.contains("Server survival: v4.5.0"), text);
    }

    @Test
    void proxyArgument_zeigtNurProxyVersion() {
        String text = text(ausfuehren(befehl(() -> "v4.5.1",
                () -> Map.of("lobby", "v4.5.1")), "proxy"));

        assertTrue(text.contains("PunishSystem v4.5.1"), text);
        assertFalse(text.contains("Server lobby"), text);
    }

    @Test
    void serverArgument_zeigtNurServerVersionen() {
        String text = text(ausfuehren(befehl(() -> "v4.5.1",
                () -> Map.of("lobby", "v4.5.1")), "server"));

        assertTrue(text.contains("Server lobby: v4.5.1"), text);
        assertFalse(text.contains("PunishSystem"), text);
    }

    @Test
    void serverArgumentOhneMeldung_zeigtHinweis() {
        String text = text(ausfuehren(befehl(() -> "v4.5.1", Map::of), "server"));

        assertTrue(text.contains("Keine Bridge-Version gemeldet"), text);
    }

    @Test
    void ohneArgumentOhneMeldung_zeigtNurProxyVersion() {
        String text = text(ausfuehren(befehl(() -> "v4.5.1", Map::of)));

        assertTrue(text.contains("PunishSystem v4.5.1"), text);
        assertFalse(text.contains("Keine Bridge-Version gemeldet"), text);
    }

    @Test
    void unbekanntesArgument_zeigtVerwendung() {
        String text = text(ausfuehren(befehl(() -> "v4.5.1", Map::of), "netzwerk"));

        assertTrue(text.contains("/punish-version [server|proxy]"), text);
        assertFalse(text.contains("PunishSystem"), text);
    }

    @Test
    void tabVervollstaendigtServerUndProxy() {
        PunishVersionCommand befehl = befehl(() -> "v4.5.1", Map::of);

        assertEquals(List.of("server", "proxy"), befehl.vervollstaendigen(sender(new ArrayList<>()), new String[0]));
        assertEquals(List.of("proxy"), befehl.vervollstaendigen(sender(new ArrayList<>()), new String[]{"prox"}));
        assertEquals(List.of(), befehl.vervollstaendigen(sender(new ArrayList<>()), new String[]{"proxy", "x"}));
    }
}
