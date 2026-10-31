/**
 * The thresholds a constraint set may carry, and how a person reads each one.
 *
 * One table for every page that shows the rules, so the form that sets a threshold and the panel
 * showing the threshold a roster passed cannot word the same rule differently.
 *
 * Each row is the threshold's name as the API spells it, then its label. `maximumWeeklyMinutes` is
 * labelled as a cap over the roster period, not per calendar week, because that is what the backend
 * enforces: it caps the total worked across the whole schedule being evaluated.
 */
export const RULE_FIELDS = [
  ['minimumRestMinutes', 'Minimum rest between assignments (minutes)'],
  ['maximumDailyMinutes', 'Maximum daily working time (minutes)'],
  ['maximumWeeklyMinutes', 'Maximum working time over the roster period (minutes)'],
  ['maximumConsecutiveDays', 'Maximum consecutive working days'],
] as const;

/** The name of one threshold a constraint set carries, as the API spells it. */
export type RuleField = (typeof RULE_FIELDS)[number][0];
