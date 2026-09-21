package io.github.naimelijah.hrsched.access.adapter.in.web;

/**
 * The body of a request to disable an account or restore one.
 *
 * <p>A primitive {@code boolean}, so an absent field reads as {@code false} and disables. That is
 * the safe way round for this one field: the failure mode of a missing value is an account that
 * cannot act until somebody restores it, not one that can act when it should not.
 *
 * @param active whether the account may sign in and act
 */
public record SetActiveRequest(boolean active) {}
