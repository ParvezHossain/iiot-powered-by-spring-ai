package com.iiot.agent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversationMemoryTests {
    @Test
    void expiresIdleSessionsAndBoundsCapacityWithoutEvictingActiveTurns() {
        var clock = mock(Clock.class);
        var now = Instant.parse("2026-09-08T00:00:00Z");
        when(clock.instant()).thenReturn(now);
        var memory = new ConversationMemory(clock, 1, Duration.ofMinutes(30));
        var session = memory.acquire(null);
        when(clock.instant()).thenReturn(now.plusSeconds(3600));
        assertThatThrownBy(() -> memory.acquire(null)).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(503));
        memory.release(session, true);
        when(clock.instant()).thenReturn(now.plusSeconds(5400));
        assertThatThrownBy(() -> memory.acquire(session.id)).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
        var replacement = memory.acquire(null);
        assertThat(replacement.id).isNotEqualTo(session.id);
        memory.release(replacement, false);
    }

    @Test
    void rejectsConcurrentTurnsAndReleasesFailedNewSessions() {
        var memory = new ConversationMemory(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), 1, Duration.ofMinutes(30));
        var session = memory.acquire(null);
        CompletableFuture.runAsync(() -> assertThatThrownBy(() -> memory.acquire(session.id))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(409))).join();
        memory.release(session, false);
        var replacement = memory.acquire(null);
        memory.release(replacement, false);
    }

    @Test
    void conversationBelongsToItsAuthenticatedCreator() {
        var context = org.springframework.security.core.context.SecurityContextHolder.getContext();
        try {
            context.setAuthentication(new org.springframework.security.authentication.TestingAuthenticationToken("alice", null));
            var memory = new ConversationMemory();
            var session = memory.acquire(null);
            memory.release(session, true);
            context.setAuthentication(new org.springframework.security.authentication.TestingAuthenticationToken("bob", null));
            assertThatThrownBy(() -> memory.acquire(session.id)).isInstanceOfSatisfying(ResponseStatusException.class,
                    e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
            context.setAuthentication(new org.springframework.security.authentication.TestingAuthenticationToken("alice", null));
            memory.release(memory.acquire(session.id), true);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void retainsOnlyRecentTurnsWithinCharacterBudget() {
        var memory = new ConversationMemory();
        var session = memory.acquire(null);
        for (int i = 0; i < 8; i++) {
            session.append(new ConversationMemory.Turn("question " + i, "answer", "now", Map.of()));
        }
        assertThat(session.turns).hasSize(6);
        assertThat(session.turns.getFirst().question()).isEqualTo("question 2");
        session.append(new ConversationMemory.Turn("large", "x".repeat(23980), "now", Map.of()));
        assertThat(session.turns).hasSize(1);
        memory.release(session, true);
    }
}
