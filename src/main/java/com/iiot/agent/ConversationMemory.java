package com.iiot.agent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Bounded process-local memory. A lease serializes turns without holding a global model-call lock. */
final class ConversationMemory {
    private final Map<UUID, Session> sessions = new LinkedHashMap<>();
    private final Clock clock;
    private final int capacity;
    private final Duration ttl;

    ConversationMemory() {
        this(Clock.systemUTC(), 128, Duration.ofMinutes(30));
    }

    ConversationMemory(Clock clock, int capacity, Duration ttl) {
        this.clock = clock;
        this.capacity = capacity;
        this.ttl = ttl;
    }

    synchronized Session acquire(UUID id) {
        var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        String owner = authentication == null ? "internal" : authentication.getName();
        Instant now = clock.instant();
        sessions.values().removeIf(s -> !s.lock.isLocked() && !s.updated.plus(ttl).isAfter(now));
        Session session;
        if (id == null) {
            if (sessions.size() >= capacity) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Conversation capacity reached; retry later");
            }
            session = new Session(UUID.randomUUID(), now, owner);
            sessions.put(session.id, session);
        }
        else {
            session = sessions.get(id);
            if (session == null || !session.owner.equals(owner)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation unknown or expired; omit conversationId to start again");
            }
        }
        if (!session.lock.tryLock()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A turn is already running for this conversation");
        }
        return session;
    }

    synchronized void release(Session session, boolean completed) {
        session.updated = clock.instant();
        if (!completed && session.turns.isEmpty()) {
            sessions.remove(session.id);
        }
        session.lock.unlock();
    }

    static final class Session {
        final UUID id;
        final String owner;
        final ReentrantLock lock = new ReentrantLock();
        final List<Turn> turns = new ArrayList<>();
        Instant updated;

        Session(UUID id, Instant updated, String owner) {
            this.owner = owner;
            this.id = id;
            this.updated = updated;
        }

        void append(Turn turn) {
            turns.add(turn);
            while (turns.size() > 6 || turns.stream().mapToInt(Turn::characters).sum() > 24000) {
                turns.removeFirst();
            }
        }
    }

    record Turn(String question, String answer, String answeredAt, Map<String, String> machines) {
        int characters() {
            return question.length() + answer.length() + answeredAt.length()
                    + machines.entrySet().stream().mapToInt(e -> e.getKey().length() + e.getValue().length()).sum();
        }
    }
}
