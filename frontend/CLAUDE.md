<!--
Maintainers: this file loads the first time a file under frontend/ is read or edited, on top of
.claude/CLAUDE.md, which every session already has. It holds what only frontend work needs. Keep
it under about 60 lines; the commands stay in .claude/CLAUDE.md because running one reads no
file. Prettier checks this file as part of npm run verify. This comment is stripped before Claude
sees the file.
-->

# frontend/CLAUDE.md

## Layout

`frontend/` is a React 19 SPA built by Vite (ADR-013):

- `src/api/` — the typed client. `client.ts` is the **only** module that calls `fetch` or names a
  header. Every request carries the bearer token except refresh and sign-out, whose credential is
  the refresh cookie plus `X-Requested-With` (ADR-028). `session.ts` holds the access token in
  memory; `problem.ts` validates the error body rather than casting it; `scheduling.ts`,
  `workforce.ts`, `access.ts` and `notifications.ts` mirror the backend DTOs, one module per
  context.
- `src/auth/` — `SessionProvider` and the sign-in, change-password and session-bar screens
  (ADR-011, ADR-028).
- `src/pages/` — one screen per capability. Pages never call `fetch` directly.
- `src/components/` — what several pages share: `ErrorBoundary`, `Feedback`, `useAsync`,
  `localDates`.
- `src/test-support/` — `routeTo` replaces `fetch` with a router that answers anything unmatched
  with a 404, and `renderSignedIn` renders a screen as somebody already signed in.

## Conventions

- **The UI enforces nothing, and must not pretend to.** Every rule is the backend's. A screen that
  looks like it satisfies a requirement is not evidence the rule holds, which is why the
  `Enforced by` column in `requirements.md` names only backend classes and tests.
- **Never put a suggested working-time number in a form field.** A default is a legal threshold
  chosen by a developer — the statute rule in `.claude/CLAUDE.md` covers the UI too.
- **Frontend versions are ahead of most published guidance.** React 19, Vite 8, TypeScript 6,
  ESLint 10, Vitest 5. Check the installed package before trusting a recalled API — the same
  discipline the Spring Boot 4.x renames forced. Already found this way: Vitest 4 moves the `test`
  key off Vite's config type (import `defineConfig` from `vitest/config`), and
  `eslint-plugin-react-hooks` 7 exposes flat config under `configs.flat`.
- **A frontend dependency's justification lives in ADR-051**, because `package.json` cannot hold a
  comment. A new dependency gets its own ADR: ADR-051 is append-only and is not edited to add one.
