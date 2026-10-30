# ADR-055: Read an account or a session without an organisation where none is known

- **Status:** Accepted
- **Date:** 2026-10-30

## Context

[ADR-009](ADR-009-tenant-isolation-in-the-domain.md) decides that every repository lookup takes an
`OrganisationId`, so a cross-tenant read cannot be written by forgetting a filter. It names no
exception. [ADR-019](ADR-019-an-application-layer-for-the-access-context.md) names one: the
register of organisations. [ADR-029](ADR-029-provision-accounts-from-the-top-down.md) names the
platform operator's account row, which carries no `organisation_id`.

The access context has more reads than that without an organisation, and the port javadocs are
where the reasons are written. This record collects them.

Forces:

- **A sign-in request carries a name and a password and nothing else.** There is no organisation to
  scope by until the account has been found. Sign-in names are unique platform-wide (R13), so the
  question has one answer.
- **A token, or the session a refresh token belongs to, names an account.** The platform operator
  belongs to no organisation, so a scoped lookup could not find its account under any argument.
- **A refresh request carries a cookie.** The refresh token is stored as a digest, never as itself,
  so the digest is the only thing to look one up by.
- **A session belongs to an account, not to a tenant.** `sign_in_session` and `refresh_token` carry
  no `organisation_id` (`V4__sign_in_sessions.sql`), and the operator's sessions belong to no
  tenant at all.
- **The organisation comes from the account, never from the request.**
  [ADR-031](ADR-031-take-the-organisation-from-the-account.md) rejected letting a request name
  which membership it uses, because that puts the organisation back in the request.
- **The operator bootstrap runs at startup with no actor and no request.**
- **A cross-tenant read is a security incident, not a bug.**

## Decision

**We read or write an account, a session or the register of organisations without an organisation
in exactly the places below, and nowhere else in the access context. Each port says so in its
javadoc.** Every other read in the context takes the organisation.

| Port | Method | Why it takes no organisation | What keeps it safe |
| --- | --- | --- | --- |
| `AccountRepository` | `findBySignInName(SignInName)` | Sign-in has a name and a password, and the operator bootstrap has no request | Whoever signs in is then the account found. The bootstrap reads only whether the account in the way is the operator, after its own insert was refused, and returns nothing |
| `AccountRepository` | `findForAuthentication(UserId)` | A token, or the session a refresh token belongs to, names an account, and the operator's belongs to no organisation | The result decides who the caller *is*, never an answer about somebody else |
| `SessionRepository` | `findRefreshToken(byte[])` | Only the digest of the presented token is known | The digest is the credential: a caller can only find a token it holds |
| `SessionRepository` | `findAuthenticatedSession(UserId, SignInSessionId)` | It discovers which organisation the caller belongs to | Both identifiers come out of a token this system signed |
| `SessionRepository` | every other method: `start`, `markReplaced`, `issueSuccessor`, `revoke`, `revokeEverySessionOf` | A session belongs to an account, and the operator's belong to no tenant | Each is reached by an identifier from a verified token, or from a refresh token the caller holds. `revokeEverySessionOf` is the exception, explained below |
| `OrganisationRepository` | `findAll()` | It *is* the register of tenants | The use case above it refuses listing to anyone who belongs to a tenant |
| `OrganisationRepository` | `findById(OrganisationId)` | Its id is the target, taken from the URL path, and an operator may read any organisation ([ADR-030](ADR-030-confine-the-operator-to-the-register-of-organisations.md)) | `OrganisationService.findOrganisation` calls `requireAccessTo` first for every actor but the operator, so forgetting that call is forgetting the filter |

**`ReadSessionUseCase.findSession` takes no organisation, even for a `TenantActor`.** The same use
case serves the operator, who has none, so it reads the account by `findForAuthentication` for both.

