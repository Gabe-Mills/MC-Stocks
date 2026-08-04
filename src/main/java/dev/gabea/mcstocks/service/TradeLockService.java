package dev.gabea.mcstocks.service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public final class TradeLockService {
    private final Map<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();

    public ReentrantLock lockFor(UUID playerId) {
        return locks.computeIfAbsent(playerId, ignored -> new ReentrantLock());
    }

    public <T> T withLock(UUID playerId, LockedSupplier<T> work) throws Exception {
        ReentrantLock lock = lockFor(playerId);
        if (!lock.tryLock()) {
            throw new IllegalStateException("trade-busy");
        }
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }

    @FunctionalInterface
    public interface LockedSupplier<T> {
        T get() throws Exception;
    }
}
