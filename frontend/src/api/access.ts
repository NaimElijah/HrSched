import { api, path } from './client';

/** Mirrors OrganisationResponse. */
export interface Organisation {
  readonly id: string;
  readonly name: string;
}

/** Who will administer a newly onboarded organisation. */
export interface FirstAdmin {
  readonly signInName: string;
  readonly temporaryPassword: string;
}

/**
 * The organisation register (F13).
 *
 * Every call is made as whoever holds the session, which for all three of these is the platform
 * operator: a tenant role is refused onboarding and listing, and may read only its own.
 */
export const access = {
  /**
   * Onboards an organisation and the account that will administer it, in one request.
   *
   * The first Admin is not optional. The operator is refused everything inside an organisation
   * (ADR-030), so one onboarded without an Admin would have nobody able to add one.
   *
   * @param name what to call the organisation
   * @param firstAdmin the account to create alongside it, which must change its password at first
   *   sign-in
   */
  onboardOrganisation: (name: string, firstAdmin: FirstAdmin): Promise<Organisation> =>
    api.post<Organisation>('/api/organisations', { name, firstAdmin }),

  /** Every organisation on the platform. The Operator's alone: a tenant role is refused with a 403. */
  findOrganisations: (): Promise<Organisation[]> => api.get<Organisation[]>('/api/organisations'),

  /** One organisation: any of them for the Operator, and only its own for a tenant role. */
  findOrganisation: (id: string): Promise<Organisation> =>
    api.get<Organisation>(path`/api/organisations/${id}`),
};
