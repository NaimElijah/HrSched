import { api, path } from './client';

/** Mirrors OrganisationResponse. */
export interface Organisation {
  readonly id: string;
  readonly name: string;
}

/** What an account may do inside its organisation. */
export type TenantRole = 'EMPLOYEE' | 'SCHEDULER' | 'ADMIN';

/** Mirrors AccountResponse: an account as an Admin of its organisation sees it. */
export interface Account {
  readonly id: string;
  readonly signInName: string;
  readonly role: TenantRole;
  /**
   * The personnel record this account is, or null for somebody who is never rostered. An Employee
   * account is always linked (R34), but for one stored unlinked before that rule, which is disabled
   * until it is linked.
   */
  readonly employee: string | null;
  readonly active: boolean;
  readonly passwordChangeRequired: boolean;
  readonly locked: boolean;
  readonly createdAt: string;
}

/** What it takes to create an account. The organisation is not here, and cannot be. */
export interface NewAccount {
  readonly signInName: string;
  readonly temporaryPassword: string;
  readonly role: TenantRole;
  readonly employee?: string;
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

/**
 * Account administration, which is an Admin's and happens in their own organisation (F15).
 *
 * **No call here names an organisation, and none can.** The backend takes it from the token, so
 * there is no identifier for this client to get wrong and none for a caller to change. A Scheduler
 * or an Employee reaching any of these is refused with a 403 that the screen renders as itself.
 */
export const accounts = {
  /** Every account of the caller's own organisation, oldest first. */
  findAccounts: (): Promise<Account[]> => api.get<Account[]>('/api/accounts'),

  /**
   * Creates an account whose owner must replace its password before they can do anything (ADR-029).
   *
   * @param account who to create, and what they may do
   */
  createAccount: (account: NewAccount): Promise<void> => api.post<void>('/api/accounts', account),

  /**
   * Changes what an account is: its role and the person it belongs to, together.
   *
   * The two travel as one because the backend takes them as one value — sending a role on its own
   * would unlink whoever the account belonged to.
   *
   * @param id the account to change
   * @param role what it may do from now on
   * @param employee the personnel record it is, omitted to unlink it
   */
  changeMembership: (id: string, role: TenantRole, employee: string | null): Promise<void> =>
    api.put<void>(path`/api/accounts/${id}/membership`, {
      role,
      ...(employee === null ? {} : { employee }),
    }),

  /**
   * @param id the account to change
   * @param active whether it may sign in and act; disabling ends every session it holds
   */
  setAccountActive: (id: string, active: boolean): Promise<void> =>
    api.put<void>(path`/api/accounts/${id}/activation`, { active }),

  /**
   * Gives an account a new temporary password, ending its sessions and clearing any lockout.
   *
   * @param id the account to reset
   * @param temporaryPassword the password it is given, which its owner must replace
   */
  resetPassword: (id: string, temporaryPassword: string): Promise<void> =>
    api.post<void>(path`/api/accounts/${id}/password`, { temporaryPassword }),
};
