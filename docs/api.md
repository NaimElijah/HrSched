# HTTP API

Every route HrSched serves, who may call it, and a walkthrough from an empty database to a published
roster. The README shows the responses that matter most; this page is the rest.

Every call except sign-in carries a bearer token HrSched signed itself. The organisation is never
part of a request: it comes from the account the token names
([ADR-031](ADRs/ADR-031-take-the-organisation-from-the-account.md)). Errors are RFC 9457 problem
details, `application/problem+json`, and every response carries its trace id as `X-Trace-Id`.

## Endpoints

The security chain admits a role to a family of routes, and closes everything it does not name
([ADR-032](ADRs/ADR-032-close-the-security-chain-by-default.md)). Each use case then checks the
exact role. A resource in another organisation answers `404`, the same as one that does not exist.

| Area | Endpoints | Who |
| :--- | :--- | :--- |
| Session | `POST /api/auth/sign-in`, `/refresh`, `/sign-out` | Anyone; refresh and sign-out need the cookie and `X-Requested-With` |
| Own account | `GET /api/auth/session`, `POST /api/auth/password` | Any signed-in account, even before its password is changed |
| Tenants | `POST`, `GET /api/organisations`; `GET /api/organisations/{id}` | Operator; a tenant may read only its own |
| Accounts | `POST`, `GET /api/accounts`; `PUT /{id}/membership`, `/{id}/activation`; `POST /{id}/password` | Admin |
| People | `POST /api/employees`; `PUT /{id}/qualifications/{qualification}`, `/{id}/contract`; `GET` list and one | Admin; reading also Scheduler |
| Availability | `POST`, `GET /api/employees/{id}/unavailability`, `/{id}/preferences`; `DELETE /{id}/unavailability?start&end`, `/{id}/preferences?start&end&strength` | The person, Admin or Scheduler |
| Qualifications | `POST /api/qualifications`; `GET` list and one | Admin; reading any member |
| Rules | `PUT /api/constraints`; `GET /api/constraints`, `/{version}` | Admin; reading any member |
| Objectives | `PUT /api/objectives`; `GET /api/objectives` | Admin; reading any member |
| Shifts | `POST /api/shifts`; `GET /api/shifts?from&to`, `/{id}` | Scheduler; reading any member |
| Rosters | `POST /api/rosters`, `/{id}/assignments`, `/{id}/publication`; `DELETE /{id}/assignments/{shift}/{employee}`; `GET` list and one, and `/{id}/assessment` | Scheduler; reading and checking also Admin |
| Swaps | `POST /api/swaps`, `/{id}/approval`, `/{id}/rejection`; `GET` list and one | Asking: the person, Admin or Scheduler; deciding: Scheduler; reading: the parties |
| Me | `GET /api/me/schedule?from&to`; `GET /api/notifications`; `POST /api/notifications/{id}/read` | Any member, their own only |

`/actuator/health/**`, `/actuator/info`, `/actuator/metrics` and `/actuator/prometheus` need no
token.

## Signing in

A sign-in returns a short-lived access token and sets a rotating refresh cookie: `HttpOnly`,
`Secure`, `SameSite=Strict`, scoped to `/api/auth`
([ADR-028](ADRs/ADR-028-keep-the-session-in-memory-and-a-rotating-refresh-cookie.md)). An account
whose password somebody else chose gets a token that can do one thing, change that password; every
other route answers `403` until it does. The new password must be at least 15 characters. Changing
it revokes every earlier session, so only the token in that response works. A first sign-in answers
like this, with the token shortened and every identifier invented. An Employee account is always
linked to an employee (R34), so `employee` is null only for the other roles:

```json
{
  "accessToken": "eyJraWQiOiJPNWN…",
  "tokenType": "Bearer",
  "expiresIn": 599,
  "session": {
    "userId": "893ce360-828c-4a11-b9c6-287a31d8db16",
    "kind": "TENANT",
    "organisation": "71261a62-1718-42f9-9ada-83f0e7dda61f",
    "role": "EMPLOYEE",
    "employee": "5d0c8f3e-2b6a-4e19-9c47-0a3f61e8b2d4",
    "signInName": "robin@example.com",
    "passwordChangeRequired": true
  }
}
```

## Walkthrough

You need `curl`, `jq` and `openssl`, and a backend started with `./mvnw spring-boot:run` on a fresh
database. That command bootstraps the local operator `operator` / `local-development-only`. The
local database keeps its rows, so on a second run, start over with
`docker compose -f backend/compose.yaml down -v`, which deletes every local row.

