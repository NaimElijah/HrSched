import { api, type ActingIdentity } from './client';

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
  readonly status: 'DRAFT' | 'PUBLISHED' | 'SUPERSEDED';
  readonly assignments: readonly Assignment[];
  readonly validatedAgainstConstraintVersion: number | null;
}

/** Mirrors ConstraintSetResponse: a version, and the rules by name. */
export interface ConstraintSet {
  readonly version: number;
  readonly constraints: readonly string[];
}

/**
 * The rules an organisation applies, as whole minutes and counts.
 *
 * Every field is optional and there is no default for any of them, deliberately. HrSched hardcodes
 * no jurisdiction: whoever configures an organisation supplies the numbers.
 */
export interface ConstraintSetRequest {
  readonly minimumRestMinutes?: number;
  readonly maximumDailyMinutes?: number;
  readonly maximumWeeklyMinutes?: number;
  readonly maximumConsecutiveDays?: number;
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

  findRoster: (identity: ActingIdentity, id: string): Promise<Roster> =>
    api.get<Roster>(identity, `/api/rosters/${id}`),

  generateRoster: (identity: ActingIdentity, from: string, to: string): Promise<Roster> =>
    api.post<Roster>(identity, '/api/rosters', { start: from, end: to }),

  /** Throws an ApiError with `isRuleBreach` and a populated `violations` when the rules refuse it. */
  publishRoster: (identity: ActingIdentity, id: string): Promise<Roster> =>
    api.post<Roster>(identity, `/api/rosters/${id}/publication`),

  currentConstraints: (identity: ActingIdentity): Promise<ConstraintSet> =>
    api.get<ConstraintSet>(identity, '/api/constraints'),

  reviseConstraints: (
    identity: ActingIdentity,
    constraints: ConstraintSetRequest,
  ): Promise<ConstraintSet> => api.put<ConstraintSet>(identity, '/api/constraints', constraints),
};
