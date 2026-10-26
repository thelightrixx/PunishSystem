package de.lightrixx.punish.service;

import de.lightrixx.punish.core.MessageKeys;
import de.lightrixx.punish.model.NoteRecord;
import de.lightrixx.punish.storage.NoteRepository;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class NoteService {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(NoteService.class);

    private final NoteRepository noteRepository;

    private final BroadcastService broadcastService;

    public NoteService(NoteRepository noteRepository) {
        this(noteRepository, null);
    }

    public NoteService(NoteRepository noteRepository, BroadcastService broadcastService) {
        this.noteRepository = Objects.requireNonNull(noteRepository,
                "Das NoteRepository darf nicht null sein.");
        this.broadcastService = broadcastService;
    }

    public NoteRecord hinzufuegen(UUID zielUuid, String zielName, String text, String ausfuehrer) {
        return hinzufuegen(zielUuid, zielName, text, ausfuehrer, false);
    }

    public NoteRecord hinzufuegen(UUID zielUuid, String zielName, String text, String ausfuehrer,
                                  boolean silent) {
        Objects.requireNonNull(zielUuid, "Die Ziel-UUID darf nicht null sein.");
        Objects.requireNonNull(zielName, "Der Zielname darf nicht null sein.");
        Objects.requireNonNull(text, "Der Text darf nicht null sein.");
        Objects.requireNonNull(ausfuehrer, "Der Ausführende darf nicht null sein.");
        NoteRecord record = noteRepository.hinzufuegen(zielUuid, zielName, text, ausfuehrer);
        LOG.info("Notiz #{} für {} von {} eingetragen.", record.nummer(), zielName, ausfuehrer);
        broadcasten(MessageKeys.BROADCAST_NOTE_ADD, Map.of(
                "spieler", zielName,
                "ausfuehrer", ausfuehrer,
                "id", String.valueOf(record.nummer()),
                "text", text), silent);
        return record;
    }

    public Optional<NoteRecord> entfernen(UUID zielUuid, String zielName, int nummer, String ausfuehrer) {
        return entfernen(zielUuid, zielName, nummer, ausfuehrer, false);
    }

    public Optional<NoteRecord> entfernen(UUID zielUuid, String zielName, int nummer, String ausfuehrer,
                                          boolean silent) {
        Objects.requireNonNull(zielUuid, "Die Ziel-UUID darf nicht null sein.");
        Objects.requireNonNull(zielName, "Der Zielname darf nicht null sein.");
        Objects.requireNonNull(ausfuehrer, "Der Ausführende darf nicht null sein.");
        Optional<NoteRecord> entfernt = noteRepository.entfernen(zielUuid, nummer);
        entfernt.ifPresent(notiz -> {
            LOG.info("Notiz #{} von {} durch {} gelöscht.", nummer, zielName, ausfuehrer);
            broadcasten(MessageKeys.BROADCAST_NOTE_DELETE, Map.of(
                    "spieler", zielName,
                    "ausfuehrer", ausfuehrer,
                    "id", String.valueOf(nummer),
                    "text", notiz.text()), silent);
        });
        return entfernt;
    }

    public Optional<NoteRecord> bearbeiten(UUID zielUuid, String zielName, int nummer,
                                           String neuerText, String ausfuehrer) {
        return bearbeiten(zielUuid, zielName, nummer, neuerText, ausfuehrer, false);
    }

    public Optional<NoteRecord> bearbeiten(UUID zielUuid, String zielName, int nummer,
                                           String neuerText, String ausfuehrer, boolean silent) {
        Objects.requireNonNull(zielUuid, "Die Ziel-UUID darf nicht null sein.");
        Objects.requireNonNull(zielName, "Der Zielname darf nicht null sein.");
        Objects.requireNonNull(neuerText, "Der neue Text darf nicht null sein.");
        Objects.requireNonNull(ausfuehrer, "Der Ausführende darf nicht null sein.");
        Optional<NoteRecord> bearbeitet = noteRepository.bearbeiten(zielUuid, nummer, neuerText);
        bearbeitet.ifPresent(notiz -> {
            LOG.info("Notiz #{} von {} durch {} bearbeitet.", nummer, zielName, ausfuehrer);
            broadcasten(MessageKeys.BROADCAST_NOTE_EDIT, Map.of(
                    "spieler", zielName,
                    "ausfuehrer", ausfuehrer,
                    "id", String.valueOf(nummer),
                    "text", neuerText), silent);
        });
        return bearbeitet;
    }

    public List<NoteRecord> findeNotizen(UUID zielUuid) {
        Objects.requireNonNull(zielUuid, "Die Ziel-UUID darf nicht null sein.");
        return noteRepository.finden(zielUuid);
    }

    public int anzahl(UUID zielUuid) {
        Objects.requireNonNull(zielUuid, "Die Ziel-UUID darf nicht null sein.");
        return noteRepository.anzahl(zielUuid);
    }

    private void broadcasten(String typ, Map<String, String> platzhalter, boolean silent) {
        if (broadcastService == null) {
            return;
        }
        try {
            broadcastService.senden(typ, platzhalter, silent);
        } catch (Exception e) {
            LOG.warn("Notiz-Broadcast '{}' fehlgeschlagen (Notiz bleibt wirksam).", typ, e);
        }
    }
}
