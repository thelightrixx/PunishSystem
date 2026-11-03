package de.lightrixx.punish.velocity;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReloadTeilfehlerRegressionTest {

    @Test
    void fehlerImRepoWirdGesammeltUndBrichtDenReloadNichtAb() {
        List<String> teilfehler = new ArrayList<>();
        VelocityReloadBefehl.ladeRepo(teilfehler, "Erster", () -> {
        });
        VelocityReloadBefehl.ladeRepo(teilfehler, "Kaputt", () -> {
            throw new IllegalStateException("Testfehler");
        });
        VelocityReloadBefehl.ladeRepo(teilfehler, "Letzter", () -> {
        });
        assertEquals(List.of("Kaputt"), teilfehler);
    }
    @Test
    void reloadLaedtAuchFreezesVorDemBridgeSync() {
        var planer = org.mockito.Mockito.mock(de.lightrixx.punish.core.AuftragsPlaner.class);
        org.mockito.Mockito.doAnswer(inv -> {
            inv.getArgument(0, Runnable.class).run();
            return null;
        }).when(planer).aufgabeAsync(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doAnswer(inv -> {
            inv.getArgument(0, Runnable.class).run();
            return null;
        }).when(planer).aufgabe(org.mockito.ArgumentMatchers.any());
        var freezes = org.mockito.Mockito.mock(de.lightrixx.punish.storage.FreezeRepository.class);
        var kanal = org.mockito.Mockito.mock(BrueckenKanal.class);
        var reload = new VelocityReloadBefehl(
                org.mockito.Mockito.mock(de.lightrixx.punish.service.MessageService.class),
                org.mockito.Mockito.mock(VelocityKonfiguration.class),
                org.mockito.Mockito.mock(de.lightrixx.punish.service.ProtectionService.class),
                org.mockito.Mockito.mock(de.lightrixx.punish.storage.PlayerCache.class), planer,
                org.mockito.Mockito.mock(de.lightrixx.punish.storage.BanRepository.class),
                org.mockito.Mockito.mock(de.lightrixx.punish.storage.IpBanRepository.class),
                org.mockito.Mockito.mock(de.lightrixx.punish.storage.MuteRepository.class),
                org.mockito.Mockito.mock(de.lightrixx.punish.storage.TimeoutRepository.class),
                org.mockito.Mockito.mock(de.lightrixx.punish.storage.VerstossRepository.class),
                null, null, null,
                org.mockito.Mockito.mock(de.lightrixx.punish.storage.HistoryRepository.class),
                org.mockito.Mockito.mock(de.lightrixx.punish.service.AutoBannService.class),
                null, null, null);
        reload.setFreezeRepository(freezes);
        reload.setBrueckenKanal(kanal);

        reload.ausfuehren(org.mockito.Mockito.mock(de.lightrixx.punish.core.BefehlsSender.class), new String[0]);

        var reihenfolge = org.mockito.Mockito.inOrder(freezes, kanal);
        reihenfolge.verify(freezes).laden();
        reihenfolge.verify(kanal).mutePushAnAlle();
    }
}
