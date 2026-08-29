package io.github.naimelijah.hrsched.workforce.adapter.in.web;

import io.github.naimelijah.hrsched.workforce.domain.model.PreferenceStrength;
import java.time.Instant;

/**
 * @param start when the span begins
 * @param end when the span ends
 * @param strength how the employee feels about it
 */
public record PreferenceRequest(Instant start, Instant end, PreferenceStrength strength) {}
