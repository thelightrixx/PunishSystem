package de.lightrixx.punish.model;

import java.util.Objects;
import java.util.UUID;

/** Meldungskennzeichnung gehört zum Ereignis; stille Aktionen bleiben in der Akte sichtbar. */
public record HistoryEreignis(UUID uuid, HistoryEntry eintrag, boolean silent) {
    public HistoryEreignis {
        Objects.requireNonNull(uuid);
        Objects.requireNonNull(eintrag);
    }
}
