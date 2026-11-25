package de.lightrixx.punish.velocity;

import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.CachedPlayer;
import de.lightrixx.punish.model.WarnRecord;
import de.lightrixx.punish.service.*;
import de.lightrixx.punish.storage.PlayerCache;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StrafkatalogBefehlTest {
    VelocityKonfiguration config;
    ModerationsDienst moderation;
    BefehlsSender sender;
    WarnService warn;
    MuteService mute;
    StrafkatalogBefehl befehl;
    UUID ziel = UUID.randomUUID();

    @BeforeEach
    void vorbereiten() {
        config = mock(VelocityKonfiguration.class);
        when(config.standardScope()).thenReturn(de.lightrixx.punish.model.ServerScope.global());
        when(config.moderationsAbschnitt("strafkatalog")).thenReturn(Map.of("regeln", Map.of("spam", Map.of(
                "grund", "Spamming", "fenster-tage", 30, "stufen", List.of(Map.of("befehl", "warn"),
                Map.of("befehl", "mute", "dauer", "15m"))))));
        when(config.muteMaxMinuten()).thenReturn(120L);
        moderation = mock(ModerationsDienst.class);
        when(moderation.erfasse(any())).thenAnswer(a -> { a.getArgument(0, Runnable.class).run(); return List.of(); });
        when(moderation.katalogAngewendet(any(), anyString(), any(), anyInt(), anyLong(), anyString(), anyList()))
                .thenReturn(new ModerationsDienst.Fall(1, ziel, "Spieler", "Spam", 1, false, "spam", 1, 1, List.of()));
        sender = mock(BefehlsSender.class);
        when(sender.hatRecht(anyString())).thenReturn(true);
        when(sender.name()).thenReturn("Mod");
        when(sender.abstandsSchluessel()).thenReturn("mod-a");
        var cache = mock(PlayerCache.class);
        when(cache.istFrischeNamensZuordnung("Spieler")).thenReturn(true);
        when(cache.findenNachName("Spieler")).thenReturn(Optional.of(new CachedPlayer(ziel, "Spieler", "127.0.0.1", 1)));
        var schutz = mock(ProtectionService.class);
        when(schutz.pruefeSchutzSofort(eq(ziel), any())).thenReturn(Optional.of(ProtectionService.SchutzErgebnis.NICHT_GESCHUETZT));
        warn = mock(WarnService.class);
        when(warn.hinzufuegen(any(), anyString(), anyString(), anyString(), anyBoolean(), any()))
                .thenReturn(new WarnRecord(1, "Spieler", "Spamming", "Mod", 1));
        mute = mock(MuteService.class);
        befehl = new StrafkatalogBefehl(mock(MessageService.class), config, schutz, cache, null,
                mock(SpielerVerzeichnis.class), moderation,
                new StrafkatalogBefehl.Dienste(warn, mute, mock(TimeoutService.class), mock(BanService.class), mock(FreezeService.class)));
    }

    String vorschlagen() {
        befehl.ausfuehren(sender, new String[]{"Spieler", "spam", "--global"});
        var nachrichten = ArgumentCaptor.forClass(Component.class);
        verify(sender, atLeastOnce()).nachricht(nachrichten.capture());
        return nachrichten.getAllValues().stream().filter(c -> c.clickEvent() != null).map(c -> c.clickEvent().value())
                .reduce((a, b) -> b).orElseThrow(() -> new AssertionError(nachrichten.getAllValues())).split(" ")[2];
    }

    @Test
    void vorschlagVerhaengtNichtsUndBestaetigungFuehrtGenauEinmalAus() {
        String token = vorschlagen();
        verifyNoInteractions(warn, mute);
        befehl.ausfuehren(sender, new String[]{"confirm", token});
        befehl.ausfuehren(sender, new String[]{"confirm", token});
        verify(warn, times(1)).hinzufuegen(eq(ziel), eq("Spieler"), eq("Spamming"), anyString(), eq(false), eq(sender));
        verify(moderation, times(1)).katalogAngewendet(eq(ziel), eq("Spieler"), any(), eq(1), eq(0L), anyString(), anyList());
    }

    @Test
    void widerrufenesRechtOderGeaenderterPunktestandVerhindertAusfuehrung() {
        String token = vorschlagen();
        when(sender.hatRecht("punish.warn")).thenReturn(false);
        befehl.ausfuehren(sender, new String[]{"confirm", token});
        verifyNoInteractions(warn, mute);
        when(sender.hatRecht("punish.warn")).thenReturn(true);
        token = vorschlagen();
        when(moderation.anzahlRegel(eq(ziel), eq("spam"), anyLong())).thenReturn(1L);
        befehl.ausfuehren(sender, new String[]{"confirm", token});
        verifyNoInteractions(warn, mute);
    }

    @Test
    void anderesTeammitgliedKannBestaetigungNichtUebernehmen() {
        String token = vorschlagen();
        when(sender.abstandsSchluessel()).thenReturn("mod-b");
        befehl.ausfuehren(sender, new String[]{"confirm", token});
        verifyNoInteractions(warn, mute);
    }

    @Test
    void fehlgeschlageneStrafeErhoehtDieStufeNicht() {
        when(moderation.anzahlRegel(eq(ziel), eq("spam"), anyLong())).thenReturn(1L);
        String token = vorschlagen();
        befehl.ausfuehren(sender, new String[]{"confirm", token});
        verify(mute).muten(eq(ziel), anyString(), eq(900_000L), anyString(), anyString(), any(), any(), eq(false), eq(sender));
        verify(moderation, never()).katalogAngewendet(any(), anyString(), any(), anyInt(), anyLong(), anyString(), anyList());
    }

    @Test
    void letzteStufeWiederholtSichUndUngueltigeRegelBleibtDeaktiviert() {
        var regeln = Strafkatalog.lesen(config.moderationsAbschnitt("strafkatalog"));
        assertEquals(2, regeln.get("spam").stufenNummer(100));
        assertTrue(Strafkatalog.lesen(Map.of("regeln", Map.of("bad", Map.of("stufen",
                List.of(Map.of("befehl", "mute", "dauer", "perm")))))).isEmpty());
    }

    @Test
    void strafeUeberBestehendemLimitErzeugtKeinenBestaetigungsknopf() {
        when(moderation.anzahlRegel(eq(ziel), eq("spam"), anyLong())).thenReturn(1L);
        when(config.muteMaxMinuten()).thenReturn(1L);
        befehl.ausfuehren(sender, new String[]{"Spieler", "spam", "--global"});
        var nachrichten = ArgumentCaptor.forClass(Component.class);
        verify(sender).nachricht(nachrichten.capture());
        assertNull(nachrichten.getValue().clickEvent());
        verifyNoInteractions(warn, mute);
    }
}
