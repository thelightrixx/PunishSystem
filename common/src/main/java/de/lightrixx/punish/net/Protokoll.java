package de.lightrixx.punish.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class Protokoll {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(Protokoll.class);

    public static final String KANAL = "punish:main";

    public static final int VERSION = 7;
    public static final int MIN_VERSION = 7;

    public static final int MAX_NUTZDATEN = 1024 * 1024;

    /**
     * Replay-Schutz (Protokoll v7): Jede Tunnel-Nachricht trägt eine pro Richtung
     * und Absender streng monotone Sequenznummer. Kodierung: ein Byte
     * {@code SEQ_RAHMEN}, dann 8-Byte-Sequenz (big-endian, ab 1), dann das
     * bisherige Paket (Typ + Nutzdaten). Empfänger akzeptieren nur eine Sequenz
     * größer als die letzte; ein Rückstand über {@code REPLAY_FENSTER} hinaus
     * verlangt Resync per neuem HELLO. HELLO setzt die Zähler beider Richtungen
     * zurück. Ab v7 sind alle Nicht-HELLO-Nachrichten verpflichtend sequenziert;
     * ältere Peers werden über HELLO abgewiesen (MIN_VERSION = 7).
     */
    public static final int SEQ_RAHMEN = 0x53;
    public static final long REPLAY_FENSTER = 1000L;

    public record SequenzRahmen(long sequenz, byte[] paket, boolean sequenziert) {
        public SequenzRahmen {
            paket = paket == null ? new byte[0] : paket.clone();
        }
    }

    public static byte[] rahmeMitSequenz(byte[] paket, long sequenz) throws IOException {
        if (paket == null || paket.length < 1) {
            throw new IOException("Leeres Paket");
        }
        if (paket.length > MAX_NUTZDATEN + 1) {
            throw new IOException("Paket zu groß: " + paket.length);
        }
        if (sequenz <= 0) {
            throw new IOException("Ungültige Sequenz: " + sequenz);
        }
        byte[] gerahmt = new byte[paket.length + 9];
        gerahmt[0] = (byte) SEQ_RAHMEN;
        for (int i = 0; i < 8; i++) {
            gerahmt[1 + i] = (byte) (sequenz >>> (56 - 8 * i));
        }
        System.arraycopy(paket, 0, gerahmt, 9, paket.length);
        // Nach dem Rahmen ist der Kanal (MAX_NUTZDATEN) die Obergrenze; Signatur kommt noch dazu.
        if (gerahmt.length > MAX_NUTZDATEN) {
            throw new IOException("Gerahmtes Paket übersteigt die Kanalgröße: " + gerahmt.length);
        }
        return gerahmt;
    }

    public static SequenzRahmen entrahmeSequenz(byte[] klar) throws IOException {
        if (klar == null || klar.length < 1) {
            throw new IOException("Leeres Paket");
        }
        if ((klar[0] & 0xFF) != SEQ_RAHMEN || klar.length < 10) {
            return new SequenzRahmen(-1L, klar, false);
        }
        long sequenz = 0L;
        for (int i = 0; i < 8; i++) {
            sequenz = (sequenz << 8) | (klar[1 + i] & 0xFF);
        }
        if (sequenz <= 0) {
            throw new IOException("Ungültige Sequenz im Rahmen.");
        }
        byte[] paket = new byte[klar.length - 9];
        System.arraycopy(klar, 9, paket, 0, paket.length);
        if (paket.length < 1) {
            throw new IOException("Leerer Sequenz-Rahmen.");
        }
        return new SequenzRahmen(sequenz, paket, true);
    }

    public static final class SequenzPruefer {
        private long letzte = -1L;

        public synchronized boolean akzeptiere(long sequenz) {
            if (sequenz <= 0) {
                return false;
            }
            if (letzte < 0 || sequenz > letzte) {
                letzte = sequenz;
                return true;
            }
            return false;
        }

        public synchronized boolean brauchtResync(long sequenz) {
            return letzte >= 0 && sequenz > 0 && sequenz + REPLAY_FENSTER < letzte;
        }

        public synchronized void zuruecksetzen() {
            letzte = -1L;
        }

        public synchronized long letzte() {
            return letzte;
        }
    }

    public static final int HELLO = 0x01;
    public static final int MUTE_SYNC = 0x02;
    public static final int MUTE_ENTFERNEN = 0x03;
    public static final int MUTE_SETZEN = 0x04;
    public static final int IP_MUTE_SYNC = 0x05;
    public static final int IP_MUTE_ENTFERNEN = 0x06;
    public static final int IP_MUTE_SETZEN = 0x07;
    public static final int BEFEHL_AUSFUEHREN = 0x09;
    /** Proxy -> Bridge: aktiver Bann gesetzt (Fallback-Deny auf Backend). */
    public static final int BAN_SETZEN = 0x0E;
    /** Proxy -> Bridge: Bann aufgehoben (UUID-Liste). */
    public static final int BAN_ENTFERNEN = 0x0F;
    /** Proxy -> Bridge: Voll-Sync aktiver Banns (gechunkt wie Mutes). */
    public static final int BAN_SYNC = 0x10;
    /** Proxy -> Bridge: aktiver Timeout gesetzt (Fallback-Deny auf Backend). */
    public static final int TIMEOUT_SETZEN = 0x11;
    /** Proxy -> Bridge: Timeout aufgehoben (UUID-Liste). */
    public static final int TIMEOUT_ENTFERNEN = 0x12;
    /** Proxy -> Bridge: Voll-Sync aktiver Timeouts (gechunkt wie Mutes). */
    public static final int TIMEOUT_SYNC = 0x13;
    /** Bridge -> Proxy: GUI-Daten anfordern (STATUS/HISTORY/REPORTS/KATALOG). */
    public static final int GUI_ANFRAGE = 0x0A;
    /** Proxy -> Bridge: Antwort auf GUI_ANFRAGE (gleiche requestId). */
    public static final int GUI_ANTWORT = 0x0B;
    /** Bridge -> Proxy: Platzhalter-Werte für PlaceholderAPI anfordern. */
    public static final int PLATZHALTER_ANFRAGE = 0x0C;
    /** Proxy -> Bridge: Antwort auf PLATZHALTER_ANFRAGE (gleiche requestId). */
    public static final int PLATZHALTER_ANTWORT = 0x0D;
    public static final int HELLO_ACK = 0x14;
    public static final int SYNC_BEGIN = 0x15;
    public static final int SYNC_END = 0x16;
    /** Bridge -> Proxy: Chatfilter-Treffer (Hash + gekürzter Klartext nur für opt-in Team-Info). */
    public static final int CHATFILTER_TREFFER = 0x17;
    /** Proxy -> Bridge: Konfiguration neu laden (Antwort auf /punish-reload). */
    public static final int CONFIG_RELOAD = 0x18;
    /** Proxy -> Bridge: Laufzeit-Schalter (Filter an/aus, KI an/aus). */
    public static final int CHATFILTER_STEUERUNG = 0x19;
    /** Proxy -> Bridge: Wort in die Wortliste aufnehmen (Absender-Server). */
    public static final int CHATFILTER_WORT = 0x1A;
    /** Bridge -> Proxy: lokaler Modell-Stand (Hashes, "" = fehlt). */
    public static final int MODELL_STAND = 0x1B;
    /** Proxy -> Bridge: Modell-Transfer ankündigen. */
    public static final int MODELL_META = 0x1C;
    /** Proxy -> Bridge: ein Modell-Block. */
    public static final int MODELL_BLOCK = 0x1D;
    /** Proxy -> Bridge: Modell-Transfer abgeschlossen (Bridge prüft + installiert). */
    public static final int MODELL_FERTIG = 0x1E;
    /** Bridge -> Proxy: Modell-Installation bestätigt/abgelehnt. */
    public static final int MODELL_QUITTUNG = 0x1F;
    /** Proxy -> Bridge: aktiver Freeze gesetzt (Bewegungs-/Aktions-Block auf Backend). */
    public static final int FREEZE_SETZEN = 0x20;
    /** Proxy -> Bridge: Freeze aufgehoben (UUID-Liste). */
    public static final int FREEZE_ENTFERNEN = 0x21;
    /** Proxy -> Bridge: Voll-Sync aktiver Freezes (gechunkt wie Mutes). */
    public static final int FREEZE_SYNC = 0x22;
    /** Bridge -> Proxy: Stand der synchronisierten Texte (Paket-Hash, "" = keine). */
    public static final int TEXT_STAND = 0x23;
    /** Proxy -> Bridge: Text-Transfer ankündigen. */
    public static final int TEXT_META = 0x24;
    /** Proxy -> Bridge: ein Text-Block. */
    public static final int TEXT_BLOCK = 0x25;
    /** Proxy -> Bridge: Text-Sync abgeschlossen (anwenden + neu laden). */
    public static final int TEXT_FERTIG = 0x26;
    /** Bridge -> Proxy: Text-Sync bestätigt/abgelehnt. */
    public static final int TEXT_QUITTUNG = 0x27;
    /** Bridge -> Proxy: Update-Stand (installierte Version + Variante, "" = unbekannt). */
    public static final int UPDATE_STAND = 0x28;
    /** Proxy -> Bridge: Update-Transfer ankündigen. */
    public static final int UPDATE_META = 0x29;
    /** Proxy -> Bridge: ein Update-Block. */
    public static final int UPDATE_BLOCK = 0x2A;
    /** Proxy -> Bridge: Update-Transfer abgeschlossen (Bridge prüft + legt in update/ ab). */
    public static final int UPDATE_FERTIG = 0x2B;
    /** Bridge -> Proxy: Update-Installation bestätigt/abgelehnt. */
    public static final int UPDATE_QUITTUNG = 0x2C;

    public static final int MAX_CHATFILTER_WORT = 50;

    public static final long MAX_MODELL_DATEI_BYTES = 400L * 1024L * 1024L;
    /** Obergrenze pro Modell-Block (passt in MAX_NUTZDATEN). */
    public static final int MAX_MODELL_CHUNK_BYTES = MAX_NUTZDATEN - 512;

    public static final String GUI_STATUS = "STATUS";
    public static final String GUI_HISTORY = "HISTORY";
    public static final String GUI_REPORTS = "REPORTS";
    public static final String GUI_REPORT_INFO = "REPORT_INFO";
    public static final String GUI_KATALOG = "KATALOG";

    public static final int MAX_GUI_ZEILEN = 54;
    public static final int MAX_GUI_TEXT = 1000;

    private Protokoll() {
    }

    public record MuteEintrag(UUID uuid, String name, String grund, String ausfuehrer,
                              long erstelltAm, long ablaufAm, String scope) {
        public MuteEintrag {
            Objects.requireNonNull(uuid, "uuid");
            if (name == null) {
                name = "Unbekannt";
            }
            if (grund == null) {
                grund = "";
            }
            if (ausfuehrer == null) {
                ausfuehrer = "Konsole";
            }
            if (scope == null || scope.isBlank()) {
                scope = "unbekannt";
            }
        }

        public boolean istAktiv(long jetzt) {
            return ablaufAm > jetzt;
        }

        public long verbleibend(long jetzt) {
            return Math.max(0L, ablaufAm - jetzt);
        }
    }

    public record IpMuteEintrag(String ip, String grund, String ausfuehrer,
                                long erstelltAm, long ablaufAm, String scope) {
        public IpMuteEintrag {
            if (ip == null || ip.isBlank()) {
                throw new IllegalArgumentException("ip");
            }
            ip = ip.trim();
            if (grund == null) {
                grund = "";
            }
            if (ausfuehrer == null) {
                ausfuehrer = "Konsole";
            }
            if (scope == null || scope.isBlank()) {
                scope = "unbekannt";
            }
        }

        public boolean istAktiv(long jetzt) {
            return ablaufAm > jetzt;
        }

        public long verbleibend(long jetzt) {
            return Math.max(0L, ablaufAm - jetzt);
        }
    }

    public static void schreibeUuid(DataOutput out, UUID uuid) throws IOException {
        if (uuid == null) {
            out.writeLong(0L);
            out.writeLong(0L);
        } else {
            out.writeLong(uuid.getMostSignificantBits());
            out.writeLong(uuid.getLeastSignificantBits());
        }
    }

    public static UUID leseUuid(DataInput in) throws IOException {
        long msb = in.readLong();
        long lsb = in.readLong();
        if (msb == 0L && lsb == 0L) {
            return null;
        }
        return new UUID(msb, lsb);
    }

    public static void schreibeMute(DataOutput out, MuteEintrag eintrag) throws IOException {
        schreibeUuid(out, eintrag.uuid());
        out.writeUTF(eintrag.name());
        out.writeUTF(eintrag.grund());
        out.writeUTF(eintrag.ausfuehrer());
        out.writeLong(eintrag.erstelltAm());
        out.writeLong(eintrag.ablaufAm());
        out.writeUTF(eintrag.scope());
    }

    public static MuteEintrag leseMute(DataInput in) throws IOException {
        UUID uuid = leseUuid(in);
        if (uuid == null) {
            throw new IOException("MuteEintrag ohne UUID");
        }
        return new MuteEintrag(uuid, in.readUTF(), in.readUTF(), in.readUTF(),
                in.readLong(), in.readLong(), in.readUTF());
    }

    public static void schreibeIpMute(DataOutput out, IpMuteEintrag eintrag) throws IOException {
        out.writeUTF(eintrag.ip());
        out.writeUTF(eintrag.grund());
        out.writeUTF(eintrag.ausfuehrer());
        out.writeLong(eintrag.erstelltAm());
        out.writeLong(eintrag.ablaufAm());
        out.writeUTF(eintrag.scope());
    }

    public static IpMuteEintrag leseIpMute(DataInput in) throws IOException {
        String ip = in.readUTF();
        if (ip == null || ip.isBlank()) {
            throw new IOException("IpMuteEintrag ohne IP");
        }
        return new IpMuteEintrag(ip, in.readUTF(), in.readUTF(),
                in.readLong(), in.readLong(), in.readUTF());
    }

    public static byte[] nachricht(int typ, IoAktion aktion) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(typ);
            aktion.schreiben(out);
            out.flush();
        }
        byte[] ergebnis = bytes.toByteArray();
        if (ergebnis.length > MAX_NUTZDATEN + 1) {
            throw new IOException("Nachricht zu groß: " + ergebnis.length);
        }
        return ergebnis;
    }

    public static Paket paket(byte[] roh) throws IOException {
        if (roh == null || roh.length < 1) {
            throw new IOException("Leeres Paket");
        }
        if (roh.length > MAX_NUTZDATEN + 1) {
            throw new IOException("Paket zu groß: " + roh.length);
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(roh));
        int typ = in.readUnsignedByte();
        return new Paket(typ, in);
    }

    public record Paket(int typ, DataInputStream nutzdaten) {
    }

    @FunctionalInterface
    public interface IoAktion {
        void schreiben(DataOutputStream out) throws IOException;
    }

    public static byte[] hello(String serverName) throws IOException {
        return hello(serverName, "", "");
    }

    public static byte[] hello(String serverName, String pluginVersion) throws IOException {
        return hello(serverName, pluginVersion, "");
    }

    public static byte[] hello(String serverName, String pluginVersion, String variante) throws IOException {
        String version = kuerzeVersion(pluginVersion);
        String v = variante == null ? "" : variante.trim();
        if (v.length() > 32) {
            v = v.substring(0, 32);
        }
        String varianteFinal = v;
        String session = java.util.UUID.randomUUID().toString();
        return nachricht(HELLO, out -> {
            out.writeInt(VERSION);
            out.writeUTF(serverName == null ? "unbekannt" : serverName);
            out.writeUTF(version);
            out.writeUTF(varianteFinal);
            out.writeUTF(session);
        });
    }

    public record Hello(int protokollVersion, String serverName, String pluginVersion,
                        String variante, String sessionId) {
        public Hello(int protokollVersion, String serverName, String pluginVersion) {
            this(protokollVersion, serverName, pluginVersion, "", "");
        }

        public Hello(int protokollVersion, String serverName, String pluginVersion, String variante) {
            this(protokollVersion, serverName, pluginVersion, variante, "");
        }
    }

    public static Hello leseHello(DataInput in) throws IOException {
        int version = in.readInt();
        String name = "unbekannt";
        try {
            name = in.readUTF();
        } catch (Exception e) {
            LOG.debug("HELLO ohne Servernamen (Legacy-Format toleriert).", e);
        }
        String pluginVersion = "";
        try {
            pluginVersion = in.readUTF();
        } catch (Exception e) {
            LOG.debug("HELLO ohne Plugin-Version (Legacy-Format toleriert).", e);
        }
        String variante = "";
        try {
            variante = in.readUTF();
        } catch (Exception e) {
            LOG.debug("HELLO ohne Variante (Legacy-Format toleriert).", e);
        }
        String sessionId = "";
        try {
            sessionId = in.readUTF();
        } catch (Exception e) {
            LOG.debug("HELLO ohne Session-ID (Legacy-Format toleriert).", e);
        }
        return new Hello(version, name == null ? "unbekannt" : name,
                pluginVersion == null ? "" : pluginVersion.trim(),
                variante == null ? "" : variante.trim(),
                sessionId == null ? "" : sessionId.trim());
    }

    private static String kuerzeVersion(String version) {
        if (version == null) {
            return "";
        }
        String getrimmt = version.trim().replace('\r', ' ').replace('\n', ' ').trim();
        return getrimmt.length() > 64 ? getrimmt.substring(0, 64) : getrimmt;
    }

    public static byte[] muteSync(List<MuteEintrag> eintraege) throws IOException {
        List<MuteEintrag> liste = eintraege == null ? List.of() : eintraege;
        return nachricht(MUTE_SYNC, out -> {
            out.writeInt(liste.size());
            for (MuteEintrag eintrag : liste) {
                schreibeMute(out, eintrag);
            }
        });
    }

    public static List<MuteEintrag> leseMuteSync(DataInput in) throws IOException {
        int anzahl = in.readInt();
        if (anzahl < 0 || anzahl > 100000) {
            throw new IOException("Ungültige Mute-Anzahl: " + anzahl);
        }
        List<MuteEintrag> liste = new ArrayList<>(Math.min(anzahl, 1024));
        for (int i = 0; i < anzahl; i++) {
            liste.add(leseMute(in));
        }
        return liste;
    }

    public static byte[] ipMuteSync(List<IpMuteEintrag> eintraege) throws IOException {
        List<IpMuteEintrag> liste = eintraege == null ? List.of() : eintraege;
        return nachricht(IP_MUTE_SYNC, out -> {
            out.writeInt(liste.size());
            for (IpMuteEintrag eintrag : liste) {
                schreibeIpMute(out, eintrag);
            }
        });
    }

    public static List<IpMuteEintrag> leseIpMuteSync(DataInput in) throws IOException {
        int anzahl = in.readInt();
        if (anzahl < 0 || anzahl > 100000) {
            throw new IOException("Ungültige IP-Mute-Anzahl: " + anzahl);
        }
        List<IpMuteEintrag> liste = new ArrayList<>(Math.min(anzahl, 1024));
        for (int i = 0; i < anzahl; i++) {
            liste.add(leseIpMute(in));
        }
        return liste;
    }

    public static List<String> leseIpListe(DataInput in) throws IOException {
        int anzahl = in.readInt();
        if (anzahl < 0 || anzahl > 100000) {
            throw new IOException("Ungültige IP-Anzahl: " + anzahl);
        }
        List<String> liste = new ArrayList<>(Math.min(anzahl, 1024));
        for (int i = 0; i < anzahl; i++) {
            String ip = in.readUTF();
            if (ip != null && !ip.isBlank()) {
                liste.add(ip);
            }
        }
        return liste;
    }

    /**
     * Kompakter Bann für den Backend-Fallback-Cache. {@code ablaufAm} =
     * {@link Long#MAX_VALUE} bedeutet permanent.
     */
    public record BanEintrag(UUID uuid, String name, String grund, String ausfuehrer,
                             long erstelltAm, long ablaufAm, String scope) {
        public BanEintrag {
            Objects.requireNonNull(uuid, "uuid");
            if (name == null) {
                name = "Unbekannt";
            }
            if (grund == null) {
                grund = "";
            }
            if (ausfuehrer == null) {
                ausfuehrer = "Konsole";
            }
            if (scope == null || scope.isBlank()) {
                scope = "unbekannt";
            }
        }

        public boolean istPermanent() {
            return ablaufAm == Long.MAX_VALUE;
        }

        public boolean istAktiv(long jetzt) {
            return istPermanent() || ablaufAm > jetzt;
        }
    }

    /**
     * Kompakter Timeout für den Backend-Fallback-Cache (nie permanent).
     */
    public record TimeoutEintrag(UUID uuid, String name, String grund, String ausfuehrer,
                                 long erstelltAm, long ablaufAm, String scope) {
        public TimeoutEintrag {
            Objects.requireNonNull(uuid, "uuid");
            if (name == null) {
                name = "Unbekannt";
            }
            if (grund == null) {
                grund = "";
            }
            if (ausfuehrer == null) {
                ausfuehrer = "Konsole";
            }
            if (scope == null || scope.isBlank()) {
                scope = "unbekannt";
            }
        }

        public boolean istAktiv(long jetzt) {
            return ablaufAm > jetzt;
        }
    }

    public static void schreibeBan(DataOutput out, BanEintrag eintrag) throws IOException {
        schreibeUuid(out, eintrag.uuid());
        out.writeUTF(eintrag.name());
        out.writeUTF(eintrag.grund());
        out.writeUTF(eintrag.ausfuehrer());
        out.writeLong(eintrag.erstelltAm());
        out.writeLong(eintrag.ablaufAm());
        out.writeUTF(eintrag.scope());
    }

    public static BanEintrag leseBan(DataInput in) throws IOException {
        UUID uuid = leseUuid(in);
        if (uuid == null) {
            throw new IOException("BanEintrag ohne UUID");
        }
        return new BanEintrag(uuid, in.readUTF(), in.readUTF(), in.readUTF(),
                in.readLong(), in.readLong(), in.readUTF());
    }

    public static void schreibeTimeout(DataOutput out, TimeoutEintrag eintrag) throws IOException {
        schreibeUuid(out, eintrag.uuid());
        out.writeUTF(eintrag.name());
        out.writeUTF(eintrag.grund());
        out.writeUTF(eintrag.ausfuehrer());
        out.writeLong(eintrag.erstelltAm());
        out.writeLong(eintrag.ablaufAm());
        out.writeUTF(eintrag.scope());
    }

    public static TimeoutEintrag leseTimeout(DataInput in) throws IOException {
        UUID uuid = leseUuid(in);
        if (uuid == null) {
            throw new IOException("TimeoutEintrag ohne UUID");
        }
        return new TimeoutEintrag(uuid, in.readUTF(), in.readUTF(), in.readUTF(),
                in.readLong(), in.readLong(), in.readUTF());
    }

    /**
     * Kompakter Freeze für den Backend-Cache. {@code ablaufAm} =
     * {@link Long#MAX_VALUE} bedeutet permanent (bis /unfreeze).
     */
    public record FreezeEintrag(UUID uuid, String name, String grund, String ausfuehrer,
                                long erstelltAm, long ablaufAm, String scope) {
        public FreezeEintrag {
            Objects.requireNonNull(uuid, "uuid");
            if (name == null) {
                name = "Unbekannt";
            }
            if (grund == null) {
                grund = "";
            }
            if (ausfuehrer == null) {
                ausfuehrer = "Konsole";
            }
            if (scope == null || scope.isBlank()) {
                scope = "unbekannt";
            }
        }

        public boolean istPermanent() {
            return ablaufAm == Long.MAX_VALUE;
        }

        public boolean istAktiv(long jetzt) {
            return istPermanent() || ablaufAm > jetzt;
        }
    }

    public static void schreibeFreeze(DataOutput out, FreezeEintrag eintrag) throws IOException {
        schreibeUuid(out, eintrag.uuid());
        out.writeUTF(eintrag.name());
        out.writeUTF(eintrag.grund());
        out.writeUTF(eintrag.ausfuehrer());
        out.writeLong(eintrag.erstelltAm());
        out.writeLong(eintrag.ablaufAm());
        out.writeUTF(eintrag.scope());
    }

    public static FreezeEintrag leseFreeze(DataInput in) throws IOException {
        UUID uuid = leseUuid(in);
        if (uuid == null) {
            throw new IOException("FreezeEintrag ohne UUID");
        }
        return new FreezeEintrag(uuid, in.readUTF(), in.readUTF(), in.readUTF(),
                in.readLong(), in.readLong(), in.readUTF());
    }

    public static byte[] banSync(List<BanEintrag> eintraege) throws IOException {
        List<BanEintrag> liste = eintraege == null ? List.of() : eintraege;
        return nachricht(BAN_SYNC, out -> {
            out.writeInt(liste.size());
            for (BanEintrag eintrag : liste) {
                schreibeBan(out, eintrag);
            }
        });
    }

    public static List<BanEintrag> leseBanSync(DataInput in) throws IOException {
        int anzahl = in.readInt();
        if (anzahl < 0 || anzahl > 100000) {
            throw new IOException("Ungültige Ban-Anzahl: " + anzahl);
        }
        List<BanEintrag> liste = new ArrayList<>(Math.min(anzahl, 1024));
        for (int i = 0; i < anzahl; i++) {
            liste.add(leseBan(in));
        }
        return liste;
    }

    public static byte[] timeoutSync(List<TimeoutEintrag> eintraege) throws IOException {
        List<TimeoutEintrag> liste = eintraege == null ? List.of() : eintraege;
        return nachricht(TIMEOUT_SYNC, out -> {
            out.writeInt(liste.size());
            for (TimeoutEintrag eintrag : liste) {
                schreibeTimeout(out, eintrag);
            }
        });
    }

    public static List<TimeoutEintrag> leseTimeoutSync(DataInput in) throws IOException {
        int anzahl = in.readInt();
        if (anzahl < 0 || anzahl > 100000) {
            throw new IOException("Ungültige Timeout-Anzahl: " + anzahl);
        }
        List<TimeoutEintrag> liste = new ArrayList<>(Math.min(anzahl, 1024));
        for (int i = 0; i < anzahl; i++) {
            liste.add(leseTimeout(in));
        }
        return liste;
    }

    public static byte[] freezeSync(List<FreezeEintrag> eintraege) throws IOException {
        List<FreezeEintrag> liste = eintraege == null ? List.of() : eintraege;
        return nachricht(FREEZE_SYNC, out -> {
            out.writeInt(liste.size());
            for (FreezeEintrag eintrag : liste) {
                schreibeFreeze(out, eintrag);
            }
        });
    }

    public static List<FreezeEintrag> leseFreezeSync(DataInput in) throws IOException {
        int anzahl = in.readInt();
        if (anzahl < 0 || anzahl > 100000) {
            throw new IOException("Ungültige Freeze-Anzahl: " + anzahl);
        }
        List<FreezeEintrag> liste = new ArrayList<>(Math.min(anzahl, 1024));
        for (int i = 0; i < anzahl; i++) {
            liste.add(leseFreeze(in));
        }
        return liste;
    }

    public record BefehlsAuftrag(UUID alsUuid, String zeile) {
    }

    public static byte[] befehlAusfuehren(UUID alsUuid, String zeile) throws IOException {
        return nachricht(BEFEHL_AUSFUEHREN, out -> {
            schreibeUuid(out, alsUuid);
            out.writeUTF(zeile == null ? "" : zeile);
        });
    }

    public static BefehlsAuftrag leseBefehl(DataInput in) throws IOException {
        return new BefehlsAuftrag(leseUuid(in), in.readUTF());
    }

    public record ChatfilterTreffer(UUID uuid, String name, String kanal, String quelle,
                                    double bewertung, String textHash, String text, long zeitpunkt) {
        public ChatfilterTreffer {
            Objects.requireNonNull(uuid, "uuid");
            if (name == null || name.isBlank()) {
                name = "Unbekannt";
            }
            if (kanal == null || kanal.isBlank()) {
                kanal = "CHAT";
            }
            if (quelle == null || quelle.isBlank()) {
                quelle = "KI";
            }
            if (Double.isNaN(bewertung) || bewertung < 0.0d || bewertung > 1.0d) {
                throw new IllegalArgumentException("bewertung");
            }
            if (textHash == null || textHash.isBlank()) {
                throw new IllegalArgumentException("textHash");
            }
            if (text == null) {
                text = "";
            }
        }
    }

    public static byte[] chatfilterTreffer(ChatfilterTreffer treffer) throws IOException {
        Objects.requireNonNull(treffer, "treffer");
        return nachricht(CHATFILTER_TREFFER, out -> {
            schreibeUuid(out, treffer.uuid());
            out.writeUTF(treffer.name());
            out.writeUTF(treffer.kanal());
            out.writeUTF(treffer.quelle());
            out.writeDouble(treffer.bewertung());
            out.writeUTF(treffer.textHash());
            out.writeUTF(treffer.text());
            out.writeLong(treffer.zeitpunkt());
        });
    }

    public static ChatfilterTreffer leseChatfilterTreffer(DataInput in) throws IOException {
        UUID uuid = leseUuid(in);
        if (uuid == null) {
            throw new IOException("ChatfilterTreffer ohne UUID");
        }
        try {
            return new ChatfilterTreffer(uuid, in.readUTF(), in.readUTF(), in.readUTF(),
                    in.readDouble(), in.readUTF(), in.readUTF(), in.readLong());
        } catch (IllegalArgumentException e) {
            throw new IOException("Ungültiger ChatfilterTreffer: " + e.getMessage(), e);
        }
    }

    public record ChatfilterSteuerung(int filterModus, int kiModus) {
        public ChatfilterSteuerung {
            if ((filterModus < 0 || filterModus > 2) || (kiModus < 0 || kiModus > 2)) {
                throw new IllegalArgumentException("modus");
            }
        }

        public Boolean filterAn() {
            return schalter(filterModus);
        }

        public Boolean kiAn() {
            return schalter(kiModus);
        }

        private static Boolean schalter(int modus) {
            return switch (modus) {
                case 1 -> Boolean.FALSE;
                case 2 -> Boolean.TRUE;
                default -> null;
            };
        }
    }

    public static byte[] chatfilterSteuerung(Boolean filterAn, Boolean kiAn) throws IOException {
        return nachricht(CHATFILTER_STEUERUNG, out -> {
            out.writeByte(modus(filterAn));
            out.writeByte(modus(kiAn));
        });
    }

    private static int modus(Boolean an) {
        if (an == null) {
            return 0;
        }
        return an ? 2 : 1;
    }

    public static ChatfilterSteuerung leseChatfilterSteuerung(DataInput in) throws IOException {
        try {
            return new ChatfilterSteuerung(in.readUnsignedByte(), in.readUnsignedByte());
        } catch (IllegalArgumentException e) {
            throw new IOException("Ungültige ChatfilterSteuerung: " + e.getMessage(), e);
        }
    }

    public static byte[] chatfilterWort(String wort) throws IOException {
        String sauber = wort == null ? "" : wort.trim();
        return nachricht(CHATFILTER_WORT, out -> out.writeUTF(sauber));
    }

    public static String leseChatfilterWort(DataInput in) throws IOException {
        String wort = in.readUTF();
        if (wort == null || wort.isBlank() || wort.length() > MAX_CHATFILTER_WORT) {
            throw new IOException("Ungültiges Chatfilter-Wort.");
        }
        if (wort.contains("\n") || wort.contains("\r")) {
            throw new IOException("Ungültiges Chatfilter-Wort.");
        }
        return wort.trim();
    }

    public static byte[] configReload() throws IOException {
        return nachricht(CONFIG_RELOAD, out -> {
        });
    }

    public record ModellStand(String modellHash, String vocabHash) {
        public ModellStand {
            modellHash = normiereHash(modellHash);
            vocabHash = normiereHash(vocabHash);
        }
    }

    public record ModellMeta(String dateiname, long groesse, String sha256,
                             int chunkGroesse, int anzahl) {
        public ModellMeta {
            dateiname = pruefeModellDateiname(dateiname);
            if (groesse < 1 || groesse > MAX_MODELL_DATEI_BYTES) {
                throw new IllegalArgumentException("groesse");
            }
            sha256 = fordereHash(sha256);
            if (chunkGroesse < 4096 || chunkGroesse > MAX_MODELL_CHUNK_BYTES) {
                throw new IllegalArgumentException("chunkGroesse");
            }
            long erwartet = (groesse + chunkGroesse - 1) / chunkGroesse;
            if (anzahl < 1 || anzahl > 100000 || anzahl != erwartet) {
                throw new IllegalArgumentException("anzahl");
            }
        }
    }

    public record ModellBlock(String dateiname, int index, byte[] daten) {
        public ModellBlock {
            dateiname = pruefeModellDateiname(dateiname);
            if (index < 0 || index > 100000) {
                throw new IllegalArgumentException("index");
            }
            if (daten == null || daten.length < 1 || daten.length > MAX_MODELL_CHUNK_BYTES) {
                throw new IllegalArgumentException("daten");
            }
            daten = daten.clone();
        }
    }

    public record ModellFertig(String dateiname, String sha256, long groesse) {
        public ModellFertig {
            dateiname = pruefeModellDateiname(dateiname);
            sha256 = fordereHash(sha256);
            if (groesse < 1 || groesse > MAX_MODELL_DATEI_BYTES) {
                throw new IllegalArgumentException("groesse");
            }
        }
    }

    public record ModellQuittung(String dateiname, boolean erfolg) {
        public ModellQuittung {
            dateiname = pruefeModellDateiname(dateiname);
        }
    }

    static String normiereHash(String hash) {
        if (hash == null || hash.isBlank()) {
            return "";
        }
        String sauber = hash.trim().toLowerCase(java.util.Locale.ROOT);
        if (sauber.length() != 64 || !sauber.chars().allMatch(c ->
                (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
            throw new IllegalArgumentException("hash");
        }
        return sauber;
    }

    static String fordereHash(String hash) {
        String sauber = normiereHash(hash);
        if (sauber.isEmpty()) {
            throw new IllegalArgumentException("hash");
        }
        return sauber;
    }

    static String pruefeModellDateiname(String name) {
        if (name == null || name.isBlank() || name.length() > 64) {
            throw new IllegalArgumentException("dateiname");
        }
        String sauber = name.trim();
        if (sauber.contains("/") || sauber.contains("\\") || sauber.contains("..")) {
            throw new IllegalArgumentException("dateiname");
        }
        boolean zeichenOk = sauber.chars().allMatch(c ->
                (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                        || c == '.' || c == '_' || c == '-');
        String klein = sauber.toLowerCase(java.util.Locale.ROOT);
        if (!zeichenOk || !(klein.endsWith(".onnx") || klein.endsWith(".txt"))) {
            throw new IllegalArgumentException("dateiname");
        }
        return sauber;
    }

    public static byte[] modellStand(String modellHash, String vocabHash) throws IOException {
        var stand = new ModellStand(modellHash, vocabHash);
        return nachricht(MODELL_STAND, out -> {
            out.writeUTF(stand.modellHash());
            out.writeUTF(stand.vocabHash());
        });
    }

    public static ModellStand leseModellStand(DataInput in) throws IOException {
        try {
            return new ModellStand(in.readUTF(), in.readUTF());
        } catch (IllegalArgumentException e) {
            throw new IOException("Ungültiger Modell-Stand.", e);
        }
    }

    public static byte[] modellMeta(ModellMeta meta) throws IOException {
        Objects.requireNonNull(meta, "meta");
        return nachricht(MODELL_META, out -> {
            out.writeUTF(meta.dateiname());
            out.writeLong(meta.groesse());
            out.writeUTF(meta.sha256());
            out.writeInt(meta.chunkGroesse());
            out.writeInt(meta.anzahl());
        });
    }

    public static ModellMeta leseModellMeta(DataInput in) throws IOException {
        try {
            return new ModellMeta(in.readUTF(), in.readLong(), in.readUTF(),
                    in.readInt(), in.readInt());
        } catch (IllegalArgumentException e) {
            throw new IOException("Ungültige Modell-Meta.", e);
        }
    }

    public static byte[] modellBlock(ModellBlock block) throws IOException {
        Objects.requireNonNull(block, "block");
        return nachricht(MODELL_BLOCK, out -> {
            out.writeUTF(block.dateiname());
            out.writeInt(block.index());
            out.writeInt(block.daten().length);
            out.write(block.daten());
        });
    }

    public static ModellBlock leseModellBlock(DataInput in) throws IOException {
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

    public static byte[] modellFertig(ModellFertig fertig) throws IOException {
        Objects.requireNonNull(fertig, "fertig");
        return nachricht(MODELL_FERTIG, out -> {
            out.writeUTF(fertig.dateiname());
            out.writeUTF(fertig.sha256());
            out.writeLong(fertig.groesse());
        });
    }

    public static ModellFertig leseModellFertig(DataInput in) throws IOException {
        try {
            return new ModellFertig(in.readUTF(), in.readUTF(), in.readLong());
        } catch (IllegalArgumentException e) {
            throw new IOException("Ungültiges Modell-Fertig.", e);
        }
    }

    public static byte[] modellQuittung(ModellQuittung quittung) throws IOException {
        Objects.requireNonNull(quittung, "quittung");
        return nachricht(MODELL_QUITTUNG, out -> {
            out.writeUTF(quittung.dateiname());
            out.writeBoolean(quittung.erfolg());
        });
    }

    public static ModellQuittung leseModellQuittung(DataInput in) throws IOException {
        try {
            return new ModellQuittung(in.readUTF(), in.readBoolean());
        } catch (IllegalArgumentException e) {
            throw new IOException("Ungültige Modell-Quittung.", e);
        }
    }

    public static final int MAX_TEXT_DATEI_BYTES = 256 * 1024;

    public static final java.util.Set<String> TEXT_DATEINAMEN = java.util.Set.of(
            "general.yml", "messages.yml", "screens.yml", "broadcasts.yml", "gui.yml", "wortliste.txt");

    public record TextStand(String paketHash) {
        public TextStand {
            paketHash = normiereHash(paketHash);
        }
    }

    public record TextMeta(String dateiname, long groesse, String sha256,
                             int chunkGroesse, int anzahl) {
        public TextMeta {
            dateiname = pruefeTextDateiname(dateiname);
            if (groesse < 1 || groesse > MAX_TEXT_DATEI_BYTES) {
                throw new IllegalArgumentException("groesse");
            }
            sha256 = fordereHash(sha256);
            if (chunkGroesse < 1024 || chunkGroesse > MAX_MODELL_CHUNK_BYTES) {
                throw new IllegalArgumentException("chunkGroesse");
            }
            long erwartet = (groesse + chunkGroesse - 1) / chunkGroesse;
            if (anzahl < 1 || anzahl > 1000 || anzahl != erwartet) {
                throw new IllegalArgumentException("anzahl");
            }
        }
    }

    public record TextBlock(String dateiname, int index, byte[] daten) {
        public TextBlock {
            dateiname = pruefeTextDateiname(dateiname);
            if (index < 0 || index > 1000) {
                throw new IllegalArgumentException("index");
            }
            if (daten == null || daten.length < 1 || daten.length > MAX_MODELL_CHUNK_BYTES) {
                throw new IllegalArgumentException("daten");
            }
            daten = daten.clone();
        }
    }

    public record TextFertig(String paketHash) {
        public TextFertig {
            paketHash = fordereHash(paketHash);
        }
    }

    public record TextQuittung(String paketHash, boolean erfolg) {
        public TextQuittung {
            paketHash = normiereHash(paketHash);
        }
    }

    public static String textPaketHash(java.util.Map<String, byte[]> dateien) {
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

    static String pruefeTextDateiname(String name) {
        if (name == null || name.isBlank() || name.length() > 64) {
            throw new IllegalArgumentException("dateiname");
        }
        String sauber = name.trim();
        if (!TEXT_DATEINAMEN.contains(sauber)) {
            throw new IllegalArgumentException("dateiname");
        }
        return sauber;
    }

    public static byte[] textStand(String paketHash) throws IOException {
        var stand = new TextStand(paketHash);
        return nachricht(TEXT_STAND, out -> out.writeUTF(stand.paketHash()));
    }

    public static TextStand leseTextStand(DataInput in) throws IOException {
        try {
            return new TextStand(in.readUTF());
        } catch (IllegalArgumentException e) {
            throw new IOException("Ungültiger Text-Stand.", e);
        }
    }

    public static byte[] textMeta(TextMeta meta) throws IOException {
        Objects.requireNonNull(meta, "meta");
        return nachricht(TEXT_META, out -> {
            out.writeUTF(meta.dateiname());
            out.writeLong(meta.groesse());
            out.writeUTF(meta.sha256());
            out.writeInt(meta.chunkGroesse());
            out.writeInt(meta.anzahl());
        });
    }

    public static TextMeta leseTextMeta(DataInput in) throws IOException {
        try {
            return new TextMeta(in.readUTF(), in.readLong(), in.readUTF(),
                    in.readInt(), in.readInt());
        } catch (IllegalArgumentException e) {
            throw new IOException("Ungültige Text-Meta.", e);
        }
    }

    public static byte[] textBlock(TextBlock block) throws IOException {
        Objects.requireNonNull(block, "block");
        return nachricht(TEXT_BLOCK, out -> {
            out.writeUTF(block.dateiname());
            out.writeInt(block.index());
            out.writeInt(block.daten().length);
            out.write(block.daten());
        });
    }

    public static TextBlock leseTextBlock(DataInput in) throws IOException {
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

    public static byte[] textFertig(TextFertig fertig) throws IOException {
        Objects.requireNonNull(fertig, "fertig");
        return nachricht(TEXT_FERTIG, out -> out.writeUTF(fertig.paketHash()));
    }

    public static TextFertig leseTextFertig(DataInput in) throws IOException {
        try {
            return new TextFertig(in.readUTF());
        } catch (IllegalArgumentException e) {
            throw new IOException("Ungültiges Text-Fertig.", e);
        }
    }

    public static byte[] textQuittung(TextQuittung quittung) throws IOException {
        Objects.requireNonNull(quittung, "quittung");
        return nachricht(TEXT_QUITTUNG, out -> {
            out.writeUTF(quittung.paketHash());
            out.writeBoolean(quittung.erfolg());
        });
    }

    public static TextQuittung leseTextQuittung(DataInput in) throws IOException {
        try {
            return new TextQuittung(in.readUTF(), in.readBoolean());
        } catch (IllegalArgumentException e) {
            throw new IOException("Ungültige Text-Quittung.", e);
        }
    }

    public static final long MAX_UPDATE_DATEI_BYTES = 64L * 1024L * 1024L;

    public static final java.util.Set<String> UPDATE_VARIANTEN = java.util.Set.of("bridge", "bridge-1.21");

    public record UpdateStand(String version, String variante) {
        public UpdateStand {
            version = version == null ? "" : version.trim();
            if (version.length() > 64) {
                version = version.substring(0, 64);
            }
            variante = variante == null ? "" : variante.trim().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public record UpdateMeta(String variante, String version, long groesse, String sha256,
                             int chunkGroesse, int anzahl) {
        public UpdateMeta {
            variante = normiereUpdateVariante(variante);
            version = normiereUpdateVersion(version);
            if (groesse < 1 || groesse > MAX_UPDATE_DATEI_BYTES) {
                throw new IllegalArgumentException("groesse");
            }
            sha256 = fordereHash(sha256);
            if (chunkGroesse < 4096 || chunkGroesse > MAX_MODELL_CHUNK_BYTES) {
                throw new IllegalArgumentException("chunkGroesse");
            }
            long erwartet = (groesse + chunkGroesse - 1) / chunkGroesse;
            if (anzahl < 1 || anzahl > 100000 || anzahl != erwartet) {
                throw new IllegalArgumentException("anzahl");
            }
        }
    }

    public record UpdateBlock(String variante, int index, byte[] daten) {
        public UpdateBlock {
            variante = normiereUpdateVariante(variante);
            if (index < 0 || index > 100000) {
                throw new IllegalArgumentException("index");
            }
            if (daten == null || daten.length < 1 || daten.length > MAX_MODELL_CHUNK_BYTES) {
                throw new IllegalArgumentException("daten");
            }
            daten = daten.clone();
        }
    }

    public record UpdateFertig(String variante, String version, String sha256, long groesse) {
        public UpdateFertig {
            variante = normiereUpdateVariante(variante);
            version = normiereUpdateVersion(version);
            sha256 = fordereHash(sha256);
            if (groesse < 1 || groesse > MAX_UPDATE_DATEI_BYTES) {
                throw new IllegalArgumentException("groesse");
            }
        }
    }

    public record UpdateQuittung(String variante, String version, boolean erfolg) {
        public UpdateQuittung {
            variante = variante == null ? "" : variante.trim().toLowerCase(java.util.Locale.ROOT);
            version = version == null ? "" : version.trim();
        }
    }

    static String normiereUpdateVariante(String variante) {
        if (variante == null || variante.isBlank()) {
            throw new IllegalArgumentException("variante");
        }
        String sauber = variante.trim().toLowerCase(java.util.Locale.ROOT);
        if (!UPDATE_VARIANTEN.contains(sauber)) {
            throw new IllegalArgumentException("variante");
        }
        return sauber;
    }

    static String normiereUpdateVersion(String version) {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version");
        }
        String sauber = version.trim();
        if (sauber.length() > 64) {
            throw new IllegalArgumentException("version");
        }
        for (int i = 0; i < sauber.length(); i++) {
            char c = sauber.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_' || c == '+';
            if (!ok) {
                throw new IllegalArgumentException("version");
            }
        }
        return sauber;
    }

    public static byte[] updateStand(UpdateStand stand) throws IOException {
        Objects.requireNonNull(stand, "stand");
        return nachricht(UPDATE_STAND, out -> {
            out.writeUTF(stand.version());
            out.writeUTF(stand.variante());
        });
    }

    public static UpdateStand leseUpdateStand(DataInput in) throws IOException {
        try {
            return new UpdateStand(in.readUTF(), in.readUTF());
        } catch (IllegalArgumentException e) {
            throw new IOException("Ungültiger Update-Stand.", e);
        }
    }

    public static byte[] updateMeta(UpdateMeta meta) throws IOException {
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

    public static UpdateMeta leseUpdateMeta(DataInput in) throws IOException {
        try {
            return new UpdateMeta(in.readUTF(), in.readUTF(), in.readLong(),
                    in.readUTF(), in.readInt(), in.readInt());
        } catch (IllegalArgumentException e) {
            throw new IOException("Ungültige Update-Meta.", e);
        }
    }

    public static byte[] updateBlock(UpdateBlock block) throws IOException {
        Objects.requireNonNull(block, "block");
        return nachricht(UPDATE_BLOCK, out -> {
            out.writeUTF(block.variante());
            out.writeInt(block.index());
            out.writeInt(block.daten().length);
            out.write(block.daten());
        });
    }

    public static UpdateBlock leseUpdateBlock(DataInput in) throws IOException {
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

    public static byte[] updateFertig(UpdateFertig fertig) throws IOException {
        Objects.requireNonNull(fertig, "fertig");
        return nachricht(UPDATE_FERTIG, out -> {
            out.writeUTF(fertig.variante());
            out.writeUTF(fertig.version());
            out.writeUTF(fertig.sha256());
            out.writeLong(fertig.groesse());
        });
    }

    public static UpdateFertig leseUpdateFertig(DataInput in) throws IOException {
        try {
            return new UpdateFertig(in.readUTF(), in.readUTF(), in.readUTF(), in.readLong());
        } catch (IllegalArgumentException e) {
            throw new IOException("Ungültiges Update-Fertig.", e);
        }
    }

    public static byte[] updateQuittung(UpdateQuittung quittung) throws IOException {
        Objects.requireNonNull(quittung, "quittung");
        return nachricht(UPDATE_QUITTUNG, out -> {
            out.writeUTF(quittung.variante());
            out.writeUTF(quittung.version());
            out.writeBoolean(quittung.erfolg());
        });
    }

    public static UpdateQuittung leseUpdateQuittung(DataInput in) throws IOException {
        try {
            return new UpdateQuittung(in.readUTF(), in.readUTF(), in.readBoolean());
        } catch (IllegalArgumentException e) {
            throw new IOException("Ungültige Update-Quittung.", e);
        }
    }

    public static List<UUID> leseUuidListe(DataInput in) throws IOException {
        int anzahl = in.readInt();
        if (anzahl < 0 || anzahl > 100000) {
            throw new IOException("Ungültige UUID-Anzahl: " + anzahl);
        }
        List<UUID> liste = new ArrayList<>(Math.min(anzahl, 1024));
        for (int i = 0; i < anzahl; i++) {
            UUID uuid = leseUuid(in);
            if (uuid != null) {
                liste.add(uuid);
            }
        }
        return liste;
    }

    public record GuiAnfrage(long requestId, UUID absenderUuid, String typ,
                             String ziel, String filter, int seite, String extra) {
        public GuiAnfrage {
            if (requestId <= 0) {
                throw new IllegalArgumentException("requestId");
            }
            if (typ == null || typ.isBlank()) {
                typ = GUI_STATUS;
            }
            typ = typ.trim().toUpperCase(java.util.Locale.ROOT);
            if (ziel == null) {
                ziel = "";
            }
            if (filter == null) {
                filter = "";
            }
            if (seite < 1) {
                seite = 1;
            }
            if (extra == null) {
                extra = "";
            }
        }
    }

    public record GuiZeile(String typ, String titel, String details, String aktion, String meta) {
        public GuiZeile {
            if (typ == null || typ.isBlank()) {
                typ = "INFO";
            }
            if (titel == null) {
                titel = "";
            }
            if (details == null) {
                details = "";
            }
            if (aktion == null) {
                aktion = "";
            }
            if (meta == null) {
                meta = "";
            }
        }
    }

    public record GuiAntwort(long requestId, boolean erfolg, String nachricht,
                             int seite, int seiten, int gesamt,
                             List<GuiZeile> eintraege) {
        public GuiAntwort {
            if (eintraege == null) {
                eintraege = List.of();
            } else {
                eintraege = List.copyOf(eintraege);
            }
            if (nachricht == null) {
                nachricht = "";
            }
            if (seite < 1) {
                seite = 1;
            }
            if (seiten < 1) {
                seiten = 1;
            }
            if (gesamt < 0) {
                gesamt = eintraege.size();
            }
        }
    }

    static String kuerze(String text, int max) {
        if (text == null) {
            return "";
        }
        if (max <= 0) {
            return "";
        }
        if (text.length() <= max) {
            return text;
        }
        int ende = max;
        if (Character.isHighSurrogate(text.charAt(ende - 1))
                && Character.isLowSurrogate(text.charAt(ende))) {
            ende--;
        }
        return text.substring(0, ende);
    }

    public static byte[] guiAnfrage(GuiAnfrage anfrage) throws IOException {
        Objects.requireNonNull(anfrage, "anfrage");
        return nachricht(GUI_ANFRAGE, out -> {
            out.writeLong(anfrage.requestId());
            schreibeUuid(out, anfrage.absenderUuid());
            out.writeUTF(kuerze(anfrage.typ(), 32));
            out.writeUTF(kuerze(anfrage.ziel(), 64));
            out.writeUTF(kuerze(anfrage.filter(), 64));
            out.writeInt(anfrage.seite());
            out.writeUTF(kuerze(anfrage.extra(), MAX_GUI_TEXT));
        });
    }

    public static GuiAnfrage leseGuiAnfrage(DataInput in) throws IOException {
        long requestId = in.readLong();
        if (requestId <= 0) {
            throw new IOException("Ungültige requestId: " + requestId);
        }
        UUID absender = leseUuid(in);
        String typ = in.readUTF();
        String ziel = in.readUTF();
        String filter = in.readUTF();
        int seite = in.readInt();
        String extra = in.readUTF();
        return new GuiAnfrage(requestId, absender, typ, ziel, filter, seite, extra);
    }

    public static void schreibeGuiZeile(DataOutput out, GuiZeile zeile) throws IOException {
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(zeile, "zeile");
        out.writeUTF(kuerze(zeile.typ(), 32));
        out.writeUTF(kuerze(zeile.titel(), 200));
        out.writeUTF(kuerze(zeile.details(), MAX_GUI_TEXT));
        out.writeUTF(kuerze(zeile.aktion(), 300));
        out.writeUTF(kuerze(zeile.meta(), 200));
    }

    public static GuiZeile leseGuiZeile(DataInput in) throws IOException {
        String typ = in.readUTF();
        String titel = in.readUTF();
        String details = in.readUTF();
        String aktion = in.readUTF();
        String meta = in.readUTF();
        return new GuiZeile(typ, titel, details, aktion, meta);
    }

    public static byte[] guiAntwort(GuiAntwort antwort) throws IOException {
        Objects.requireNonNull(antwort, "antwort");
        List<GuiZeile> eintraege = antwort.eintraege();
        if (eintraege.size() > MAX_GUI_ZEILEN) {
            eintraege = eintraege.subList(0, MAX_GUI_ZEILEN);
        }
        List<GuiZeile> versand = eintraege;
        return nachricht(GUI_ANTWORT, out -> {
            out.writeLong(antwort.requestId());
            out.writeBoolean(antwort.erfolg());
            out.writeUTF(kuerze(antwort.nachricht(), MAX_GUI_TEXT));
            out.writeInt(antwort.seite());
            out.writeInt(antwort.seiten());
            out.writeInt(antwort.gesamt());
            out.writeInt(versand.size());
            for (GuiZeile zeile : versand) {
                schreibeGuiZeile(out, zeile);
            }
        });
    }

    public static GuiAntwort leseGuiAntwort(DataInput in) throws IOException {
        long requestId = in.readLong();
        if (requestId <= 0) {
            throw new IOException("Ungültige requestId: " + requestId);
        }
        boolean erfolg = in.readBoolean();
        String nachricht = in.readUTF();
        int seite = in.readInt();
        int seiten = in.readInt();
        int gesamt = in.readInt();
        int anzahl = in.readInt();
        if (anzahl < 0 || anzahl > MAX_GUI_ZEILEN) {
            throw new IOException("Ungültige GUI-Anzahl: " + anzahl);
        }
        List<GuiZeile> liste = new ArrayList<>(anzahl);
        for (int i = 0; i < anzahl; i++) {
            liste.add(leseGuiZeile(in));
        }
        return new GuiAntwort(requestId, erfolg, nachricht, seite, seiten, gesamt, liste);
    }

    public record PlatzhalterAnfrage(long requestId, UUID absenderUuid, String ziel) {
        public PlatzhalterAnfrage {
            if (requestId <= 0) {
                throw new IllegalArgumentException("requestId");
            }
            if (ziel == null) {
                ziel = "";
            }
        }
    }

    /**
     * Kompakte Straf-Werte für PlaceholderAPI.
     * Ablauf-Semantik: {@code -1} = inaktiv, {@code Long.MAX_VALUE} = permanent,
     * sonst Epoch-Millis des Ablaufs.
     * Detail-Semantik (v2): Grund/Von sind gekürzt ({@value #MAX_PLATZHALTER_TEXT}
     * Zeichen, {@code ""} = kein aktiver Eintrag), Seit ist Epoch-Millis des
     * aktiven Eintrags ({@code 0} = kein aktiver Eintrag).
     */
    public record PlatzhalterAntwort(long requestId, boolean erfolg,
                                     boolean muted, long muteAblauf,
                                     boolean banned, long banAblauf,
                                     boolean timeouted, long timeoutAblauf,
                                     boolean frozen, long freezeAblauf,
                                     int verstossAnzahl, int verstossPunkte,
                                     int warns, int reportsGegen, int reportsOffen,
                                     String muteGrund, String muteVon, long muteSeit,
                                     String banGrund, String banVon, long banSeit,
                                     String timeoutGrund, String timeoutVon, long timeoutSeit,
                                     String freezeGrund, String freezeVon, long freezeSeit,
                                     int notizen, int historyAnzahl,
                                     String warnsLetzterGrund, String verstossStufe) {
        public static final long INAKTIV = -1L;
        public static final int MAX_PLATZHALTER_TEXT = 128;

        public PlatzhalterAntwort {
            if (verstossAnzahl < 0) {
                verstossAnzahl = 0;
            }
            if (verstossPunkte < 0) {
                verstossPunkte = 0;
            }
            if (warns < 0) {
                warns = 0;
            }
            if (reportsGegen < 0) {
                reportsGegen = 0;
            }
            if (reportsOffen < 0) {
                reportsOffen = 0;
            }
            if (muteGrund == null) {
                muteGrund = "";
            }
            if (muteVon == null) {
                muteVon = "";
            }
            if (muteSeit < 0) {
                muteSeit = 0;
            }
            if (banGrund == null) {
                banGrund = "";
            }
            if (banVon == null) {
                banVon = "";
            }
            if (banSeit < 0) {
                banSeit = 0;
            }
            if (timeoutGrund == null) {
                timeoutGrund = "";
            }
            if (timeoutVon == null) {
                timeoutVon = "";
            }
            if (timeoutSeit < 0) {
                timeoutSeit = 0;
            }
            if (freezeGrund == null) {
                freezeGrund = "";
            }
            if (freezeVon == null) {
                freezeVon = "";
            }
            if (freezeSeit < 0) {
                freezeSeit = 0;
            }
            if (notizen < 0) {
                notizen = 0;
            }
            if (historyAnzahl < 0) {
                historyAnzahl = 0;
            }
            if (warnsLetzterGrund == null) {
                warnsLetzterGrund = "";
            }
            if (verstossStufe == null) {
                verstossStufe = "";
            }
        }

        public static PlatzhalterAntwort fehler(long requestId) {
            return new PlatzhalterAntwort(requestId, false,
                    false, INAKTIV, false, INAKTIV, false, INAKTIV, false, INAKTIV, 0, 0, 0, 0, 0,
                    "", "", 0L, "", "", 0L, "", "", 0L, "", "", 0L, 0, 0, "", "");
        }

        public static PlatzhalterAntwort leer(long requestId) {
            return new PlatzhalterAntwort(requestId, true,
                    false, INAKTIV, false, INAKTIV, false, INAKTIV, false, INAKTIV, 0, 0, 0, 0, 0,
                    "", "", 0L, "", "", 0L, "", "", 0L, "", "", 0L, 0, 0, "", "");
        }
    }

    public static byte[] platzhalterAnfrage(PlatzhalterAnfrage anfrage) throws IOException {
        Objects.requireNonNull(anfrage, "anfrage");
        return nachricht(PLATZHALTER_ANFRAGE, out -> {
            out.writeLong(anfrage.requestId());
            schreibeUuid(out, anfrage.absenderUuid());
            out.writeUTF(kuerze(anfrage.ziel(), 64));
        });
    }

    public static PlatzhalterAnfrage lesePlatzhalterAnfrage(DataInput in) throws IOException {
        long requestId = in.readLong();
        if (requestId <= 0) {
            throw new IOException("Ungültige requestId: " + requestId);
        }
        UUID absender = leseUuid(in);
        String ziel = in.readUTF();
        return new PlatzhalterAnfrage(requestId, absender, ziel);
    }

    public static byte[] platzhalterAntwort(PlatzhalterAntwort antwort) throws IOException {
        Objects.requireNonNull(antwort, "antwort");
        return nachricht(PLATZHALTER_ANTWORT, out -> {
            out.writeLong(antwort.requestId());
            out.writeBoolean(antwort.erfolg());
            out.writeBoolean(antwort.muted());
            out.writeLong(antwort.muteAblauf());
            out.writeBoolean(antwort.banned());
            out.writeLong(antwort.banAblauf());
            out.writeBoolean(antwort.timeouted());
            out.writeLong(antwort.timeoutAblauf());
            out.writeBoolean(antwort.frozen());
            out.writeLong(antwort.freezeAblauf());
            out.writeInt(antwort.verstossAnzahl());
            out.writeInt(antwort.verstossPunkte());
            out.writeInt(antwort.warns());
            out.writeInt(antwort.reportsGegen());
            out.writeInt(antwort.reportsOffen());
            // Detail-Felder v2 (neu): alte Bridges lesen sie nicht, sie ignorieren
            // die nachgestellten Bytes.
            int maxText = PlatzhalterAntwort.MAX_PLATZHALTER_TEXT;
            out.writeUTF(kuerze(antwort.muteGrund(), maxText));
            out.writeUTF(kuerze(antwort.muteVon(), maxText));
            out.writeLong(antwort.muteSeit());
            out.writeUTF(kuerze(antwort.banGrund(), maxText));
            out.writeUTF(kuerze(antwort.banVon(), maxText));
            out.writeLong(antwort.banSeit());
            out.writeUTF(kuerze(antwort.timeoutGrund(), maxText));
            out.writeUTF(kuerze(antwort.timeoutVon(), maxText));
            out.writeLong(antwort.timeoutSeit());
            out.writeUTF(kuerze(antwort.freezeGrund(), maxText));
            out.writeUTF(kuerze(antwort.freezeVon(), maxText));
            out.writeLong(antwort.freezeSeit());
            out.writeInt(antwort.notizen());
            out.writeInt(antwort.historyAnzahl());
            out.writeUTF(kuerze(antwort.warnsLetzterGrund(), maxText));
            out.writeUTF(kuerze(antwort.verstossStufe(), maxText));
        });
    }

    public static PlatzhalterAntwort lesePlatzhalterAntwort(DataInput in) throws IOException {
        long requestId = in.readLong();
        if (requestId <= 0) {
            throw new IOException("Ungültige requestId: " + requestId);
        }
        boolean erfolg = in.readBoolean();
        boolean muted = in.readBoolean();
        long muteAblauf = in.readLong();
        boolean banned = in.readBoolean();
        long banAblauf = in.readLong();
        boolean timeouted = in.readBoolean();
        long timeoutAblauf = in.readLong();
        // Nachgestellte Freeze-Felder (neu): alte Proxys senden sie nicht.
        // ByteArrayInputStream meldet die exakte Restlänge: 5 Ints = 20 Bytes
        // ohne Freeze, 29 Bytes mit Freeze. Eine abgeschnittene Sektion
        // (Versions-Schieflage) degradiert auf v1-Werte (frozen=false,
        // fehlende Zähler 0), statt den ganzen Frame zu verwerfen.
        // Verhalten gepinnt in AuditCodecFuzzTest.cS81.
        boolean frozen = false;
        long freezeAblauf = PlatzhalterAntwort.INAKTIV;
        boolean unvollstaendig = false;
        try {
            if (in instanceof DataInputStream dis && dis.available() > 20) {
                frozen = dis.readBoolean();
                freezeAblauf = dis.readLong();
            }
        } catch (IOException e) {
            frozen = false;
            freezeAblauf = PlatzhalterAntwort.INAKTIV;
            unvollstaendig = true;
        }
        int[] zaehler = new int[5];
        for (int i = 0; i < zaehler.length; i++) {
            try {
                zaehler[i] = in.readInt();
            } catch (IOException e) {
                unvollstaendig = true;
                zaehler[i] = 0;
                break;
            }
        }
        if (unvollstaendig) {
            frozen = false;
            freezeAblauf = PlatzhalterAntwort.INAKTIV;
        }
        int verstossAnzahl = zaehler[0];
        int verstossPunkte = zaehler[1];
        int warns = zaehler[2];
        int reportsGegen = zaehler[3];
        int reportsOffen = zaehler[4];
        // Nachgestellte Detail-Felder v2 (neu): alte Proxys senden sie nicht.
        // Unvollständige Pakete ergeben leere Details, keine Fehler.
        try {
            String muteGrund = in.readUTF();
            String muteVon = in.readUTF();
            long muteSeit = in.readLong();
            String banGrund = in.readUTF();
            String banVon = in.readUTF();
            long banSeit = in.readLong();
            String timeoutGrund = in.readUTF();
            String timeoutVon = in.readUTF();
            long timeoutSeit = in.readLong();
            String freezeGrund = in.readUTF();
            String freezeVon = in.readUTF();
            long freezeSeit = in.readLong();
            int notizen = in.readInt();
            int historyAnzahl = in.readInt();
            String warnsLetzterGrund = in.readUTF();
            String verstossStufe = in.readUTF();
            return new PlatzhalterAntwort(requestId, erfolg, muted, muteAblauf, banned, banAblauf,
                    timeouted, timeoutAblauf, frozen, freezeAblauf, verstossAnzahl, verstossPunkte,
                    warns, reportsGegen, reportsOffen,
                    muteGrund, muteVon, muteSeit, banGrund, banVon, banSeit,
                    timeoutGrund, timeoutVon, timeoutSeit, freezeGrund, freezeVon, freezeSeit,
                    notizen, historyAnzahl, warnsLetzterGrund, verstossStufe);
        } catch (IOException e) {
            return new PlatzhalterAntwort(requestId, erfolg, muted, muteAblauf, banned, banAblauf,
                    timeouted, timeoutAblauf, frozen, freezeAblauf, verstossAnzahl, verstossPunkte,
                    warns, reportsGegen, reportsOffen,
                    "", "", 0L, "", "", 0L, "", "", 0L, "", "", 0L, 0, 0, "", "");
        }
    }

    public static byte[] helloAck(int proxyVersion) throws IOException {
        return helloAck(proxyVersion, "");
    }

    public static byte[] helloAck(int proxyVersion, String kanonischerServerName) throws IOException {
        return helloAck(proxyVersion, kanonischerServerName, "");
    }

    public static byte[] helloAck(int proxyVersion, String kanonischerServerName, String proxyPluginVersion) throws IOException {
        String name = kanonischerServerName == null ? "" : kanonischerServerName.trim();
        if (name.length() > 32) {
            name = name.substring(0, 32);
        }
        String finalName = name;
        String finalVersion = kuerzeVersion(proxyPluginVersion);
        return nachricht(HELLO_ACK, out -> {
            out.writeInt(proxyVersion);
            out.writeUTF(finalName);
            out.writeUTF(finalVersion);
        });
    }

    public static int leseHelloAck(DataInput in) throws IOException {
        int version = in.readInt();
        try {
            in.readUTF();
        } catch (Exception e) {
            LOG.debug("HELLO_ACK ohne Servernamen (Legacy-Format toleriert).", e);
        }
        return version;
    }

    public record HelloAck(int proxyVersion, String kanonischerServerName, String proxyPluginVersion) {

        public HelloAck(int proxyVersion, String kanonischerServerName) {
            this(proxyVersion, kanonischerServerName, "");
        }
    }

    public static HelloAck leseHelloAckMitName(DataInput in) throws IOException {
        int version = in.readInt();
        String name = "";
        try {
            name = in.readUTF();
        } catch (Exception e) {
            LOG.debug("HELLO_ACK ohne Servernamen (Legacy-Format toleriert).", e);
        }
        String pluginVersion = "";
        try {
            pluginVersion = in.readUTF();
        } catch (Exception e) {
            LOG.debug("HELLO_ACK ohne Plugin-Version (Legacy-Format toleriert).", e);
        }
        return new HelloAck(version, name == null ? "" : name.trim(),
                pluginVersion == null ? "" : pluginVersion.trim());
    }

    public static byte[] syncBegin(long generation) throws IOException {
        if (generation <= 0) {
            throw new IOException("Ungültige Sync-Generation: " + generation);
        }
        return nachricht(SYNC_BEGIN, out -> out.writeLong(generation));
    }

    public static byte[] syncEnd(long generation) throws IOException {
        if (generation <= 0) {
            throw new IOException("Ungültige Sync-Generation: " + generation);
        }
        return nachricht(SYNC_END, out -> out.writeLong(generation));
    }

    public static long leseSyncGeneration(DataInput in) throws IOException {
        long generation = in.readLong();
        if (generation <= 0) {
            throw new IOException("Ungültige Sync-Generation: " + generation);
        }
        return generation;
    }
}
