import { api, type ActingIdentity } from './client';

/** Mirrors OrganisationResponse. */
export interface Organisation {
  readonly id: string;
  readonly name: string;
}

/**
 * The organisation register.
 *
 * Both calls are the platform operator's alone, which is why each takes an identity claiming that
 * role rather than the one the rest of the app is acting as. Onboarding is also the one request the
 * backend does not check `X-Organisation` on, because requiring an organisation to create the first
 * organisation would leave the platform unbootstrappable (ADR-020).
 */
export const access = {
  onboardOrganisation: (identity: ActingIdentity, name: string): Promise<Organisation> =>
    api.post<Organisation>(identity, '/api/organisations', { name }),

  findOrganisations: (identity: ActingIdentity): Promise<Organisation[]> =>
    api.get<Organisation[]>(identity, '/api/organisations'),
};
