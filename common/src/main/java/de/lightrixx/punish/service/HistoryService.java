package de.lightrixx.punish.service;

import de.lightrixx.punish.model.HistoryEntry;
import de.lightrixx.punish.model.HistoryType;
import de.lightrixx.punish.model.ServerScope;
import de.lightrixx.punish.storage.HistoryRepository;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class HistoryService {

    private final HistoryRepository historyRepository;

    public HistoryService(HistoryRepository historyRepository) {
        this.historyRepository = Objects.requireNonNull(historyRepository,
                "Das HistoryRepository darf nicht null sein.");
    }

    public void hinzufuegen(HistoryType typ, String ausfuehrer, UUID zielUuid,
                            String zielName, String grund, Long ablauf) {
        hinzufuegen(typ, ausfuehrer, zielUuid, zielName, grund, ablauf,
                ServerScope.unbekannt(), ServerScope.UNBEKANNT_WERT);
    }

    public void hinzufuegen(HistoryType typ, String ausfuehrer, UUID zielUuid,
                            String zielName, String grund, Long ablauf,
                            ServerScope scope, String herkunft) {
        Objects.requireNonNull(typ, "Der History-Typ darf nicht null sein.");
        Objects.requireNonNull(ausfuehrer, "Der Ausführende darf nicht null sein.");
        Objects.requireNonNull(zielUuid, "Die Ziel-UUID darf nicht null sein.");
        Objects.requireNonNull(zielName, "Der Zielname darf nicht null sein.");

        historyRepository.hinzufuegenNeu(typ, ausfuehrer, zielUuid, zielName, grund, ablauf,
                scope == null ? ServerScope.UNBEKANNT_WERT : scope.speicherwert(),
                herkunft);
    }

    public void hinzufuegen(HistoryType typ, String ausfuehrer, String ziel,
                            String grund, Long ablauf) {
        hinzufuegen(typ, ausfuehrer, ziel, grund, ablauf,
                ServerScope.unbekannt(), ServerScope.UNBEKANNT_WERT);
    }

    public void hinzufuegen(HistoryType typ, String ausfuehrer, String ziel,
                            String grund, Long ablauf, ServerScope scope, String herkunft) {
        Objects.requireNonNull(typ, "Der History-Typ darf nicht null sein.");
        Objects.requireNonNull(ausfuehrer, "Der Ausführende darf nicht null sein.");
        Objects.requireNonNull(ziel, "Das Ziel darf nicht null sein.");

        historyRepository.hinzufuegenNeu(typ, ausfuehrer, ziel, grund, ablauf,
                scope == null ? ServerScope.UNBEKANNT_WERT : scope.speicherwert(),
                herkunft);
    }

    public List<HistoryEntry> findeSpielerHistory(UUID zielUuid, int limit) {
        Objects.requireNonNull(zielUuid, "Die Ziel-UUID darf nicht null sein.");
        return historyRepository.finden(zielUuid, limit);
    }

    public List<HistoryEntry> findeServerHistory(int limit) {
        return historyRepository.findeAlle(limit);
    }
}
