package de.lightrixx.punish.bridge;

import de.lightrixx.punish.bridge.chatfilter.ChatfilterKonfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BridgeDateienTest {

    @Test
    void neuesWortWirdNichtAnDieVorherigeZeileGehaengt(@TempDir Path ordner) throws Exception {
        Path wortliste = Files.writeString(ordner.resolve("wortliste.txt"), "vorher");

        BridgeDateien.fuegeWortHinzu(wortliste.toFile(), "nachher");

        assertEquals("vorher" + System.lineSeparator() + "nachher" + System.lineSeparator(),
                Files.readString(wortliste));
    }

    @Test
    void nichtLesbarerDateiStandBleibtVonLeerenDateienUnterscheidbar(@TempDir Path ordner) {
        assertEquals(java.util.Optional.of(""), BridgeDateien.chatfilterStand(ordner.toFile(), null));
        var config = mock(ChatfilterKonfiguration.class);
        when(config.dateiAufloesen(anyString())).thenThrow(new IllegalStateException("Testfehler"));
        when(config.wortlisteDateiname()).thenReturn("wortliste.txt");

        assertTrue(BridgeDateien.chatfilterStand(ordner.toFile(), config).isEmpty());
    }
}
