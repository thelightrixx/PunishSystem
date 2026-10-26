package de.lightrixx.punish.command;

import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.Permissions;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PunishEditRechtTest {

    private static PunishEditCommand befehl() {
        return new PunishEditCommand(
                mock(de.lightrixx.punish.service.MessageService.class),
                mock(de.lightrixx.punish.core.Konfiguration.class),
                mock(de.lightrixx.punish.service.ProtectionService.class),
                mock(de.lightrixx.punish.storage.PlayerCache.class),
                mock(de.lightrixx.punish.service.VerstossService.class),
                mock(de.lightrixx.punish.service.WarnService.class),
                mock(de.lightrixx.punish.service.NoteService.class),
                mock(de.lightrixx.punish.service.BanService.class),
                mock(de.lightrixx.punish.service.MuteService.class),
                mock(de.lightrixx.punish.service.TimeoutService.class),
                mock(de.lightrixx.punish.service.IpBanService.class),
                mock(de.lightrixx.punish.service.IpMuteService.class),
                mock(de.lightrixx.punish.service.IpTimeoutService.class),
                mock(de.lightrixx.punish.util.TimeParser.class),
                mock(de.lightrixx.punish.core.AuftragsPlaner.class),
                mock(de.lightrixx.punish.core.SpielerVerzeichnis.class));
    }

    private static BefehlsSender senderMit(Set<String> rechte) {
        BefehlsSender sender = mock(BefehlsSender.class);
        when(sender.hatRecht(anyString())).thenAnswer(inv -> rechte.contains(inv.getArgument(0)));
        return sender;
    }

    @Test
    void editRechtDarfAlleTypen() {
        var befehl = befehl();
        var sender = senderMit(Set.of(Permissions.PUNISH_EDIT.node()));
        assertTrue(befehl.hatRecht(sender, new String[]{"ban", "X"}));
        assertTrue(befehl.hatRecht(sender, new String[]{"warn", "X"}));
        assertTrue(befehl.hatRecht(sender, new String[]{"show", "mute", "X"}));
    }

    @Test
    void warnRechtNurWarnTyp() {
        var befehl = befehl();
        var sender = senderMit(Set.of(Permissions.PUNISH_WARN.node()));
        assertTrue(befehl.hatRecht(sender, new String[]{"warn", "X"}));
        assertFalse(befehl.hatRecht(sender, new String[]{"ban", "X"}));
        assertFalse(befehl.hatRecht(sender, new String[]{"mute", "X"}));
        assertFalse(befehl.hatRecht(sender, new String[]{"show", "ban", "X"}));
        assertTrue(befehl.hatRecht(sender, new String[]{"show", "warn", "X"}));
    }

    @Test
    void banRechtNurBanTyp() {
        var befehl = befehl();
        var sender = senderMit(Set.of(Permissions.PUNISH_BAN.node()));
        assertTrue(befehl.hatRecht(sender, new String[]{"ban", "X"}));
        assertFalse(befehl.hatRecht(sender, new String[]{"warn", "X"}));
    }

    @Test
    void ohneRechtKeinEdit() {
        var befehl = befehl();
        var sender = senderMit(Set.of());
        assertFalse(befehl.hatRecht(sender, new String[]{"warn", "X"}));
        assertFalse(befehl.hatRecht(sender, new String[]{"unbekannt", "X"}));
    }

    @Test
    void nullSenderAbgelehnt() {
        assertFalse(befehl().hatRecht(null, new String[]{"ban", "X"}));
    }
}
