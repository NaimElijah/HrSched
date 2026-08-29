package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * @param start when the shift begins
 * @param end when the shift ends
 * @param requiredHeadcount how many people it needs
 * @param requiredQualifications what each of them must hold, may be null for none
 */
public record DefineShiftRequest(
    Instant start, Instant end, int requiredHeadcount, List<UUID> requiredQualifications) {}
