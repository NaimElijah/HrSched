package io.github.naimelijah.hrsched.workforce.adapter.in.web;

import java.util.UUID;

/**
 * @param id the qualification identity
 * @param name what it is called
 */
public record QualificationResponse(UUID id, String name) {}
