import { api, path, type ActingIdentity } from './client';

/** A span of work that needs covering. Mirrors ShiftResponse. */
export interface Shift {
  readonly id: string;
  readonly start: string;
  readonly end: string;
  readonly requiredHeadcount: number;
  readonly requiredQualifications: readonly string[];
}

/** Who works which shift. Mirrors AssignmentResponse. */
export interface Assignment {
  readonly shift: string;
  readonly employee: string;
}

/** Mirrors RosterResponse. `validatedAgainstConstraintVersion` is null while a draft. */
export interface Roster {
  readonly id: string;
  readonly start: string;
  readonly end: string;
  readonly status: 'DRAFT' | 'PUBLISHED' | 'SUPERSEDED';
  readonly assignments: readonly Assignment[];
  readonly validatedAgainstConstraintVersion: number | null;
}

/**
 * Mirrors ConstraintSetResponse: a version, the rules by name, and their thresholds.
 *
 * The thresholds matter because revising replaces the whole set. A caller changing one rule has to
 * resend the others, which it can only do if it can read them back.
 */
export interface ConstraintSet {
  readonly version: number;
  readonly constraints: readonly string[];
  readonly minimumRestMinutes: number | null;
  readonly maximumDailyMinutes: number | null;
  readonly maximumWeeklyMinutes: number | null;
  readonly maximumConsecutiveDays: number | null;
}

/**
 * The rules an organisation applies, as whole minutes and counts.
 *
 * Every field is optional and there is no default for any of them, deliberately. HrSched hardcodes
 * no jurisdiction: whoever configures an organisation supplies the numbers.
 */
export interface ConstraintSetRequest {
  readonly minimumRestMinutes?: number | undefined;
  readonly maximumDailyMinutes?: number | undefined;
  readonly maximumWeeklyMinutes?: number | undefined;
  readonly maximumConsecutiveDays?: number | undefined;
}

export interface DefineShiftRequest {
  readonly start: string;
  readonly end: string;
  readonly requiredHeadcount: number;
  readonly requiredQualifications: readonly string[];
}

export const scheduling = {
  findShifts: (identity: ActingIdentity, from: string, to: string): Promise<Shift[]> =>
    api.get<Shift[]>(
      identity,
      `/api/shifts?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`,
    ),

  defineShift: (identity: ActingIdentity, shift: DefineShiftRequest): Promise<Shift> =>
    api.post<Shift>(identity, '/api/shifts', shift),

  findRosters: (identity: ActingIdentity): Promise<Roster[]> =>
    api.get<Roster[]>(identity, '/api/rosters'),

  generateRoster: (identity: ActingIdentity, from: string, to: string): Promise<Roster> =>
    api.post<Roster>(identity, '/api/rosters', { start: from, end: to }),

  /** Throws an ApiError with `isRuleBreach` and a populated `violations` when the rules refuse it. */
  publishRoster: (identity: ActingIdentity, id: string): Promise<Roster> =>
    api.post<Roster>(identity, path`/api/rosters/${id}/publication`),

  findRoster: (identity: ActingIdentity, id: string): Promise<Roster> =>
    api.get<Roster>(identity, path`/api/rosters/${id}`),

  /**
   * Puts somebody on a shift of a draft, and returns the roster as it now stands.
   *
   * Throws an ApiError with `isRefused` when the domain will not have that person there —
   * unqualified, unavailable, or a shift outside the roster's period. Nothing here checks first.
   */
  assign: (
    identity: ActingIdentity,
    roster: string,
    shift: string,
    employee: string,
  ): Promise<Roster> =>
    api.post<Roster>(identity, path`/api/rosters/${roster}/assignments`, { shift, employee }),

  /** Takes somebody off a shift of a draft, and returns the roster as it now stands. */
  unassign: (
    identity: ActingIdentity,
    roster: string,
    shift: string,
    employee: string,
  ): Promise<Roster> =>
    api.delete<Roster>(identity, path`/api/rosters/${roster}/assignments/${shift}/${employee}`),

  currentConstraints: (identity: ActingIdentity): Promise<ConstraintSet> =>
    api.get<ConstraintSet>(identity, '/api/constraints'),

  reviseConstraints: (
    identity: ActingIdentity,
    constraints: ConstraintSetRequest,
  ): Promise<ConstraintSet> => api.put<ConstraintSet>(identity, '/api/constraints', constraints),
};
