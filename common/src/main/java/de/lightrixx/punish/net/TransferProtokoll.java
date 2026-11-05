package de.lightrixx.punish.net;

import java.io.DataInput;
import java.io.IOException;
import java.util.Objects;

import static de.lightrixx.punish.net.Protokoll.MAX_MODELL_CHUNK_BYTES;
import static de.lightrixx.punish.net.Protokoll.MODELL_BLOCK;
import static de.lightrixx.punish.net.Protokoll.MODELL_FERTIG;
import static de.lightrixx.punish.net.Protokoll.MODELL_META;
import static de.lightrixx.punish.net.Protokoll.MODELL_QUITTUNG;
import static de.lightrixx.punish.net.Protokoll.MODELL_STAND;
import static de.lightrixx.punish.net.Protokoll.ModellBlock;
import static de.lightrixx.punish.net.Protokoll.ModellFertig;
import static de.lightrixx.punish.net.Protokoll.ModellMeta;
import static de.lightrixx.punish.net.Protokoll.ModellQuittung;
import static de.lightrixx.punish.net.Protokoll.ModellStand;
import static de.lightrixx.punish.net.Protokoll.TEXT_BLOCK;
import static de.lightrixx.punish.net.Protokoll.TEXT_FERTIG;
import static de.lightrixx.punish.net.Protokoll.TEXT_META;
import static de.lightrixx.punish.net.Protokoll.TEXT_QUITTUNG;
import static de.lightrixx.punish.net.Protokoll.TEXT_STAND;
import static de.lightrixx.punish.net.Protokoll.TextBlock;
import static de.lightrixx.punish.net.Protokoll.TextFertig;
import static de.lightrixx.punish.net.Protokoll.TextMeta;
import static de.lightrixx.punish.net.Protokoll.TextQuittung;
import static de.lightrixx.punish.net.Protokoll.TextStand;
import static de.lightrixx.punish.net.Protokoll.UPDATE_BLOCK;
import static de.lightrixx.punish.net.Protokoll.UPDATE_FERTIG;
import static de.lightrixx.punish.net.Protokoll.UPDATE_META;
import static de.lightrixx.punish.net.Protokoll.UPDATE_QUITTUNG;
import static de.lightrixx.punish.net.Protokoll.UPDATE_STAND;
import static de.lightrixx.punish.net.Protokoll.UpdateBlock;
import static de.lightrixx.punish.net.Protokoll.UpdateFertig;
import static de.lightrixx.punish.net.Protokoll.UpdateMeta;
import static de.lightrixx.punish.net.Protokoll.UpdateQuittung;
import static de.lightrixx.punish.net.Protokoll.UpdateStand;
import static de.lightrixx.punish.net.Protokoll.nachricht;

final class TransferProtokoll {

    private TransferProtokoll() {
    }

    static byte[] modellStand(String modellHash, String vocabHash) throws IOException {
        var stand = new ModellStand(modellHash, vocabHash);
        return nachricht(MODELL_STAND, out -> {
            out.writeUTF(stand.modellHash());
            out.writeUTF(stand.vocabHash());
        });
    }

    static ModellStand leseModellStand(DataInput in) throws IOException {
        try {
            return new ModellStand(in.readUTF(), in.readUTF());
        } catch (IllegalArgumentException e) {
            throw new IOException("Ungültiger Modell-Stand.", e);
        }
    }

    static byte[] modellMeta(ModellMeta meta) throws IOException {
        Objects.requireNonNull(meta, "meta");
        return nachricht(MODELL_META, out -> {
            out.writeUTF(meta.dateiname());
            out.writeLong(meta.groesse());
            out.writeUTF(meta.sha256());
            out.writeInt(meta.chunkGroesse());
            out.writeInt(meta.anzahl());
        });
    }

    static ModellMeta leseModellMeta(DataInput in) throws IOException {
        try {
            return new ModellMeta(in.readUTF(), in.readLong(), in.readUTF(),
                    in.readInt(), in.readInt());
        } catch (IllegalArgumentException e) {
            throw new IOException("Ungültige Modell-Meta.", e);
        }
    }

    static byte[] modellBlock(ModellBlock block) throws IOException {
        Objects.requireNonNull(block, "block");
        return nachricht(MODELL_BLOCK, out -> {
            out.writeUTF(block.dateiname());
            out.writeInt(block.index());
            out.writeInt(block.daten().length);
            out.write(block.daten());
        });
    }

