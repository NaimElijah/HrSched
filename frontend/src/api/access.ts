import { api, path } from './client';

/** Mirrors OrganisationResponse. */
export interface Organisation {
  readonly id: string;
  readonly name: string;
}

/**
 * The organisation register (F13).
 *
 * Onboarding is the one request the backend does not check `X-Organisation` on, because requiring
 * an organisation to create the first organisation would leave the platform unbootstrappable
 * (ADR-020). Listing and reading are checked like every other request, so a claim naming an
 * organisation nobody onboarded is refused with a 404 before either reaches the register.
 *
 * Every call is made as whoever the client is currently acting as. The identity bar bootstraps a
 * first organisation by claiming OPERATOR for that one request; everything else claims what the bar
 * shows.
 */
export const access = {
  onboardOrganisation: (name: string): Promise<Organisation> =>
    api.post<Organisation>('/api/organisations', { name }),

  /** Every organisation on the platform. The Operator's alone: a tenant role is refused with a 403. */
  findOrganisations: (): Promise<Organisation[]> => api.get<Organisation[]>('/api/organisations'),

  /** One organisation: any of them for the Operator, and only its own for a tenant role. */
  findOrganisation: (id: string): Promise<Organisation> =>
    api.get<Organisation>(path`/api/organisations/${id}`),
};
