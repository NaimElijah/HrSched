# ADR-025: Edit a draft roster through controls on a board, not by drag and drop

- **Status:** Accepted
- **Date:** 2026-09-14

## Context

Assigning an employee to a shift on a draft roster, and removing that assignment, has been
possible over HTTP since PR #18 (requirement F2). No screen offered it: opening a roster showed a
read-only list of who worked when.

ADR-007's context imagines the product this way: "a scheduler dragging shifts around is the entire
point of the product". Three constraints pull against taking that literally now:

- **The user interface enforces nothing** (`architecture.md`). Whether a person may work a shift —
  qualifications, declared unavailability, overlapping work — is the backend's decision, answered
  with a 422 and its reason when the answer is no.
- **Every control must work from a keyboard and a screen reader.** A drag has neither by default.
- **A new dependency needs a justification** (the repository's own rules), and ADR-013 keeps the
  frontend to Testing Library with `fetch` stubbed, which cannot simulate a pointer drag.

## Decision

**We render a roster as a board, and a draft is changed through ordinary controls on it.**
`RosterBoard` groups the roster's shifts by local day and shows, for each shift, its times, the
qualifications it requires by name, how many people are assigned against its headcount with any
shortfall stated in words, the people on it, and — while it is a draft — a Remove button beside each
person and an Assign picker with a button. A published or superseded roster is shown with no
controls, because it is never edited (R6).

**The picker offers every employee.** It does not filter by qualification, by unavailability or by
who is already on the shift. The backend refuses an ineligible assignment and the board shows the
refusal with the server's reason.

**The board loads its own roster by identifier**, and the roster a change returns becomes what it
shows. Only the roster list reloads after a change.

## Consequences

**Easier**

- No dependency. Every control is a native `select` or `button`, so it is reachable by keyboard and
  named for a screen reader by construction, and every button's name says whom it removes from
  which shift.
- Tested through roles and labels like every other screen, with no pointer simulation.
- One refusal path: whatever the backend will not accept arrives as a 422 and is rendered with its
  reason, so a rule changed on the server needs no change here.

**Harder**

- Moving somebody from one shift to another is two actions, a removal and an assignment, and the
  draft has a gap in between. It is a draft; a gap is allowed to exist in one.
- The picker offers people the backend will refuse. A scheduler learns that somebody is ineligible
  by being told so, not by their absence from the list.
- A picker per shift grows long with a large organisation.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **Drag and drop with `@dnd-kit`** | Closest to what ADR-007 imagines. Rejected: a new dependency whose keyboard support and announcements we would have to configure and test, plus a keyboard-operable fallback anyway, so it would be both. **Revisit when** schedulers routinely edit dozens of assignments per roster and the pickers, rather than the rules, are what they complain about. |
| **Native HTML drag and drop** | No dependency. Rejected: no keyboard or touch support, so it can never be the only way to assign, and jsdom does not implement it, so it would go untested. |
| **Filter the picker to people who are eligible** | Fewer refusals. Rejected: it would be a second copy of the qualification and availability checks, written in the UI and free to drift from the backend's; and a person filtered out reads as "not an employee" rather than "not eligible for this". **Revisit if** refusals prove frequent — by asking the backend who is eligible, never by recomputing it here. |
| **Keep deriving the open roster from the roster list** | No extra request. Rejected: the list is cleared while it reloads, so a board derived from it unmounted on every change and took the keyboard focus with it. |
