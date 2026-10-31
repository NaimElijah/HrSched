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

- `src/api/` — the typed client. `client.ts` is the **only** module that calls `fetch` (lint
  refuses it anywhere else) or names a header. Every request carries the bearer token except
  refresh and sign-out, whose credential is the refresh cookie plus `X-Requested-With` (ADR-028).
  `session.ts` holds the access token in memory; `problem.ts` validates the error body rather than
  casting it; `scheduling.ts`, `workforce.ts`, `access.ts` and `notifications.ts` mirror the
  backend DTOs, one module per context.
- `src/auth/` — `SessionProvider` and the sign-in, change-password and session-bar screens
  (ADR-011, ADR-028).
- `src/pages/` — the screens, one `*Page` per capability, and the parts of a screen:
  `RosterBoard`, `ApprovingRules` and `ObjectiveScores` belong to `RostersPage`, `Inbox` to
  `MePage`. A helper only one screen uses stays beside it; once a second screen uses it, it moves
  to `src/components/`. Pages never call `fetch` directly.
- `src/components/` — what several screens share: `ErrorBoundary`, `Feedback`, `useAsync`,
  `localDates`, `useShiftsById`, `swapStatus`, and the wording tables `ruleFields` and
  `objectiveFields`. It imports `src/api` only. The folders depend one way,
  `api ← components ← auth ← pages`, so a shared hook that needs the session takes it from its
  caller; lint refuses an import against the direction, and production code importing
  `src/test-support/`.
- `src/test-support/` — `routeTo` replaces `fetch` with a router that answers anything unmatched
  with a 404. A `METHOD path` key answers that method at exactly that path, and a bare path only a
  GET there, so a write to the wrong endpoint is refused. `renderSignedIn` renders a screen as
  somebody already signed in, and `WITHOUT_PAUSES` types a value without a timer turn between
  keys, so a form test does not press against the timeout (#286). Tests run in
  `America/Los_Angeles`, set as `test.env.TZ` in `vite.config.ts`, so a test that builds a local
  date asserts the exact instant it means in that zone.

## Conventions

- **The UI enforces nothing, and must not pretend to.** Every rule is the backend's. A screen that
  looks like it satisfies a requirement is not evidence the rule holds, which is why the
  `Enforced by` column in `requirements.md` names only backend classes and tests.
- **Never put a suggested working-time number in a form field.** A default is a legal threshold
  chosen by a developer — the statute rule in `.claude/CLAUDE.md` covers the UI too.
- **Frontend versions are ahead of most published guidance** (React 19, Vite 8, TypeScript 6,
  ESLint 10, Vitest 5): check the installed package before trusting a recalled API. Found this
  way: `defineConfig` comes from `vitest/config`, and `eslint-plugin-react-hooks` 7 keeps its flat
  config under `configs.flat`.
- **A frontend dependency's justification is its row in `architecture.md`'s dependency table**,
  because `package.json` cannot hold a comment. Adding, replacing or removing one updates its row
  in the same commit, and a choice between alternatives gets an ADR. ADR-051 records the reasons
  for the ones adopted before 2026-10-29.

## Focus and announcements

WCAG 2.2 AA, as #257 applies it. Tests assert these through roles, names, descriptions and
`toHaveFocus()`, never through markup.

- **C1** A button in flight is `aria-disabled`, never `disabled`, which drops focus to the page;
  its handler ignores a press meanwhile (`SignOutUnconfirmedPage`).
- **C2** An action that removes the focused control moves focus to a named target: its row's
  `<th scope="row" tabIndex={-1}>`, or its section's heading.
- **C3** A successful write says so in a `role="status"` present before its text.
- **C4** A view change focuses the heading of what appeared. `App` does it for a tab, a sign-in,
  the end of the forced password change and a retried restore, and titles it `<view> · HrSched`.
- **C5** A control that cannot act yet is `aria-disabled` and described by a visible hint; a load
  that failed offers to try again.
- **C6** A refusal shows at its cause, inside its row or item; a field it is about is
  `aria-invalid` and described by the message.
- **C7** A repeated button's `aria-label` starts with its visible text, then names its item.
- **C8** A table has a `<caption>` (`visually-hidden` when a heading shows it), every header cell a
  name, an empty list says so, and a wide table sits in a focusable `.table-scroll` region.
- **C9** A success status clears as soon as its form is edited again.
