package de.lightrixx.punish.velocity;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VelocitySetupBefehlTest {

    @Test
    void checkZaehltKurzeSecretsNichtAlsEingerichtet(@org.junit.jupiter.api.io.TempDir java.io.File ordner) {
        var proxy = org.mockito.Mockito.mock(com.velocitypowered.api.proxy.ProxyServer.class);
        var lobby = org.mockito.Mockito.mock(com.velocitypowered.api.proxy.server.RegisteredServer.class);
        var info = org.mockito.Mockito.mock(com.velocitypowered.api.proxy.server.ServerInfo.class);
        org.mockito.Mockito.when(info.getName()).thenReturn("lobby");
        org.mockito.Mockito.when(lobby.getServerInfo()).thenReturn(info);
        org.mockito.Mockito.when(proxy.getAllServers()).thenReturn(java.util.List.of(lobby));
        var config = org.mockito.Mockito.mock(VelocityKonfiguration.class);
        org.mockito.Mockito.when(config.brueckenSecrets()).thenReturn(java.util.Map.of("lobby", "zu-kurz"));
        var nachrichten = org.mockito.Mockito.mock(de.lightrixx.punish.service.MessageService.class);
        var sender = org.mockito.Mockito.mock(de.lightrixx.punish.core.BefehlsSender.class);
        var befehl = new VelocitySetupBefehl(nachrichten, config,
                org.mockito.Mockito.mock(de.lightrixx.punish.service.ProtectionService.class),
                org.mockito.Mockito.mock(de.lightrixx.punish.storage.PlayerCache.class),
                org.mockito.Mockito.mock(de.lightrixx.punish.core.AuftragsPlaner.class),
                proxy, null, ordner, () -> "4.0");

        befehl.ausfuehren(sender, new String[]{"check"});

        org.mockito.Mockito.verify(nachrichten).senden(sender,
                de.lightrixx.punish.core.MessageKeys.SETUP_CHECK_SECRETS,
                java.util.Map.of("anzahl", "0", "max", "1"));
        org.mockito.Mockito.verify(nachrichten).senden(sender,
                de.lightrixx.punish.core.MessageKeys.SETUP_CHECK_SECRETS_FEHLT,
                java.util.Map.of("server", "lobby"));
        befehl.ausfuehren(sender, new String[]{"bridge", "lobby"});
        org.mockito.Mockito.verify(nachrichten).senden(sender,
                de.lightrixx.punish.core.MessageKeys.SETUP_BRIDGE_KEIN_SECRET,
                java.util.Map.of("server", "lobby"));
    }

    @Test
    void secretIstLangUndEindeutig() {
        String erstes = VelocitySetupBefehl.generiereSecret();
        String zweites = VelocitySetupBefehl.generiereSecret();

        assertTrue(erstes.length() >= 40, "Secret zu kurz: " + erstes);
        assertNotEquals(erstes, zweites, "Zwei Secrets dürfen nicht identisch sein.");
    }

    @Test
    void bridgeDateiEnthaeltFertigenBlock() throws Exception {
        Path ordner = Files.createTempDirectory("punish-setup");
        Path datei = VelocitySetupBefehl.schreibeBridgeDatei(ordner, "survival", "geheim");

        String inhalt = Files.readString(datei);
        assertTrue(datei.getFileName().toString().equals("bridge-survival.yml"), inhalt);
        assertTrue(inhalt.contains("server-name: \"survival\""), inhalt);
        assertTrue(inhalt.contains("tunnel-secret: \"geheim\""), inhalt);
        assertTrue(inhalt.contains("plugins/PunishSystemBridge/config/general.yml"), inhalt);
    }
}
