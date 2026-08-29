package io.github.naimelijah.hrsched.workforce.adapter.in.web;

import java.util.List;
import java.util.UUID;

/**
 * @param id the employee identity
 * @param name their name
 * @param qualifications the qualifications they hold
 */
public record EmployeeResponse(UUID id, String name, List<UUID> qualifications) {}
