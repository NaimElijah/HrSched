import { api, path } from './client';

/** A span of time, as the API renders one. */
export interface Period {
  readonly start: string;
  readonly end: string;
}

/**
 * Mirrors EmployeeResponse.
 *
 * Unavailability and contracted hours are here because requirement F4 asks a Scheduler to see them.
 * They are personal data, and the backend is what keeps them from a colleague: listing employees
 * needs Admin or Scheduler. Nothing in this file enforces that, and nothing here should look as
 * though it does.
 */
export interface Employee {
  readonly id: string;
  readonly name: string;
  readonly qualifications: readonly string[];
  readonly unavailability: readonly Period[];
  /** Contracted minutes per week, or null when none are set. Minutes, as the API sends them. */
  readonly weeklyContractedMinutes: number | null;
}

/** Mirrors QualificationResponse. */
export interface Qualification {
  readonly id: string;
  readonly name: string;
}

/** How an employee feels about working a span. Soft: it never blocks a roster. */
export type PreferenceStrength = 'PREFERS' | 'PREFERS_NOT';

/** Mirrors PreferenceResponse: a span, and how its employee feels about working it. */
export interface StatedPreference extends Period {
  readonly strength: PreferenceStrength;
}

/**
 * The workforce context's endpoints: employees, qualifications, contracts, unavailability and
 * preferences.
 *
 * Every call rejects with an `ApiError` for a refusal, as {@link api} does. The organisation is
 * never a parameter, as the backend takes it from the token (ADR-031).
 */
export const workforce = {
  /** Every employee of the organisation. An Admin's or a Scheduler's to read; others get a 403. */
  findEmployees: (): Promise<Employee[]> => api.get<Employee[]>('/api/employees'),

  /**
   * Adds an employee by name, with no qualification, absence or contract yet. Admin only. A name
   * the store cannot hold is refused with a 400.
   */
  addEmployee: (name: string): Promise<Employee> => api.post<Employee>('/api/employees', { name }),

  /** Every qualification the organisation has defined. Any member may read them. */
  findQualifications: (): Promise<Qualification[]> =>
    api.get<Qualification[]>('/api/qualifications'),

  /** Defines a qualification by name. Admin only. A name the store cannot hold gets a 400. */
  defineQualification: (name: string): Promise<Qualification> =>
    api.post<Qualification>('/api/qualifications', { name }),

  /**
   * Adds a qualification to what an employee holds, and answers no content. Admin only. A 404
   * when the employee or the qualification is not the organisation's.
   */
  grantQualification: (employee: string, qualification: string): Promise<void> =>
    api.put<void>(path`/api/employees/${employee}/qualifications/${qualification}`),

  /**
   * Sets an employee's contracted minutes per week, replacing any earlier value. Admin only. A
   * count that is not positive, or too large to hold, is refused with a 400, not a 422.
   */
  setContract: (employee: string, weeklyContractedMinutes: number): Promise<void> =>
    api.put<void>(path`/api/employees/${employee}/contract`, { weeklyContractedMinutes }),

  /** What somebody declared they cannot work. The backend answers only them, an Admin or a Scheduler. */
  findUnavailability: (employee: string): Promise<Period[]> =>
    api.get<Period[]>(path`/api/employees/${employee}/unavailability`),

  /** What somebody stated they would rather work or not, answered to the same people. */
  findPreferences: (employee: string): Promise<StatedPreference[]> =>
    api.get<StatedPreference[]>(path`/api/employees/${employee}/preferences`),

  /**
   * Adds a span the employee cannot work. The person, an Admin or a Scheduler may. A span
   * overlapping one already declared is refused with a 409, and one the store cannot hold with a
   * 400.
   */
  declareUnavailability: (employee: string, start: string, end: string): Promise<void> =>
    api.post<void>(path`/api/employees/${employee}/unavailability`, { start, end }),

  /**
   * A declaration has no identifier, so it is named by what it says, in the query because a DELETE
   * carries no body (ADR-040). Send back exactly the instants the read-back returned.
   */
  withdrawUnavailability: (employee: string, start: string, end: string): Promise<void> =>
    api.delete<void>(
      `${path`/api/employees/${employee}/unavailability`}` +
        `?start=${encodeURIComponent(start)}&end=${encodeURIComponent(end)}`,
    ),

  /** Named by its span and strength; every identical statement is withdrawn with it (ADR-040). */
  withdrawPreference: (
    employee: string,
    start: string,
    end: string,
    strength: PreferenceStrength,
  ): Promise<void> =>
    api.delete<void>(
      `${path`/api/employees/${employee}/preferences`}` +
        `?start=${encodeURIComponent(start)}&end=${encodeURIComponent(end)}` +
        `&strength=${encodeURIComponent(strength)}`,
    ),

  statePreference: (
    employee: string,
    start: string,
    end: string,
    strength: PreferenceStrength,
  ): Promise<void> =>
    api.post<void>(path`/api/employees/${employee}/preferences`, {
      start,
      end,
      strength,
    }),
};
