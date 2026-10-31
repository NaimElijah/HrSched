/**
 * The objectives an organisation may weigh, and how a person reads each one (ADR-010).
 *
 * One table for every page that shows them, so the form that sets a weight and the draft check
 * showing what it measured cannot word the same objective differently. Each measure is a count, and
 * lower is better.
 *
 * Each row is the objective's name as the weights API spells it, its label, and what its count
 * measures.
 */
export const OBJECTIVE_FIELDS = [
  ['fairness', 'Fairness', 'the most unpopular shifts any one person carries'],
  ['preferences', 'Preferences', 'stated preferences the roster goes against'],
  ['disruption', 'Disruption', 'assignments changed from the published roster it replaces'],
] as const;

/** The name of one objective, as the weights API spells it. */
export type ObjectiveField = (typeof OBJECTIVE_FIELDS)[number][0];
