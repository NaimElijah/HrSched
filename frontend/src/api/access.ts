import { api, type ActingIdentity } from './client';

/** Mirrors OrganisationResponse. */
export interface Organisation {
  readonly id: string;
  readonly name: string;
}

/**
 * The organisation register.
 *
 * Onboarding is the platform operator's act, which is why the caller passes an identity claiming
 * that role rather than the one the rest of the app is acting as. It is also the one request the
 * backend does not check `X-Organisation` on, because requiring an organisation to create the first
 * organisation would leave the platform unbootstrappable (ADR-020).
 *
 * There is deliberately no `findOrganisations` here. Nothing lists them, and an unused export of a
 * path the boundary check does *not* exempt is a trap: its first caller would send whatever
 * organisation the app is currently acting as and get a 404 rather than the listing they expected.
 */
export const access = {
  onboardOrganisation: (identity: ActingIdentity, name: string): Promise<Organisation> =>
    api.post<Organisation>(identity, '/api/organisations', { name }),
};
