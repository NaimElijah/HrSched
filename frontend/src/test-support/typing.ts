/**
 * Options for `userEvent.type` that press every key of a value without pausing between them.
 *
 * By default user-event waits a timer turn after each key, and React renders in every one of those
 * turns, so a long value typed into a form left a test close to Vitest's 5 s timeout, and the gate
 * failed whenever the machine was busy with something else (#286). Every key is still pressed and
 * every event still fires; only the pauses between them go. Raising the timeout would only have
 * moved the edge.
 */
export const WITHOUT_PAUSES = { delay: null } as const;
