package de.lightrixx.punish.storage;

import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class StoreLock {

    public static final ReadWriteLock LOCK = new ReentrantReadWriteLock(true);

    private StoreLock() {
    }
}
