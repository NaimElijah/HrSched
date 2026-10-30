# ADR-032: Close the security chain by default, and name every route that is open

- **Status:** Accepted
  - **Amended 2026-10-30:**
    [ADR-049](ADR-049-refuse-the-metrics-to-every-caller-until-a-scraper-exists.md) refuses the
    `metrics` and `prometheus` endpoints by placing `denyAll` before the actuator `permitAll`. The
    "anybody" in the `/actuator/**` row now holds for health, liveness, readiness and info only.
- **Date:** 2026-09-21

## Context

`SecurityConfiguration` grew one rule at a time while the identity headers were being deleted
([ADR-011](ADR-011-issue-our-own-access-tokens.md)). Each route family that moved onto the access
token gained a rule; everything still on the headers stayed under `anyRequest().permitAll()`,
because refusing it would have refused requests the application was still meant to serve.

That is now finished. Every controller takes its identity from the token, and every route the
application serves has a rule. What is left is the last line, and it is the one that decides what
happens to a route nobody thought about.

The failure modes are not symmetric:

- Ending in `permitAll`, a new endpoint is reachable by anybody the moment it is written. Nothing
  fails. No test says so. It looks exactly like an endpoint that was meant to be open, and the
  person most likely to add one is the person least likely to be thinking about this file.
- Ending in `denyAll`, a new endpoint is refused until somebody names it. That failure is loud,
  immediate, and lands on the person who has the context to decide.

## Decision

**The chain ends in `anyRequest().denyAll()`.** Every route is named by a rule above it, and
anything else — including a route added later — is refused.

What is named, and why:

| Rule | Who | Why it is not `denyAll` |
| --- | --- | --- |
| `DispatcherType.ERROR`, `ASYNC` | the container | Not requests anybody made. Without this, `denyAll` turns every 500 into a 403 and hides the real failure. |
| `POST /api/auth/sign-in`, `/refresh`, `/sign-out` | anybody | Authentication itself. Signing in has no token by definition; the other two are carried by the refresh cookie and defended against cross-site use by requiring a header no cross-site request can send (ADR-028). |
| `/actuator/**` | anybody | ADR-017. What is actually reachable is decided by `management.endpoints.web.exposure.include`, and an unexposed endpoint answers 404 rather than 403 — which tells a caller nothing, the same reasoning the tenant 404 rests on. |
| `GET /api/auth/session`, `POST /api/auth/password` | any authenticated account | The two acts somebody holding a password they did not choose has to be able to perform, and the only two reachable while that change is pending (ADR-029). |
| `POST`/`GET /api/organisations` | `OPERATOR` | The register of tenants is what an operator administers, and it names every customer (F13). |
| `GET /api/organisations/{id}` | any role | Any member may read their own; the use case decides whose, and a tenant naming another gets a 404 (ADR-030). |
| `/api/accounts/**` | `ADMIN` | Administering a tenant's staff. An Admin only — the operator holds no role at all, because it belongs to no organisation. |
| `/api/employees/**`, `/api/qualifications/**`, `/api/shifts/**`, `/api/constraints`, `/api/rosters/**`, `/api/me/**`, `/api/swaps/**`, `/api/notifications/**` | any tenant role | Everything inside an organisation. Requiring a role rather than mere authentication refuses two callers at once: the operator, and an account whose temporary password is still pending. |

**These rules are the coarse outer line, and the use case behind each route is what decides.** The
fine-grained answer — which role may publish a roster, whose schedule somebody may read — lives in
one place, the service. Writing it twice would mean two places to keep in step, and the copy that
is easy to forget is the one that fails open.

## Consequences

**Easier.** "Which routes are open?" is answered by reading one method, top to bottom. A new
controller cannot be quietly world-readable.

**Harder.** Adding a route now takes two edits, and the second one is in a file the author may not
have opened. That is the cost, and it is the point: the edit they are being forced to make is the
decision about who may call it.

**A 403 where there used to be a 404.** A path this application does not serve is now refused by
the chain rather than dispatched to nothing. That discloses a little — it says "something refuses
you here" — but it says it uniformly for every unserved path, so it distinguishes nothing.

**Actuator is the one family whose reachability is decided elsewhere.** The chain permits
`/actuator/**` and Boot's exposure list decides what exists. Anybody narrowing that list should
know that this file will not stop them widening it again.

## Alternatives considered

- **Keep `permitAll` and rely on review.** Rejected: the whole problem is that nothing fails. A
  convention that depends on somebody remembering is not a control, and this one would be
  remembered least reliably in exactly the situation it matters — somebody adding an endpoint in a
  hurry.
- **`authenticated()` as the default instead of `denyAll`.** Rejected: it would make a new route
  reachable by every signed-in account in every organisation, which is a smaller hole but still a
  decision nobody made. It also reads as though somebody had thought about it.
- **Method security (`@PreAuthorize`) instead of URL rules.** Rejected for now: the application
  layer may carry no Spring annotation (ADR-023), so the annotations would have to go on the
  controllers, which would put the authorisation answer in two places — the controller and the use
  case — rather than one. Revisit if the URL patterns ever stop lining up with the capabilities,
  which is the concrete trigger.