**`revokeEverySessionOf` takes an account id and no organisation, and its SQL has no tenant
predicate.** Two of its three callers read that id from the URL path. What keeps it safe is the
scoped `findForUpdate` earlier in the same use case: `AccountService.setAccountActive` and
`resetPassword` load the account under its organisation before they revoke anything. The third
caller, the password change, passes the caller's own account. A new "sign out everywhere" use case
has to load the account under an organisation before it revokes.

One method looks unscoped and is not exempt, and a rule must treat it so:

- **`AccountRepository.findForUpdate(Optional<OrganisationId>, UserId)` is scoped.** An empty
  organisation names the operator's scope, `organisation_id IS NULL`. A tenant's account is never
  found under it, nor the operator's under an organisation. Its parameter is optional, so the
  architecture rule below, which looks for an `OrganisationId` parameter, names it in its allow-list.

**`sign_in_session` and `refresh_token` carry no `organisation_id`, and none is added.** Their
organisation is the one their account has: one join away through `account_id` for a session, and
two for a refresh token, which reaches its account through its session.

## Consequences

**Easier**

- **Each exemption is a decision somebody made,** named in the port, with the reason beside it. A
  reviewer looking for a read that forgot its tenant has a list to compare against.
- **Sign-in, refresh and token verification need nothing from the caller but what they hold.** None
  of them has an organisation to supply.

**Harder**

- **ADR-009's guarantee does not cover these reads.** For them the compiler does not participate:
  the protection is what each caller does with the result. `findBySignInName`'s javadoc says so: do
  not reuse it to look up an account for somebody who already has an organisation.
- **A new caller of one of them can read across tenants,** and no rule sees a caller. What a rule
  can see is the signature: `repository_lookups_take_an_organisation` in `HexagonalArchitectureTest`
  fails the build on a `find…` or `lock…` method of a driven-port repository that takes no
  `OrganisationId`, except those its `UNSCOPED_LOOKUPS` list names: `findBySignInName`,
  `findForAuthentication`, `findRefreshToken`, `findAuthenticatedSession`, `findAll`, and
  `findForUpdate` for its `Optional`. Naming the methods exactly is what lets it allow these and no
  others. `findById` passes it, because it takes an `OrganisationId`, and the session writes are
  not lookups, so for those the guard in the table is review's to check, as is every caller.
- **A failed sign-in writes to an account the caller does not hold.** `findBySignInName` finds the
  account by a name anybody can submit, and a wrong password is counted against it, so a stranger
  who knows a name can lock it out ([#142](https://github.com/NaimElijah/HrSched/issues/142)).
- **The platform-wide name check answers across tenants.** Creating an account under a name another
  organisation holds is a 409, so an Admin can learn that a name is in use elsewhere
  ([#141](https://github.com/NaimElijah/HrSched/issues/141)).
- **Sessions cannot be listed or ended by organisation without a join through `account`.**
- **A row-level security policy, if one is ever adopted, would have to exempt these reads too,**
  beside the two exemptions ADR-004 already names. Its triggers are in ADR-011.
- **ADR-029's "the one aggregate root besides `organisation` without an `organisation_id`" is
  narrower than the schema.** The session tables have none either, and are not the operator's row.
  ADR-019's "the one repository here whose reads are not scoped" is no longer one.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **Add `organisation_id` to `sign_in_session` and `refresh_token`** | A filter that could not be written for half the rows: the operator's sessions belong to no tenant. A refresh carries a digest, and a sign-in a name, so neither could supply one. |
| **Require an organisation on sign-in** | It puts the organisation back in the request, which ADR-031 took out. Sign-in names are unique platform-wide so that the lookup has one answer. |
| **Give the operator an organisation** | The operator is confined to the register of organisations (ADR-030), and its row is kept distinct from a tenant's by a `CHECK` (ADR-029). |
| **Scope sign-in names to an organisation** | R13 makes them unique across the platform, and the sign-in lookup depends on it. |

**Revisit when** a read in the table gains a second caller that already has an organisation, which
should use the scoped read, or when a row-level security policy is adopted (ADR-011 names the
triggers: a query found missing its tenant predicate, or a second service writing this database
directly).
