# ADR-013: Build the frontend on React, TypeScript and Vite, talking to the backend over a dev proxy

- **Status:** Accepted
  - **Amended 2026-10-30:** The counts have moved: the API is larger than "14 endpoints" and the
    UI than a "six-screen" one. `architecture.md` lists the screens, and `frontend/src/App.tsx`
    mounts them.
- **Date:** 2026-08-30

## Context

[ADR-007](ADR-007-monorepo-backend-and-frontend.md) chose a `backend/` + `frontend/` monorepo and
deliberately did **not** create `frontend/`, because "an empty directory with a placeholder file
inside is scaffolding that drifts". It said the framework choice would get its own ADR. This is it.

What the UI has to do is unusual in one respect. HrSched's central claim is that a roster breaking
the working-time rules **cannot** be published and there is no override. A UI that renders that
refusal as a dismissible error toast would undermine the product it is presenting. The same is true
of the missing authentication: the acting identity is claimed in a header and believed, and an
interface that looked like a normal signed-in application would be lying about that.

## Decision

**React 19 with TypeScript, built by Vite.** TypeScript is the load-bearing half of that choice: the
backend DTOs are Java records that change shape as use cases land, and a typed client means a
contract drift is a compile error in the module that mirrors it rather than a runtime surprise in a
page.

**The Vite dev server proxies `/api` to `localhost:8080`.** The browser therefore only ever talks to
one origin, which is why **there is no CORS configuration anywhere in this repository**. With a
proxy there is no cross-origin request to permit. Adding CORS to the backend would mean the backend
knowing the UI's deployment shape, which is exactly the coupling ADR-007 avoided by keeping the two
builds independent.

**`src/api/client.ts` is the only module that calls `fetch` or knows a header exists.** Every page
goes through a typed module above it. That is what makes deleting the identity headers, when
ADR-011 lands, one edit rather than a search.

**Failures are typed, not stringly handled.** `ApiError` carries the status and the parsed
violations, and the untyped body is *validated* at that boundary rather than cast — a `violations`
entry that is not the expected shape is discarded rather than waved through.

**No routing library.** Navigation is local state. Nothing needs a shareable URL yet, and a router
would be a dependency bought against a requirement nobody has stated. **Revisit** the first time
somebody needs to send a colleague a link to a specific roster.

**No mocking library.** `fetch` is stubbed directly in tests. The HTTP API is our own port, and our
own ports are the case where a fake is correct.

## Consequences

**Easier**

- The API contract is checked at compile time in one place per context, so a backend DTO change
  surfaces as a type error rather than as an empty table.
- The refusal path is first-class. `Failure` distinguishes a rule breach from a role refusal from a
  cross-tenant 404, each with the explanation that case actually deserves.
- The two builds stay independent, so the backend jar is unchanged by the existence of a UI.

**Harder**

- **A second toolchain, a second ecosystem, and a second CI job.** Node, npm and their lockfile now
  gate merges alongside Maven, and Dependabot needs its own npm entry or the frontend silently goes
  unpatched.
- **Nothing automated proves the UI and the API agree.** The typed client is hand-written from the
  Java, so it can drift and only a human would notice. No contract test and no browser test exist
  here — that is a second heavyweight dependency and its own decision. **Revisit** the first time
  that drift actually bites.
- **The versions in use are ahead of most published guidance.** React 19, Vite 8, TypeScript 6,
  ESLint 10 and Vitest 4 all changed things that older material still describes: Vitest 4 moved the
  `test` key off Vite's config type, `eslint-plugin-react-hooks` 7 exposes flat config under
  `configs.flat`, and TS 6 defaults `verbatimModuleSyntax` and `noUncheckedSideEffectImports` on.
  Every one of those was found by running the tool, not by recall, and the same discipline the Boot
  4.x renames forced on the backend applies here.
- **The identity switcher is UI that exists to be deleted.** It is deliberately prominent, and it
  will look strange to anyone who has not read why.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **Server-rendered Thymeleaf in the backend** | One deployable, one build, one language, and no npm. Rejected because it contradicts ADR-007's sibling `frontend/` — that ADR would need superseding — and because it puts view state inside the hexagon's adapter layer, where the architecture rules would permit it but the design does not want it. |
| **Svelte** | Less boilerplate and a smaller bundle. Rejected on ecosystem and hiring pool alone; nothing about this UI plays to Svelte's strengths in a way that outweighs React being the default everybody already knows. |
| **Angular** | Batteries-included, and an HTTP interceptor is a natural home for the identity headers. Rejected as far more framework than a six-screen UI needs, and its structure would dominate the codebase rather than sit under it. |
| **CORS on the backend instead of a dev proxy** | Fewer moving parts in development. Rejected because it makes the backend aware of where the UI is served from, which is deployment knowledge leaking into the API, and it is configuration that only exists to work around a problem the proxy removes entirely. |
| **Generate the client from an OpenAPI document** | Would remove the drift risk named above, and is the honest answer to it. Rejected *for now* because the backend publishes no OpenAPI document, adding springdoc is its own dependency decision, and the API is currently 14 endpoints one person maintains. **Revisit** when the API outgrows one person's head, or at the first drift bug. |
