package de.lightrixx.punish.velocity;

import de.lightrixx.punish.core.BefehlsSender;
import de.lightrixx.punish.core.SpielerVerzeichnis;
import de.lightrixx.punish.model.MuteRecord;
import de.lightrixx.punish.model.ServerScope;
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

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ModerationsBefehlTest {
    ModerationsDienst dienst;
    BefehlsSender sender;
    MuteService mute;
    ChatfilterStaffelService staffel;
    ModerationsBefehl befehl;
    UUID uuid = UUID.randomUUID();
    long zeit = System.currentTimeMillis() - 1000;
    long ablauf = zeit + 60_000;

    @BeforeEach
    void vorbereiten() {
        dienst = mock(ModerationsDienst.class);
        when(dienst.erfasse(any())).thenAnswer(a -> { a.getArgument(0, Runnable.class).run(); return List.of(); });
        sender = mock(BefehlsSender.class);
        when(sender.hatRecht(anyString())).thenReturn(true);
        when(sender.abstandsSchluessel()).thenReturn("Mod-a");
        when(sender.name()).thenReturn("Mod");
        mute = mock(MuteService.class);
        when(mute.finden(eq(uuid), any())).thenReturn(Optional.of(mute(zeit)));
        staffel = mock(ChatfilterStaffelService.class);
        var aktion = Map.<String, Object>of("history", 23L, "typ", "MUTE", "grund", "Filter", "scope", "global",
                "erstellt", zeit, "ablauf", ablauf);
        var p = new ModerationsDienst.Pruefung(1, uuid, "Spieler", zeit, "CHAT", "KI", .9,
                "hash", "Text", false, ModerationsDienst.PruefStatus.FEHLALARM, "Mod", List.of(aktion));
        when(dienst.pruefung(1)).thenReturn(p);
        when(dienst.korrekturNoetig(eq(p), eq(aktion))).thenReturn(true);
        befehl = new ModerationsBefehl(ModerationsBefehl.Bereich.PRUEFUNG, mock(MessageService.class),
                mock(VelocityKonfiguration.class), mock(ProtectionService.class), mock(PlayerCache.class), null,
                mock(SpielerVerzeichnis.class), dienst, mock(HistoryService.class), mock(ReportService.class),
                staffel, mock(WarnService.class), mute);
    }

    MuteRecord mute(long erstellt) {
        return new MuteRecord(uuid, "Spieler", "Filter", "Chatfilter", erstellt, ablauf, ServerScope.global());
    }

    String vorschlag() {
        befehl.ausfuehren(sender, new String[]{"korrektur", "1", "23"});
        var nachrichten = ArgumentCaptor.forClass(Component.class);
        verify(sender, atLeastOnce()).nachricht(nachrichten.capture());
        return nachrichten.getAllValues().stream().filter(c -> c.clickEvent() != null).map(c -> c.clickEvent().value())
                .reduce((a, b) -> b).orElseThrow().split(" ")[2];
    }

    @Test
    void korrekturWartetAufBestaetigungUndHebtGeaendertenMuteNichtAuf() {
        String token = vorschlag();
        verify(mute, never()).entmuten(any(), anyString(), anyString(), any(), any());
        when(mute.finden(eq(uuid), any())).thenReturn(Optional.of(mute(zeit + 1)));
        befehl.ausfuehren(sender, new String[]{"confirm", token});
        verify(mute, never()).entmuten(any(), anyString(), anyString(), any(), any());
    }

    @Test
    void korrekturPrueftGegenrechtErneutUndBestaetigungIstEinmalig() {
        String token = vorschlag();
        when(sender.hatRecht("punish.unmute")).thenReturn(false);
        befehl.ausfuehren(sender, new String[]{"confirm", token});
        verify(mute, never()).entmuten(any(), anyString(), anyString(), any(), any());
        when(sender.hatRecht("punish.unmute")).thenReturn(true);
        token = vorschlag();
        befehl.ausfuehren(sender, new String[]{"confirm", token});
        befehl.ausfuehren(sender, new String[]{"confirm", token});
        verify(mute, times(1)).entmuten(eq(uuid), eq("Spieler"), anyString(), eq(ServerScope.global()), any());
    }

    @Test
    void fehlalarmEntferntDenKonkretenTrefferAusDerStaffelUndBrauchtBearbeitungsrecht() {
        when(sender.hatRecht("punish.chatfilter.pruefung.bearbeiten")).thenReturn(false);
        befehl.ausfuehren(sender, new String[]{"fehlalarm", "1"});
        verify(dienst, never()).pruefen(anyLong(), any(), anyString());
        when(sender.hatRecht("punish.chatfilter.pruefung.bearbeiten")).thenReturn(true);
        befehl.ausfuehren(sender, new String[]{"fehlalarm", "1"});
        verify(dienst).pruefen(eq(1L), eq(ModerationsDienst.PruefStatus.FEHLALARM), anyString());
        verify(staffel).entferneTreffer(uuid, 1);
    }
}
