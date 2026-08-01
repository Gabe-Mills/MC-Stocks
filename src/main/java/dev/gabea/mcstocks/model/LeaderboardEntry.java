package dev.gabea.mcstocks.model;

import java.util.UUID;

public record LeaderboardEntry(UUID playerId, double value) {
}
