package io.github.naimelijah.hrsched.access.adapter.in.web;

import java.util.UUID;

/**
 * @param id the organisation identity
 * @param name what it is called
 */
public record OrganisationResponse(UUID id, String name) {}