```bash
api() { curl -s -H "Authorization: Bearer $1" -H 'Content-Type: application/json' "${@:2}"; }

# Sign in with a password somebody else chose, replace it, and print the token that works.
enter() {
  local pending
  pending=$(curl -s -X POST localhost:8080/api/auth/sign-in -H 'Content-Type: application/json' \
    -d "{\"signInName\":\"$1\",\"password\":\"$2\"}" | jq -r .accessToken)
  api "$pending" -X POST localhost:8080/api/auth/password \
    -d "{\"currentPassword\":\"$2\",\"newPassword\":\"$3\"}" | jq -r .accessToken
}

# Every password is generated, so none reaches shell history; the minimum is 15 characters.
password() { openssl rand -hex 12; }

# 0. The operator onboards an organisation with its first Admin, and can do nothing inside it.
OPERATOR=$(enter operator local-development-only "$(password)")
ADMIN_TEMPORARY=$(password)
api "$OPERATOR" -X POST localhost:8080/api/organisations -d "{\"name\":\"Example Clinic\",
  \"firstAdmin\":{\"signInName\":\"admin@example.com\",\"temporaryPassword\":\"$ADMIN_TEMPORARY\"}}"
ADMIN=$(enter admin@example.com "$ADMIN_TEMPORARY" "$(password)")

# 1. The Admin adds a person, and an account that may build rosters.
api "$ADMIN" -X POST localhost:8080/api/employees -d '{"name":"Robin Example"}'
SCHEDULER_TEMPORARY=$(password)
api "$ADMIN" -X POST localhost:8080/api/accounts -d "{\"signInName\":\"scheduler@example.com\",
  \"temporaryPassword\":\"$SCHEDULER_TEMPORARY\",\"role\":\"SCHEDULER\"}"
SCHEDULER=$(enter scheduler@example.com "$SCHEDULER_TEMPORARY" "$(password)")

# 2. The Admin configures the rules. Invented values, not any statute's.
api "$ADMIN" -X PUT localhost:8080/api/constraints \
  -d '{"minimumRestMinutes":661,"maximumConsecutiveDays":3}'

# 3. The Scheduler defines the work. Its days are counted in its own zone.
api "$SCHEDULER" -X POST localhost:8080/api/shifts -d '{"start":"2026-11-02T08:00:00Z",
  "end":"2026-11-02T16:00:00Z","zone":"Europe/London","requiredHeadcount":1}'

# 4. Generate a draft for the day, then publish it.
ROSTER=$(api "$SCHEDULER" -X POST localhost:8080/api/rosters \
  -d '{"start":"2026-11-02T00:00:00Z","end":"2026-11-03T00:00:00Z"}' | jq -r .id)
api "$SCHEDULER" -X POST "localhost:8080/api/rosters/$ROSTER/publication"
```

The last call answers with the published roster, shown in the README's
[Usage](../README.md#usage) section. A shift may also be sent with `"unpopular":true`, which marks
it as work people would rather not do; left out, it is not marked
([ADR-010](ADRs/ADR-010-score-a-roster-by-three-objectives-each-organisation-weights.md)). A `PUT /api/constraints` replaces the whole set rather than
merging into it, and every limit is optional: one left out is not enforced.

## Checking a draft

`GET /api/rosters/{id}/assessment` answers what publishing a draft would say now, without
publishing it: every rule it breaks, in the same shape a publication refusal uses, and every shift
in its period with fewer people than it requires. It is advice, judged without the publication
lock; publishing judges again and decides. A roster that is not a draft answers `409`, and one
naming a shift that cannot be resolved answers `422` titled `Roster cannot be checked against the
rules`, as publishing it would. With invented identifiers:

```json
{
  "roster": "3f1c2a9e-0000-4000-8000-000000000001",
  "constraintVersion": 2,
  "violations": [
    {
      "employee": "3f1c2a9e-0000-4000-8000-000000000002",
      "constraint": "declared unavailability",
      "detail": "shift 3f1c2a9e-0000-4000-8000-000000000003 runs through time they declared they cannot work"
    }
  ],
  "shortfalls": [
    {
      "shift": "3f1c2a9e-0000-4000-8000-000000000004",
      "required": 2,
      "assigned": 1,
      "shortBy": 1
    }
  ]
}
```

## Other refusals

- **Any rule a draft breaks.** Publication answers `422` titled `Roster breaks the rules`, with a
  `violations` list naming every breach: `overlapping work`, `required qualifications`,
  `declared unavailability`, `not of this organisation`, or the working-time rule broken. Each
  names the employee, and its `detail` names shifts and rosters by identifier only.
- **A shift the rules cannot check.** Publication answers `422` titled
  `Roster cannot be checked against the rules`.
- **A concurrent publication.** An overlapping roster published at the same moment answers `409`,
  titled `Published concurrently`.
- **A roster whose status forbids the change.** Editing or publishing a roster that is no longer a
  draft, or asking for a swap against one that is not the published roster, answers `409` titled
  `Roster status forbids this`. Reload it: somebody most likely published it first.
- **An Employee account linked to nobody.** Creating one, taking the link away from one, giving
  the Employee role to an account linked to nobody, or restoring one stored unlinked before R34
  answers `422` titled `Employee account not linked`.
