package io.github.naimelijah.hrsched.workforce.adapter.in.web;

import java.time.Instant;

/**
 * @param start when the span begins
 * @param end when the span ends
 */
public record PeriodRequest(Instant start, Instant end) {}
