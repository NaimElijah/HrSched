package io.github.naimelijah.hrsched.architecture.fixtures.payroll.domain.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Breaks the domain rule with a library no deny-list named: a JSON annotation on a record. */
public record DomainWithAWireFormat(@JsonProperty("ref") String reference) {}
