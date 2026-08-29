package io.github.naimelijah.hrsched.workforce.adapter.in.web;

/**
 * Contracted hours as whole minutes, so the wire format carries no duration parsing.
 *
 * @param weeklyContractedMinutes contracted minutes per week
 */
public record ContractRequest(long weeklyContractedMinutes) {}
