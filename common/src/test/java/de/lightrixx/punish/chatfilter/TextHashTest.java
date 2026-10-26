package de.lightrixx.punish.chatfilter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TextHashTest {

    @Test
    void bekannterWert() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                TextHash.sha256Hex("abc"));
    }

    @Test
    void deterministischUnd64Zeichen() {
        String hash = TextHash.sha256Hex("Du Hurensohn!");
        assertEquals(64, hash.length());
        assertEquals(hash, TextHash.sha256Hex("Du Hurensohn!"));
    }

    @Test
    void unterschiedlicheTexte_unterschiedlicheHashes() {
        assertNotEquals(TextHash.sha256Hex("a"), TextHash.sha256Hex("b"));
    }
}
