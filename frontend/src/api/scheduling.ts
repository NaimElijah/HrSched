import { api, path } from './client';

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

/** Work that names the caller, from a published roster. Mirrors RosteredShiftResponse. */
export interface RosteredShift {
  readonly shift: string;
  readonly start: string;
  readonly end: string;
  readonly roster: string;
}

/** Mirrors SwapRequestResponse. */
export interface SwapRequest {
  readonly id: string;
  readonly roster: string;
  readonly shift: string;
  readonly requester: string;
  readonly counterparty: string;
  readonly status: 'REQUESTED' | 'APPROVED' | 'REJECTED';
}

/** Mirrors RequestSwapRequest: whose shift, on which published roster, and who is asked to take it. */
export interface RequestSwap {
  readonly roster: string;
  readonly shift: string;
  readonly requester: string;
  readonly counterparty: string;
}

export interface DefineShiftRequest {
  readonly start: string;
  readonly end: string;
  /** An IANA zone name, such as `Europe/London`: the zone the shift's days are counted in. */
  readonly zone: string;
  readonly requiredHeadcount: number;
  readonly requiredQualifications: readonly string[];
}

export const scheduling = {
  findShifts: (from: string, to: string): Promise<Shift[]> =>
    api.get<Shift[]>(`/api/shifts?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`),

  defineShift: (shift: DefineShiftRequest): Promise<Shift> => api.post<Shift>('/api/shifts', shift),

  findRosters: (): Promise<Roster[]> => api.get<Roster[]>('/api/rosters'),

  generateRoster: (from: string, to: string): Promise<Roster> =>
    api.post<Roster>('/api/rosters', { start: from, end: to }),

  /** Throws an ApiError with `isRuleBreach` and a populated `violations` when the rules refuse it. */
  publishRoster: (id: string): Promise<Roster> =>
    api.post<Roster>(path`/api/rosters/${id}/publication`),

  findRoster: (id: string): Promise<Roster> => api.get<Roster>(path`/api/rosters/${id}`),

  /**
   * Puts somebody on a shift of a draft, and returns the roster as it now stands.
   *
   * Throws an ApiError with `isRefused` when the domain will not have that person there —
   * unqualified, unavailable, a shift outside the roster's period, or already working then, on this
   * draft or on a published roster beside it. Nothing here checks first.
   */
  assign: (roster: string, shift: string, employee: string): Promise<Roster> =>
    api.post<Roster>(path`/api/rosters/${roster}/assignments`, { shift, employee }),

  /** Takes somebody off a shift of a draft, and returns the roster as it now stands. */
  unassign: (roster: string, shift: string, employee: string): Promise<Roster> =>
    api.delete<Roster>(path`/api/rosters/${roster}/assignments/${shift}/${employee}`),

  /** The work naming the caller's own employee link, from published rosters only (F5). */
  findOwnSchedule: (from: string, to: string): Promise<RosteredShift[]> =>
    api.get<RosteredShift[]>(
      `/api/me/schedule?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`,
    ),

  /**
   * Asks for a shift to be handed to a colleague. A Scheduler decides; whether the colleague may take
   * it is checked when they do, not now (ADR-022).
   */
  requestSwap: (request: RequestSwap): Promise<SwapRequest> =>
    api.post<SwapRequest>('/api/swaps', request),

  /** Every swap an Admin or Scheduler holds; for anybody else, only the ones they are party to. */
  findSwapRequests: (): Promise<SwapRequest[]> => api.get<SwapRequest[]>('/api/swaps'),

  findShift: (id: string): Promise<Shift> => api.get<Shift>(path`/api/shifts/${id}`),

  /**
   * Approves a swap by publishing the roster that carries it, superseding the one it was raised
   * against (ADR-022). Throws an ApiError with `isRuleBreach` when that roster would break a rule,
   * and with `isConflict` when somebody has already decided the request.
   */
  approveSwap: (id: string): Promise<Roster> => api.post<Roster>(path`/api/swaps/${id}/approval`),

  /** Declines a swap; no roster changes. Throws with `isConflict` when it was already decided. */
  rejectSwap: (id: string): Promise<void> => api.post<void>(path`/api/swaps/${id}/rejection`),

  currentConstraints: (): Promise<ConstraintSet> => api.get<ConstraintSet>('/api/constraints'),

  reviseConstraints: (constraints: ConstraintSetRequest): Promise<ConstraintSet> =>
    api.put<ConstraintSet>('/api/constraints', constraints),
};
