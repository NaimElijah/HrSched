package io.github.naimelijah.hrsched.scheduling.domain.model;

/**
 * What makes one legal roster better than another (ADR-010).
 *
 * <p>Not a constraint. A constraint decides whether a roster may be published at all; an objective
 * only ranks rosters, and a roster scoring well on every one of them is still refused if it breaks
 * a rule. Each is measured as a whole number, and lower is better.
 */
public enum Objective {

  /**
   * Fairness: the largest number of unpopular shifts any one person is assigned on the roster.
   * Which shifts are unpopular is the Scheduler's mark, never something inferred.
   */
  FAIRNESS,

  /**
   * Preferences: each assignment inside a span its assignee would rather not work, and each span
   * somebody would like to work, overlapping the roster's period, on which they hold no assignment.
   */
  PREFERENCES,

  /**
   * Disruption: each assignment on the published rosters this one would supersede that it drops,
   * and each it adds on a shift within their periods. Nothing, when it supersedes nothing.
   */
  DISRUPTION
}
