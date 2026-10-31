# ADR-059: Keep focus and announce changes in the SPA

- **Status:** Accepted
- **Date:** 2026-10-31

## Context

The accessibility scan of the SPA ([#257](https://github.com/NaimElijah/HrSched/issues/257))
checked it against WCAG 2.2 level AA, because the issue names that standard.
[ADR-025](ADR-025-edit-a-draft-roster-with-controls-not-drag-and-drop.md) already asks that every
control work from a keyboard and a screen reader. Neither says how, and the scan found the same few
failures on screen after screen:

- **Nothing said a view had changed.** Navigation is local state, not a router, so a tab, a
  sign-in or the end of the forced password change replaces everything in `main` with no page load.
  The browser moves no focus and changes no title, and a screen-reader user hears nothing of what
  appeared.
- **A button in flight dropped focus.** A `disabled` button cannot hold focus, so the browser moves
  it to the page, and a keyboard user who pressed one was sent back to the top.
- **An action removed the control that had focus**, a row's Remove button or a retry, and left
  focus on nothing.
- **Success and refusal were not announced, or were not where they were about.** A live region
  inserted along with its text is not read, a refusal shown below a form says nothing at the field
  it is about, and a refusal inside a wide table could sit scrolled out of view.
- **Tables did not reflow at 320 px** and had no captions, and empty lists said nothing.

Whether WCAG 2.2 AA is a requirement of the UI, and what would prove it, is not decided:
`requirements.md` states no accessibility requirement, and the decision is the owner's
([#353](https://github.com/NaimElijah/HrSched/issues/353)). This record does not make it one. It
records how the SPA meets the level the scan applied, so that every screen meets it the same way.

## Decision

**We follow nine conventions on every screen**, C1 to C9, and test each through roles, accessible
names, descriptions and `toHaveFocus()`, never through markup. `frontend/CLAUDE.md` keeps the short
form an agent reads before frontend work.

- **C1** A button whose request is in flight is `aria-disabled`, never `disabled`, and its handler
  ignores a press meanwhile. `aria-disabled` tells a screen reader the button cannot act, as
  `disabled` does, but the button keeps focus, so the person who pressed it stays where they were.
  The cost is that the browser no longer blocks the press, so every handler guards itself, and the
  look is the stylesheet's, forced colours included.
- **C2** An action that removes the focused control moves focus to a named target: its row's
  `<th scope="row" tabIndex={-1}>`, its section's heading, or the control that takes its place (the
  shift's picker after Remove).
- **C3** A successful write says so in a `role="status"` region that is on the page before its
  text arrives, because a live region is announced when what it holds changes, not when it appears.
- **C4** A view change moves focus to the heading of what appeared, and sets the document title to
  `<view> · HrSched`. The shell does it, with the `focus` helper in `frontend/src/components`, for
  a tab, a sign-in, the end of the forced password change and a retried restore; `ErrorBoundary`
  does it after "Try again". A first load takes no focus, because nothing was pressed.
- **C5** A control that cannot act yet is `aria-disabled` and described by a visible hint saying
  why. A form that waits for stored values says so, and offers to try again when their load failed.
- **C6** A refusal shows at its cause, inside its row or item, pinned to the visible part of a
  scrolled table. A field it is about is `aria-invalid` and described by it until the next send, so
  somebody correcting it can read it again. Where focus moves to that field, the field's
  description says it and it is no alert as well, which would say it twice.
- **C7** A button repeated on every row has an `aria-label` that starts with its visible text, then
  names its item, so a voice-control user can say what they see and a screen-reader user hears
  which row it acts on.
- **C8** A table has a `<caption>`, `visually-hidden` when a heading already shows it; every header
  cell has a name; an empty list says so; and a table wider than the page sits in a focusable
  `.table-scroll` region, so at 320 px only the table scrolls, and a keyboard can scroll it.
- **C9** A success status clears as soon as the form or row it reported is edited again, because
  it is about what was sent, not what is being typed.

## Consequences

**Easier.**

- A new screen has a checklist, and a reviewer has something to check it against.
- Focus and announcements are asserted by the tests that render the screens, so a regression fails
  the gate: `App.test.tsx`, `auth/session.test.tsx`, `main.test.tsx` and each page's own tests.

**Harder.**

- **What jsdom cannot see is unchecked.** It runs no layout, applies no forced colours and reads
  nothing aloud, so reflow at 320 px, contrast, the forced-colours look of an `aria-disabled`
  control and what a real screen reader announces are checked by hand or not at all. What would
  check them is part of #353.
- **Every in-flight handler guards itself.** A handler that forgets is a double submit the browser
  would have blocked for a `disabled` button.
- **A heading takes focus it was not written for.** The `focus` helper gives it `tabIndex={-1}`, so
  a page's first `h2` in `main` is load-bearing: a view that opens with no heading leaves focus
  where it was.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **`disabled` on a button in flight** | It drops focus to the page, which is the failure C1 exists to prevent. |
| **A skip link** to the main content | WCAG 2.4.1 asks for a way to bypass blocks that repeat on every page, and the `header`, `nav` and `main` landmarks every screen has are one: a screen reader moves between landmarks directly. What repeats before `main` is the title, the session bar and one row of tabs, little for a keyboard user to tab past. |
| **A client-side router** to set titles and move focus | It is a dependency bought against a requirement nobody has stated, the reason the shell gives for having none. It would not remove the work either: titles and focus would still be this application's code, run on a route change instead of a view change. |

**Revisit the skip link when** the navigation or the planning span grows enough that landmark
navigation is not enough, such as a second row of tabs or more controls ahead of `main`, or when a
user or an audit asks for one.

**Revisit the router when** somebody needs to send a colleague a link to a specific screen or
roster, the trigger the shell already names; titles and focus would then move to the router's
route change.
