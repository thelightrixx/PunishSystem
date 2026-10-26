package de.lightrixx.punish.core;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public interface OfflineVerzeichnis {

    record ExternSpieler(
            UUID uuid,
            String name,
            boolean hatGespielt,
            boolean online,
            long zuletztGesehen,
            boolean onlineModus,
            boolean op
    ) {
        public ExternSpieler {
            Objects.requireNonNull(uuid, "Die UUID darf nicht null sein.");
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Der Name darf nicht leer sein.");
            }
        }

        public ExternSpieler(UUID uuid, String name, boolean hatGespielt, boolean online,
                             long zuletztGesehen, boolean onlineModus) {
            this(uuid, name, hatGespielt, online, zuletztGesehen, onlineModus, false);
        }
    }

    Optional<ExternSpieler> findeNachName(String name);

    Optional<ExternSpieler> findeNachUuid(UUID uuid);

    boolean istMainThread();
}
