package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import java.time.Instant;
import java.util.UUID;

/**
 * @param id the shift identity
 * @param start when it begins
 * @param end when it ends
 * @param requiredHeadcount how many people it needs
 */
public record ShiftResponse(UUID id, Instant start, Instant end, int requiredHeadcount) {}
