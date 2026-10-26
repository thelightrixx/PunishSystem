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
}
