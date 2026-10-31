# HTTP API

Every route HrSched serves, who may call it, and a walkthrough from an empty database to a published
roster. The README shows the responses that matter most; this page is the rest.

Every call except sign-in carries a bearer token HrSched signed itself. The organisation is never
part of a request: it comes from the account the token names
([ADR-031](ADRs/ADR-031-take-the-organisation-from-the-account.md)). A refusal is an RFC 9457
problem detail, `application/problem+json`, bar the few [listed below](#what-is-not-a-problem-detail),
and every response carries its trace id as `X-Trace-Id`. Every
response the security chain writes also carries `Content-Security-Policy: default-src 'none';
frame-ancestors 'none'` and `Referrer-Policy: no-referrer`. The request firewall's own `400`, for a
malformed path, is written before the chain and carries neither.

## Endpoints

The security chain admits a role to a family of routes, and closes everything it does not name
([ADR-032](ADRs/ADR-032-close-the-security-chain-by-default.md)). Each use case then checks the
exact role. A resource in another organisation answers `404`, the same as one that does not exist,
and every `404` gives the same detail, `No such resource.`, naming nothing that was asked for.

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
| Rosters | `POST /api/rosters/generations`, `/{id}/assignments`, `/{id}/publication`; `DELETE /{id}/assignments/{shift}/{employee}`; `GET` list and one, `/{id}/assessment`, and `/generations/{id}` | Scheduler; reading, checking and reading a generation also Admin |
| Swaps | `POST /api/swaps`, `/{id}/approval`, `/{id}/rejection`; `GET` list and one | Asking: the person, Admin or Scheduler; deciding: Scheduler; reading: the parties |
| Me | `GET /api/me/schedule?from&to`; `GET /api/notifications`; `POST /api/notifications/{id}/read` | Any member, their own only |

`/actuator/health/**` and `/actuator/info` need no token. `/actuator/metrics` and
`/actuator/prometheus`, and every path beneath either, are refused to every caller: `401` without a
token and `403` with any token, the Operator's included
([ADR-049](ADRs/ADR-049-refuse-the-metrics-to-every-caller-until-a-scraper-exists.md)).
`GET /actuator` no longer lists the endpoints.

## Signing in

A sign-in returns a short-lived access token and sets a rotating refresh cookie: `HttpOnly`,
`Secure`, `SameSite=Strict`, scoped to `/api/auth`
([ADR-028](ADRs/ADR-028-keep-the-session-in-memory-and-a-rotating-refresh-cookie.md)). An account
whose password somebody else chose gets a token that can do one thing, change that password; every
other route answers `403` titled `Password change required`, of type
`urn:hrsched:problem:password-change-required`, whose detail says to change it first, until it
does. The new password must be at least 15 characters. Changing
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

# 4. Generate a draft for the day. It runs in the background: ask until it ends, then publish.
GENERATION=$(api "$SCHEDULER" -X POST localhost:8080/api/rosters/generations \
  -d '{"start":"2026-11-02T00:00:00Z","end":"2026-11-03T00:00:00Z"}' | jq -r .id)
generation() { api "$SCHEDULER" "localhost:8080/api/rosters/generations/$GENERATION"; }
while [ "$(generation | jq -r .status)" = PENDING ]; do sleep 1; done
ROSTER=$(generation | jq -r .roster)
api "$SCHEDULER" -X POST "localhost:8080/api/rosters/$ROSTER/publication"
```

The last call answers with the published roster, shown in the README's
[Usage](../README.md#usage) section. A shift may also be sent with `"unpopular":true`, which marks
it as work people would rather not do; left out, it is not marked
([ADR-010](ADRs/ADR-010-score-a-roster-by-three-objectives-each-organisation-weights.md)). A `PUT /api/constraints` replaces the whole set rather than
merging into it, and every limit is optional: one left out is not enforced.

## Generating a draft

`POST /api/rosters/generations` with a `start` and an `end` answers `202` at once, with where to read
the generation in `Location`, because a solve at the largest stated size takes minutes ([ADR-046](ADRs/ADR-046-generate-in-the-background-and-keep-the-job-in-postgresql.md)).
`GET /api/rosters/generations/{id}` then answers its `status`:

- `PENDING` while it is solved.
- `SUCCEEDED`, with the draft's id in `roster`.
- `REFUSED`, with `violations` in the shape a publication refusal uses: people a published roster
  carries onto these shifts can no longer stay on them, and it is that roster that has to change
  ([#130](https://github.com/NaimElijah/HrSched/issues/130)).
- `FAILED`, either refused before it was solved, as below, or ended by a defect the server logged.
- `LOST`, still pending past `hrsched.generation.lost-after`: the server solving it stopped.

Two refusals answer `503` at once, and store the generation as `FAILED` so nobody waits on it:

- When an instance is already solving `hrsched.generation.concurrent-runs` generations, one more
  answers `503` titled `Generation is busy` rather than waiting.
- When the instance asked is shutting down, it answers `503` titled `Generation is unavailable`.
  Asking again may reach an instance that is not; raising `concurrent-runs` does not help it.

## Checking a draft

`GET /api/rosters/{id}/assessment` answers what publishing a draft would say now, without
publishing it: every rule it breaks, in the same shape a publication refusal uses, and every shift
in its period with fewer people than it requires. Beside those, how the draft scores on each
objective, with the organisation's weight on it, and the weighted total; lower is better, an
unweighted objective adds nothing, and the score never decides whether the draft may be published
([ADR-010](ADRs/ADR-010-score-a-roster-by-three-objectives-each-organisation-weights.md)). It is
advice, judged without the publication lock; publishing judges again and decides. A roster that is not a draft answers `409`, and one
naming a shift that cannot be resolved answers `422` titled `Roster cannot be checked against the
rules`, as publishing it would. With invented identifiers and weights:

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
  ],
  "objectives": [
    { "objective": "FAIRNESS", "measure": 2, "weight": 3 },
    { "objective": "PREFERENCES", "measure": 1, "weight": null },
    { "objective": "DISRUPTION", "measure": 0, "weight": 1 }
  ],
  "weightedTotal": 6
}
```

## Problem types

Every problem detail names its kind in `type`, a URN that is never fetched
([ADR-057](ADRs/ADR-057-name-every-kind-of-problem-with-a-urn.md)). A kind has one status and one
title wherever it is answered, so a client can tell refusals apart by `type` and leave the title
to be read. `detail` is the sentence to show a person.

| Type | Status | Title |
| :--- | :--- | :--- |
| `urn:hrsched:problem:invalid-request` | `400` | `Invalid request` |
| `urn:hrsched:problem:malformed-request` | `400` | `Malformed request` |
| `urn:hrsched:problem:sign-in-refused` | `401` | `Sign-in refused` |
| `urn:hrsched:problem:unauthenticated` | `401` | `Unauthenticated` |
| `urn:hrsched:problem:not-permitted` | `403` | `Not permitted` |
| `urn:hrsched:problem:password-change-required` | `403` | `Password change required` |
| `urn:hrsched:problem:password-not-changed` | `403` | `Password not changed` |
| `urn:hrsched:problem:not-found` | `404` | `Not found` |
| `urn:hrsched:problem:method-not-allowed` | `405` | `Method not allowed` |
| `urn:hrsched:problem:not-acceptable` | `406` | `Not acceptable` |
| `urn:hrsched:problem:already-assigned` | `409` | `Already assigned` |
| `urn:hrsched:problem:already-requested` | `409` | `Already requested` |
| `urn:hrsched:problem:employee-already-linked` | `409` | `Employee already linked` |
| `urn:hrsched:problem:last-active-admin` | `409` | `Last active Admin` |
| `urn:hrsched:problem:no-longer-pending` | `409` | `No longer pending` |
| `urn:hrsched:problem:not-your-own-password` | `409` | `Not your own password` |
| `urn:hrsched:problem:overlaps-a-declared-span` | `409` | `Overlaps a declared span` |
| `urn:hrsched:problem:published-concurrently` | `409` | `Published concurrently` |
| `urn:hrsched:problem:revised-concurrently` | `409` | `Revised concurrently` |
| `urn:hrsched:problem:roster-status-forbids-this` | `409` | `Roster status forbids this` |
| `urn:hrsched:problem:sign-in-name-taken` | `409` | `Sign-in name taken` |
| `urn:hrsched:problem:request-too-large` | `413` | `Request too large` |
| `urn:hrsched:problem:unsupported-media-type` | `415` | `Unsupported media type` |
| `urn:hrsched:problem:assignment-refused` | `422` | `Assignment refused` |
| `urn:hrsched:problem:employee-account-not-linked` | `422` | `Employee account not linked` |
| `urn:hrsched:problem:password-not-accepted` | `422` | `Password not accepted` |
| `urn:hrsched:problem:roster-breaks-the-rules` | `422` | `Roster breaks the rules` |
| `urn:hrsched:problem:roster-cannot-be-checked-against-the-rules` | `422` | `Roster cannot be checked against the rules` |
| `urn:hrsched:problem:unknown-employee` | `422` | `Unknown employee` |
| `urn:hrsched:problem:generation-is-busy` | `503` | `Generation is busy` |
| `urn:hrsched:problem:generation-is-unavailable` | `503` | `Generation is unavailable` |

### What is not a problem detail

Everything above is. These are not, and carry no `type`:

- **A session read that finds no account.** `GET /api/auth/session` answers a bare `404` with no
  body when the token's account no longer exists; what it should answer is an open decision
  ([#126](https://github.com/NaimElijah/HrSched/issues/126)).
- **What the container renders.** A request the firewall refuses (a malformed path, `400`), a form
  body over the server's form limit (`413`, refused while the body is read), and any failure the
  application does not handle (`500`) are rendered by the container's error page, Boot's
  `application/json` with `timestamp`, `status`, `error` and `path`, or HTML to a browser asking for
  it.
- **A context's refusal to an `Accept` that does not parse.** A refusal a context's handler writes,
  such as a `404` or a `409`, keeps its status, but to a request whose `Accept` is not a media type
  at all it has no body and no `Content-Type`: the type its body would be written in is chosen from
  that `Accept`, and it cannot be. Send `application/json`, or no `Accept`.
- **Actuator's own answers.** Health answers `503` with its own body when it is down; that is a
  state, not a refusal.

## Other refusals

- **No token, or one that is not accepted.** Answers `401` titled `Unauthenticated`, whose
  `WWW-Authenticate` challenge has two shapes. With no token it is exactly `Bearer`. With a token,
  whatever refused it (expired, a signature that does not verify, a missing claim, or an account that
  does not exist), it is the same sentence, so the response never says which check failed:
  `Bearer error="invalid_token", error_description="The access token was not accepted",
  error_uri="https://tools.ietf.org/html/rfc6750#section-3.1"`. The body is the same in both.
- **A JSON body that is too large.** One over `hrsched.web.max-json-document-bytes` (1048576 bytes
  by default) answers `413` titled `Request too large`, before any use case runs. Its detail repeats
  nothing that was sent.
- **A fraction where a whole number is bound.** A number written with a fraction part or an
  exponent, `2.0` included, sent for a headcount, a working-time threshold, an objective weight or
  contracted minutes answers `400` titled `Malformed request`. Nothing is stored, and the detail
  repeats nothing that was sent
  ([ADR-056](ADRs/ADR-056-refuse-a-fraction-where-a-whole-number-is-bound.md)).
- **A body that is not read.** JSON declared in any charset but UTF-8, UTF-16 and UTF-32 included,
  answers `415` titled `Unsupported media type` before it is read; `application/json` with no
  charset, or with `charset=UTF-8`, is accepted. A multipart request, any other type a route does
  not take, and a `Content-Type` whose charset does not parse, answer `415` titled
  `Unsupported media type` without being read.
- **What Spring refuses before a route decides anything.** A method the path does not take answers
  `405` titled `Method not allowed`, with `Allow` naming the methods it does. An `Accept` the route
  cannot answer in answers `406` titled `Not acceptable`, written as `application/problem+json`
  whatever was accepted, an `Accept` that does not parse included. A path no route maps, inside a
  family the chain admits the caller to, answers the same `404` as any absence. None of the details
  repeats the method, the type or the path sent, and these hold on every path, `/actuator` included
  ([#275](https://github.com/NaimElijah/HrSched/issues/275)).
- **Any rule a draft breaks.** Publication answers `422` titled `Roster breaks the rules`, with a
  `violations` list naming every breach: `overlapping work`, `required qualifications`,
  `declared unavailability`, `not of this organisation`, or the working-time rule broken. Each
  names the employee, and its `detail` names shifts and rosters by identifier only.
- **A shift the rules cannot check.** Publication answers `422` titled
  `Roster cannot be checked against the rules`.
- **A concurrent publication.** An overlapping roster published at the same moment answers `409`,
  titled `Published concurrently`.
- **A rules revision that lost a race.** Two revisions of one organisation's rules made at the same
  moment both take the next version; the later answers `409` titled `Revised concurrently`, and
  nothing of it is kept. Reload the rules and revise them again
  ([#238](https://github.com/NaimElijah/HrSched/issues/238)).
- **An assignment the roster already holds.** Assigning somebody to a shift they are already on,
  as a second press of the same button does, answers `409` titled `Already assigned`. Reload the
  roster: the first press was stored. The detail names neither the shift nor the person.
- **Unavailability overlapping a declared span.** Declaring unavailability that overlaps a span the
  employee already declared answers `409` titled `Overlaps a declared span`, and the detail names
  neither span.
- **A roster whose status forbids the change.** Editing or publishing a roster that is no longer a
  draft, or asking for a swap against one that is not the published roster, answers `409` titled
  `Roster status forbids this`. Reload it: somebody most likely published it first.
- **A value the store cannot hold.** Declaring unavailability or stating a preference with an
  instant outside what PostgreSQL stores, or a span that is no longer positive once stored at the
  microsecond, or setting contracted minutes too large for a duration, answers `400` titled
  `Invalid request`. Nothing is stored, and the detail repeats no value sent.
- **A shift or generation the store or generation cannot take.** Defining a shift, or starting a
  generation, with an instant outside what PostgreSQL stores, or a span that is no longer positive
  once stored at the microsecond; a shift or generation span too long for generation to count in
  nanoseconds, about 292 years; listing shifts (`GET /api/shifts`) with a `from` or `to` outside
  what PostgreSQL stores; or setting a working-time value in `PUT /api/constraints` too many
  minutes to count in nanoseconds. Each answers `400` titled `Invalid request`. Nothing is stored,
  and the detail repeats no value sent; for a working-time value it names the field.
- **A shift nobody could fill.** Defining a shift that requires fewer than one person, or a
  qualification the organisation has not defined, answers `400` titled `Invalid request`. The detail
  repeats neither the number nor the identifiers sent.
- **A span that ends at or before it starts.** On any route that takes a span, the shared time
  range answers `400` titled `Invalid request`, with a constant detail that names neither instant.
- **A sign-in name that cannot be one, or is taken.** Creating an account, or onboarding an
  organisation with its first Admin, under a blank sign-in name, one over the permitted length, or
  one carrying a NUL character or an unpaired surrogate answers `400` titled `Invalid request`;
  under a name any account already holds, on any organisation, `409`. Neither detail repeats the
  name. Signing in under a name that cannot be one is refused as any unknown name is.
- **An organisation name that cannot be one.** Onboarding an organisation under a blank name, or
  one carrying a NUL character or an unpaired surrogate, answers `400` titled `Invalid request`,
  stores nothing, and the detail repeats no name.
- **An employee or qualification name the store cannot hold.** Adding an employee
  (`POST /api/employees`) or defining a qualification (`POST /api/qualifications`) under a name
  carrying a NUL character or half of a surrogate pair, or one longer than 2,000 bytes of UTF-8,
  answers `400` titled `Invalid request`, stores nothing, and the detail repeats no name. The limit
  is in bytes, not characters, so a name in a script needing two or three bytes a character reaches
  it sooner.
- **A password the policy refuses.** A temporary password chosen when onboarding an organisation,
  creating an account or resetting one, and a new password chosen at `POST /api/auth/password`,
  answer `422` titled `Password not accepted`. The detail says what would satisfy the policy, such
  as the fewest characters a password may have, and `reason` names the rule broken: `TOO_SHORT`,
  `TOO_LONG` or, for a change of one's own password only, `SAME_AS_CURRENT`. Neither repeats the
  password.
- **An Employee account linked to nobody.** Creating one, taking the link away from one, giving
  the Employee role to an account linked to nobody, or restoring one stored unlinked before R34
  answers `422` titled `Employee account not linked`.
