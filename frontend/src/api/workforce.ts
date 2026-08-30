import { api, type ActingIdentity } from './client';

/** Mirrors EmployeeResponse. */
export interface Employee {
  readonly id: string;
  readonly name: string;
  readonly qualifications: readonly string[];
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
    api.put<void>(identity, `/api/employees/${employee}/qualifications/${qualification}`),

  setContract: (
    identity: ActingIdentity,
    employee: string,
    weeklyContractedMinutes: number,
  ): Promise<void> =>
    api.put<void>(identity, `/api/employees/${employee}/contract`, { weeklyContractedMinutes }),

  declareUnavailability: (
    identity: ActingIdentity,
    employee: string,
    start: string,
    end: string,
  ): Promise<void> =>
    api.post<void>(identity, `/api/employees/${employee}/unavailability`, { start, end }),

  statePreference: (
    identity: ActingIdentity,
    employee: string,
    start: string,
    end: string,
    strength: PreferenceStrength,
  ): Promise<void> =>
    api.post<void>(identity, `/api/employees/${employee}/preferences`, { start, end, strength }),
};
