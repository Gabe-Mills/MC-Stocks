package dev.gabea.mcstocks.model;

import java.util.UUID;

public record PlayerGainEntry(UUID playerId, double gainPercent, double startValue, double currentValue) {
}
