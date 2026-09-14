import { api, path, type ActingIdentity } from './client';

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

export const workforce = {
  findEmployees: (identity: ActingIdentity): Promise<Employee[]> =>
    api.get<Employee[]>(identity, '/api/employees'),

  addEmployee: (identity: ActingIdentity, name: string): Promise<Employee> =>
    api.post<Employee>(identity, '/api/employees', { name }),

  findQualifications: (identity: ActingIdentity): Promise<Qualification[]> =>
    api.get<Qualification[]>(identity, '/api/qualifications'),

  defineQualification: (identity: ActingIdentity, name: string): Promise<Qualification> =>
    api.post<Qualification>(identity, '/api/qualifications', { name }),

  grantQualification: (
    identity: ActingIdentity,
    employee: string,
    qualification: string,
  ): Promise<void> =>
    api.put<void>(identity, path`/api/employees/${employee}/qualifications/${qualification}`),

  setContract: (
    identity: ActingIdentity,
    employee: string,
    weeklyContractedMinutes: number,
  ): Promise<void> =>
    api.put<void>(identity, path`/api/employees/${employee}/contract`, { weeklyContractedMinutes }),

  declareUnavailability: (
    identity: ActingIdentity,
    employee: string,
    start: string,
    end: string,
  ): Promise<void> =>
    api.post<void>(identity, path`/api/employees/${employee}/unavailability`, { start, end }),

  statePreference: (
    identity: ActingIdentity,
    employee: string,
    start: string,
    end: string,
    strength: PreferenceStrength,
  ): Promise<void> =>
    api.post<void>(identity, path`/api/employees/${employee}/preferences`, {
      start,
      end,
      strength,
    }),
};
