package de.lightrixx.punish.net;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static de.lightrixx.punish.net.Protokoll.GUI_ANFRAGE;
import static de.lightrixx.punish.net.Protokoll.GUI_ANTWORT;
import static de.lightrixx.punish.net.Protokoll.GuiAnfrage;
import static de.lightrixx.punish.net.Protokoll.GuiAntwort;
import static de.lightrixx.punish.net.Protokoll.GuiZeile;
import static de.lightrixx.punish.net.Protokoll.MAX_GUI_TEXT;
import static de.lightrixx.punish.net.Protokoll.MAX_GUI_ZEILEN;
import static de.lightrixx.punish.net.Protokoll.PLATZHALTER_ANFRAGE;
import static de.lightrixx.punish.net.Protokoll.PLATZHALTER_ANTWORT;
import static de.lightrixx.punish.net.Protokoll.PlatzhalterAnfrage;
import static de.lightrixx.punish.net.Protokoll.PlatzhalterAntwort;
import static de.lightrixx.punish.net.Protokoll.kuerze;
import static de.lightrixx.punish.net.Protokoll.leseUuid;
import static de.lightrixx.punish.net.Protokoll.nachricht;
import static de.lightrixx.punish.net.Protokoll.schreibeUuid;

final class GuiProtokoll {

    private GuiProtokoll() {
    }

    static byte[] guiAnfrage(GuiAnfrage anfrage) throws IOException {
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

    static GuiAnfrage leseGuiAnfrage(DataInput in) throws IOException {
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

    static void schreibeGuiZeile(DataOutput out, GuiZeile zeile) throws IOException {
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(zeile, "zeile");
        out.writeUTF(kuerze(zeile.typ(), 32));
        out.writeUTF(kuerze(zeile.titel(), 200));
        out.writeUTF(kuerze(zeile.details(), MAX_GUI_TEXT));
        out.writeUTF(kuerze(zeile.aktion(), 300));
        out.writeUTF(kuerze(zeile.meta(), 200));
    }

    static GuiZeile leseGuiZeile(DataInput in) throws IOException {
        String typ = in.readUTF();
        String titel = in.readUTF();
        String details = in.readUTF();
        String aktion = in.readUTF();
        String meta = in.readUTF();
        return new GuiZeile(typ, titel, details, aktion, meta);
    }

    static byte[] guiAntwort(GuiAntwort antwort) throws IOException {
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

    static GuiAntwort leseGuiAntwort(DataInput in) throws IOException {
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

    static byte[] platzhalterAnfrage(PlatzhalterAnfrage anfrage) throws IOException {
        Objects.requireNonNull(anfrage, "anfrage");
        return nachricht(PLATZHALTER_ANFRAGE, out -> {
            out.writeLong(anfrage.requestId());
            schreibeUuid(out, anfrage.absenderUuid());
            out.writeUTF(kuerze(anfrage.ziel(), 64));
        });
    }

    static PlatzhalterAnfrage lesePlatzhalterAnfrage(DataInput in) throws IOException {
        long requestId = in.readLong();
        if (requestId <= 0) {
            throw new IOException("Ungültige requestId: " + requestId);
        }
        UUID absender = leseUuid(in);
        String ziel = in.readUTF();
        return new PlatzhalterAnfrage(requestId, absender, ziel);
    }

    static byte[] platzhalterAntwort(PlatzhalterAntwort antwort) throws IOException {
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

    static PlatzhalterAntwort lesePlatzhalterAntwort(DataInput in) throws IOException {
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
}
