package de.lightrixx.punish.velocity;

import de.lightrixx.punish.command.PunishCommand;
import de.lightrixx.punish.command.PunishVorlageCommand;
import de.lightrixx.punish.core.AuftragsPlaner;
import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.Konfiguration;
import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.service.MessageService;
import de.lightrixx.punish.service.ProtectionService;
import de.lightrixx.punish.storage.PlayerCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalMatchers;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PunishVorlageCommandTest {

    private MessageService messageService;
    private Konfiguration config;
    private BefehlsSender sender;
    private PunishCommand ban;
    private PunishCommand mute;
    private PunishCommand kick;
    private PunishVorlageCommand befehl;

    @BeforeEach
    void aufsetzen() {
        messageService = Mockito.mock(MessageService.class);
        config = Mockito.mock(Konfiguration.class);
        var protection = Mockito.mock(ProtectionService.class);
        var playerCache = Mockito.mock(PlayerCache.class);
        var planer = Mockito.mock(AuftragsPlaner.class);
        var verzeichnis = Mockito.mock(SpielerVerzeichnis.class);
        sender = Mockito.mock(BefehlsSender.class);
        ban = Mockito.mock(PunishCommand.class);
        mute = Mockito.mock(PunishCommand.class);
        kick = Mockito.mock(PunishCommand.class);

        when(config.vorlagen()).thenReturn(List.of(
                new Konfiguration.Vorlage("ban", "Cheating", "30d"),
                new Konfiguration.Vorlage("mute", "Spamming", "1h"),
                new Konfiguration.Vorlage("kick", "AFK", "")));
        when(config.bekannteServer()).thenReturn(List.of());
        when(config.standardScope()).thenReturn(de.lightrixx.punish.model.ServerScope.unbekannt());
        when(sender.hatRecht(anyString())).thenReturn(true);
        when(ban.hatRecht(any(), any())).thenReturn(true);
        when(mute.hatRecht(any(), any())).thenReturn(true);
        when(kick.hatRecht(any(), any())).thenReturn(true);

        Map<String, PunishCommand> ziele = new LinkedHashMap<>();
        ziele.put("ban", ban);
        ziele.put("mute", mute);
        ziele.put("kick", kick);
        befehl = new PunishVorlageCommand(messageService, config, protection, playerCache,
                planer, verzeichnis, ziele);
    }

    @Test
    void banVorlageDelegiertMitDauerUndGrund() {
        befehl.ausfuehren(sender, new String[]{"Steve", "Cheating"});
        verify(ban).ausfuehren(eq(sender),
                AdditionalMatchers.aryEq(new String[]{"Steve", "30d", "Cheating"}));
        verify(mute, never()).ausfuehren(any(), any());
    }

    @Test
    void vorlagenNameCaseInsensitiv() {
        befehl.ausfuehren(sender, new String[]{"Steve", "spamming"});
        verify(mute).ausfuehren(eq(sender),
                AdditionalMatchers.aryEq(new String[]{"Steve", "1h", "Spamming"}));
    }

    @Test
    void zusatztextWirdAnGrundAngehaengt() {
        befehl.ausfuehren(sender, new String[]{"Steve", "Cheating", "Kill-Aura", "--lobby"});
        verify(ban).ausfuehren(eq(sender),
                AdditionalMatchers.aryEq(new String[]{"Steve", "30d", "Cheating", "Kill-Aura", "--lobby"}));
    }

    @Test
    void vorlageOhneDauerDelegiertOhneDauer() {
        befehl.ausfuehren(sender, new String[]{"Steve", "AFK"});
        verify(kick).ausfuehren(eq(sender),
                AdditionalMatchers.aryEq(new String[]{"Steve", "AFK"}));
    }

    @Test
    void unbekannteVorlageSendetMeldungUndListe() {
        befehl.ausfuehren(sender, new String[]{"Steve", "Quatsch"});
        verify(messageService).senden(eq(sender), eq(MessageKeys.PUNISH_VORLAGE_UNBEKANNT), anyMap());
        verify(messageService).sendeBlock(eq(sender), anyList());
        verify(ban, never()).ausfuehren(any(), any());
    }

    @Test
    void fehlendesZielRechtBlockiertDelegation() {
        when(ban.hatRecht(any(), any())).thenReturn(false);
        befehl.ausfuehren(sender, new String[]{"Steve", "Cheating"});
        verify(messageService).senden(sender, MessageKeys.KEIN_RECHT);
        verify(ban, never()).ausfuehren(any(), any());
    }

    @Test
    void nurSpielerOderLeerZeigtListe() {
        befehl.ausfuehren(sender, new String[]{"Steve"});
        befehl.ausfuehren(sender, new String[0]);
        verify(messageService, Mockito.times(2)).sendeBlock(eq(sender), anyList());
        verify(ban, never()).ausfuehren(any(), any());
        verify(mute, never()).ausfuehren(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void listeEnthaeltNurSichtbareVorlagen() {
        when(sender.hatRecht(eq("punish.ban"))).thenReturn(false);
        befehl.ausfuehren(sender, new String[0]);
        org.mockito.ArgumentCaptor<List> captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(messageService).sendeBlock(eq(sender), captor.capture());
        assertEquals(3, captor.getValue().size());
    }

    @Test
    void completionBietetVorlagenNamenAn() {
        List<String> vorschlaege = befehl.vervollstaendigen(sender, new String[]{"Steve", "Che"});
        assertEquals(List.of("Cheating"), vorschlaege);
    }

    @Test
    void completionBietetKeineNamenMitLeerzeichenAn() {
        when(config.vorlagen()).thenReturn(List.of(
                new Konfiguration.Vorlage("ban", "Cool-down Streit", "1h"),
                new Konfiguration.Vorlage("ban", "Cheating", "30d")));
        List<String> vorschlaege = befehl.vervollstaendigen(sender, new String[]{"Steve", ""});
        assertTrue(vorschlaege.contains("Cheating"));
        assertEquals(1, vorschlaege.size());
    }
}