    static ModellBlock leseModellBlock(DataInput in) throws IOException {
        try {
            String name = in.readUTF();
            int index = in.readInt();
            int laenge = in.readInt();
            if (laenge < 1 || laenge > MAX_MODELL_CHUNK_BYTES) {
                throw new IOException("Ungültige Block-Länge: " + laenge);
            }
            byte[] daten = new byte[laenge];
            in.readFully(daten);
            return new ModellBlock(name, index, daten);
        } catch (IllegalArgumentException e) {
            throw new IOException("Ungültiger Modell-Block.", e);
        }
    }

    static byte[] modellFertig(ModellFertig fertig) throws IOException {
        Objects.requireNonNull(fertig, "fertig");
        return nachricht(MODELL_FERTIG, out -> {
            out.writeUTF(fertig.dateiname());
            out.writeUTF(fertig.sha256());
            out.writeLong(fertig.groesse());
        });
    }

    static ModellFertig leseModellFertig(DataInput in) throws IOException {
        try {
            return new ModellFertig(in.readUTF(), in.readUTF(), in.readLong());
        } catch (IllegalArgumentException e) {
            throw new IOException("Ungültiges Modell-Fertig.", e);
        }
    }

    static byte[] modellQuittung(ModellQuittung quittung) throws IOException {
        Objects.requireNonNull(quittung, "quittung");
        return nachricht(MODELL_QUITTUNG, out -> {
            out.writeUTF(quittung.dateiname());
            out.writeBoolean(quittung.erfolg());
        });
    }

    static ModellQuittung leseModellQuittung(DataInput in) throws IOException {
        try {
            return new ModellQuittung(in.readUTF(), in.readBoolean());
        } catch (IllegalArgumentException e) {
            throw new IOException("Ungültige Modell-Quittung.", e);
        }
    }

    static String textPaketHash(java.util.Map<String, byte[]> dateien) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            var namen = new java.util.ArrayList<>(dateien.keySet());
            namen.sort(String::compareTo);
            for (String name : namen) {
                byte[] daten = dateien.get(name);
                if (daten == null) {
                    continue;
                }
                digest.update(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(daten);
                digest.update((byte) 0);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 nicht verfügbar.", e);
        }
    }

    static byte[] textStand(String paketHash) throws IOException {
        var stand = new TextStand(paketHash);
        return nachricht(TEXT_STAND, out -> out.writeUTF(stand.paketHash()));
    }

    static TextStand leseTextStand(DataInput in) throws IOException {
        try {
            return new TextStand(in.readUTF());
        } catch (IllegalArgumentException e) {
            throw new IOException("Ungültiger Text-Stand.", e);
        }
    }

    static byte[] textMeta(TextMeta meta) throws IOException {
        Objects.requireNonNull(meta, "meta");
        return nachricht(TEXT_META, out -> {
            out.writeUTF(meta.dateiname());
            out.writeLong(meta.groesse());
            out.writeUTF(meta.sha256());
            out.writeInt(meta.chunkGroesse());
            out.writeInt(meta.anzahl());
        });
    }

    static TextMeta leseTextMeta(DataInput in) throws IOException {
        try {
            return new TextMeta(in.readUTF(), in.readLong(), in.readUTF(),
                    in.readInt(), in.readInt());
        } catch (IllegalArgumentException e) {
            throw new IOException("Ungültige Text-Meta.", e);
        }
    }

    static byte[] textBlock(TextBlock block) throws IOException {
        Objects.requireNonNull(block, "block");
        return nachricht(TEXT_BLOCK, out -> {
            out.writeUTF(block.dateiname());
            out.writeInt(block.index());
            out.writeInt(block.daten().length);
            out.write(block.daten());
        });
    }

    static TextBlock leseTextBlock(DataInput in) throws IOException {
        try {
            String name = in.readUTF();
            int index = in.readInt();
            int laenge = in.readInt();
            if (laenge < 1 || laenge > MAX_MODELL_CHUNK_BYTES) {
                throw new IOException("Ungültige Text-Block-Länge: " + laenge);
            }
            byte[] daten = new byte[laenge];
            in.readFully(daten);
            return new TextBlock(name, index, daten);
        } catch (IllegalArgumentException e) {
            throw new IOException("Ungültiger Text-Block.", e);
        }
    }

    static byte[] textFertig(TextFertig fertig) throws IOException {
        Objects.requireNonNull(fertig, "fertig");
        return nachricht(TEXT_FERTIG, out -> out.writeUTF(fertig.paketHash()));
    }

