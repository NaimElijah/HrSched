package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import java.util.UUID;

/**
 * @param shift which shift is covered
 * @param employee who covers it
 */
public record AssignmentResponse(UUID shift, UUID employee) {}
