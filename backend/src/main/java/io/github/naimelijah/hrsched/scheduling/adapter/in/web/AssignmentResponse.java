package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import java.util.UUID;

/**
 * One person on one shift of a roster, as the API shows it.
 *
 * @param shift which shift is covered
 * @param employee who covers it
 */
public record AssignmentResponse(UUID shift, UUID employee) {}
