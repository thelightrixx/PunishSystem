package de.lightrixx.punish.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProtokollUpdateTest {

    @Test
    void helloVarianteRoundtripUndAltKompatibel() throws Exception {
        byte[] neu = Protokoll.hello("lobby", "v4.0-beta+EA25b", "bridge-1.21");
        Protokoll.Hello gelesen = Protokoll.leseHello(Protokoll.paket(neu).nutzdaten());
        assertEquals("bridge-1.21", gelesen.variante());
        byte[] alt = Protokoll.nachricht(Protokoll.HELLO, out -> {
            out.writeInt(Protokoll.VERSION);
            out.writeUTF("lobby");
            out.writeUTF("v4.0-beta+EA25b");
        });
        Protokoll.Hello altGelesen = Protokoll.leseHello(Protokoll.paket(alt).nutzdaten());
        assertEquals("", altGelesen.variante());
    }

    @Test
    void updateRoundtrip() throws Exception {
        byte[] daten = new byte[]{1, 2, 3};
        String hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        Protokoll.UpdateMeta meta = new Protokoll.UpdateMeta("bridge", "v4.0-beta+EA26b", 3, hash, 4096, 1);
        Protokoll.UpdateMeta metaGelesen = Protokoll.leseUpdateMeta(Protokoll.paket(Protokoll.updateMeta(meta)).nutzdaten());
        assertEquals("bridge", metaGelesen.variante());
        Protokoll.UpdateBlock block = new Protokoll.UpdateBlock("bridge", 0, daten);
        Protokoll.UpdateBlock blockGelesen = Protokoll.leseUpdateBlock(Protokoll.paket(Protokoll.updateBlock(block)).nutzdaten());
        assertEquals(0, blockGelesen.index());
        Protokoll.UpdateFertig fertig = new Protokoll.UpdateFertig("bridge", "v4.0-beta+EA26b", hash, 3);
        Protokoll.UpdateFertig fertigGelesen = Protokoll.leseUpdateFertig(Protokoll.paket(Protokoll.updateFertig(fertig)).nutzdaten());
        assertEquals(hash, fertigGelesen.sha256());
        Protokoll.UpdateQuittung quittung = new Protokoll.UpdateQuittung("bridge", "v4.0-beta+EA26b", true);
        Protokoll.UpdateQuittung quittungGelesen =
                Protokoll.leseUpdateQuittung(Protokoll.paket(Protokoll.updateQuittung(quittung)).nutzdaten());
        assertEquals(true, quittungGelesen.erfolg());
    }
}
