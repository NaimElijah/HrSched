# ADR-005: Keep backend and frontend in one repository, as sibling directories

- **Status:** Accepted
- **Date:** 2026-08-29

## Context

Until now the repository root *was* the Maven project: `pom.xml`, `mvnw` and `src/` sat at the
top level. That is the right shape for a repository containing exactly one buildable thing.

HrSched needs a user interface — a scheduler dragging shifts around is the entire point of the
product — and the current layout leaves nowhere honest to put one. Deciding where it goes is
cheapest now, while moving the backend is a single `git mv` of five paths.

The forces:

- **The API and the UI will change together, constantly, for a long time.** Every new use case
  means a new endpoint and a new screen. Anything that makes those land in two separate reviews
  will be paid for daily.
- **One developer.** Coordination costs between repositories are pure overhead here; there is no
  second team whose release cadence needs isolating.
- **No deployment target is chosen** (Q5 is open), so the layout must not quietly decide how the
  system is packaged or shipped.

## Decision

One repository, with `backend/` and `frontend/` as sibling directories and independent builds.

```
HrSched/
├── backend/     pom.xml, mvnw, .mvn/, src/
├── frontend/    created when a framework is chosen
├── docs/
├── LICENSE
└── README.md
```

`docs/`, `LICENSE` and `README.md` stay at the root: they describe the whole system, not one
half of it.

**`frontend/` is not created yet.** An empty directory with a placeholder file inside is
scaffolding that drifts. It appears when a framework is chosen, and that choice gets its own
ADR.

**ADR-002 is extended, not superseded.** Its decision — that `./mvnw verify` is the entire
gate, and that CI runs exactly the command a developer runs — still holds unchanged. Only the
working directory moves. CI uses a job-level `defaults.run.working-directory: backend`, and
`setup-java` needs `cache-dependency-path: backend/pom.xml` because it runs outside that
default.

## Consequences

**Easier**

- An API change and the UI change that consumes it land in one commit, one review, one CI run.
- The backend can still be built and shipped entirely on its own; nothing about this layout
  couples the two artefacts.
- Splitting into two repositories later is a `git filter-repo` away, and the history survives.

**Harder**

- **Every backend command grows a `cd`.** `cd backend && ./mvnw verify`. The README, CI and
  `CLAUDE.md` all had to change together, and anyone with the old command in muscle memory gets
  a confusing error.
- **Dependabot needs the path spelled out** (`directory: /backend` for Maven). A second
  ecosystem for the frontend will need its own entry; forgetting it means silent non-coverage.
- **CI will grow.** A single job runs the backend today; a frontend means either a second job or
  path filters, and getting path filters wrong silently skips checks.
- The repository root no longer builds anything, which surprises tools that assume it does.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **Separate repositories** | The cleanest isolation, and correct once the two halves have different owners or release cadences. Rejected because every API change would span two repositories and two pull requests while the API is changing daily. **Revisit when** the frontend has a genuinely independent release cadence, or a second person owns one side. |
| **Frontend inside the Maven build** (`src/main/frontend`, bundled into the JAR by `frontend-maven-plugin`) | One artefact to deploy, which is attractive with no deployment target chosen. Rejected because every backend build would pay the frontend build cost, and frontend tooling fights being driven by Maven. **Revisit if** single-artefact deployment becomes a hard requirement. |
| **Server-rendered UI, no separate frontend** (Thymeleaf or HTMX as another inbound adapter) | Genuinely simpler: no second toolchain, no CORS, one deployable, and it fits the hexagon as just another driving adapter. Rejected because a roster is a drag-and-drop, densely interactive screen, which is where server-rendered approaches get expensive. **Revisit if** the UI turns out to be mostly forms and tables. |
| **Leave the backend at the root and nest the frontend under it** | No move required today. Rejected because it implies the frontend is part of the backend project, which is exactly the coupling to avoid. |