    static TextFertig leseTextFertig(DataInput in) throws IOException {
        try {
            return new TextFertig(in.readUTF());
        } catch (IllegalArgumentException e) {
            throw new IOException("Ungültiges Text-Fertig.", e);
        }
    }

    static byte[] textQuittung(TextQuittung quittung) throws IOException {
        Objects.requireNonNull(quittung, "quittung");
        return nachricht(TEXT_QUITTUNG, out -> {
            out.writeUTF(quittung.paketHash());
            out.writeBoolean(quittung.erfolg());
        });
    }

    static TextQuittung leseTextQuittung(DataInput in) throws IOException {
        try {
            return new TextQuittung(in.readUTF(), in.readBoolean());
        } catch (IllegalArgumentException e) {
            throw new IOException("Ungültige Text-Quittung.", e);
        }
    }

    static byte[] updateStand(UpdateStand stand) throws IOException {
        Objects.requireNonNull(stand, "stand");
        return nachricht(UPDATE_STAND, out -> {
            out.writeUTF(stand.version());
            out.writeUTF(stand.variante());
        });
    }

    static UpdateStand leseUpdateStand(DataInput in) throws IOException {
        try {
            return new UpdateStand(in.readUTF(), in.readUTF());
        } catch (IllegalArgumentException e) {
            throw new IOException("Ungültiger Update-Stand.", e);
        }
    }

    static byte[] updateMeta(UpdateMeta meta) throws IOException {
        Objects.requireNonNull(meta, "meta");
        return nachricht(UPDATE_META, out -> {
            out.writeUTF(meta.variante());
            out.writeUTF(meta.version());
            out.writeLong(meta.groesse());
            out.writeUTF(meta.sha256());
            out.writeInt(meta.chunkGroesse());
            out.writeInt(meta.anzahl());
        });
    }

    static UpdateMeta leseUpdateMeta(DataInput in) throws IOException {
        try {
            return new UpdateMeta(in.readUTF(), in.readUTF(), in.readLong(),
                    in.readUTF(), in.readInt(), in.readInt());
        } catch (IllegalArgumentException e) {
            throw new IOException("Ungültige Update-Meta.", e);
        }
    }

    static byte[] updateBlock(UpdateBlock block) throws IOException {
        Objects.requireNonNull(block, "block");
        return nachricht(UPDATE_BLOCK, out -> {
            out.writeUTF(block.variante());
            out.writeInt(block.index());
            out.writeInt(block.daten().length);
            out.write(block.daten());
        });
    }

    static UpdateBlock leseUpdateBlock(DataInput in) throws IOException {
        try {
            String variante = in.readUTF();
            int index = in.readInt();
            int laenge = in.readInt();
            if (laenge < 1 || laenge > MAX_MODELL_CHUNK_BYTES) {
                throw new IOException("Ungültige Update-Block-Länge: " + laenge);
            }
            byte[] daten = new byte[laenge];
            in.readFully(daten);
            return new UpdateBlock(variante, index, daten);
        } catch (IllegalArgumentException e) {
            throw new IOException("Ungültiger Update-Block.", e);
        }
    }

    static byte[] updateFertig(UpdateFertig fertig) throws IOException {
        Objects.requireNonNull(fertig, "fertig");
        return nachricht(UPDATE_FERTIG, out -> {
            out.writeUTF(fertig.variante());
            out.writeUTF(fertig.version());
            out.writeUTF(fertig.sha256());
            out.writeLong(fertig.groesse());
        });
    }

    static UpdateFertig leseUpdateFertig(DataInput in) throws IOException {
        try {
            return new UpdateFertig(in.readUTF(), in.readUTF(), in.readUTF(), in.readLong());
        } catch (IllegalArgumentException e) {
            throw new IOException("Ungültiges Update-Fertig.", e);
        }
    }

    static byte[] updateQuittung(UpdateQuittung quittung) throws IOException {
        Objects.requireNonNull(quittung, "quittung");
        return nachricht(UPDATE_QUITTUNG, out -> {
            out.writeUTF(quittung.variante());
            out.writeUTF(quittung.version());
            out.writeBoolean(quittung.erfolg());
        });
    }

    static UpdateQuittung leseUpdateQuittung(DataInput in) throws IOException {
        try {
            return new UpdateQuittung(in.readUTF(), in.readUTF(), in.readBoolean());
        } catch (IllegalArgumentException e) {
            throw new IOException("Ungültige Update-Quittung.", e);
        }
    }
}
