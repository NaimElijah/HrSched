# ADR-048: Keep one browser's tabs on one session, over a channel that carries two words

- **Status:** Accepted
- **Date:** 2026-10-27

This extends [ADR-028](ADR-028-keep-the-session-in-memory-and-a-rotating-refresh-cookie.md). It
supersedes nothing: ADR-028 stays Accepted, and its split between an in-memory access token and a
refresh cookie is the premise here.

## Context

ADR-028 keeps the access token in each tab's JavaScript memory and the refresh token in one cookie.
Memory is per tab; the cookie jar is per browser. So a browser holds **one refresh cookie, whichever
account signed in last, in any tab**, while each tab holds its own token and its own idea of who is
signed in. ADR-028 serialises renewals across tabs with Web Locks so that two tabs do not trip reuse
detection, and says nothing about what one tab's sign-out means to the others.

The full-system security scan ([#250](https://github.com/NaimElijah/HrSched/issues/250)) found three
failures that follow from that gap:

- **A sign-out in one tab left every other tab showing personal data.** Names, absences and
  contracted hours already drawn stayed on screen until that tab's next request failed, for whoever
  sat down at the computer next.
- **A renewal could adopt another account's session.** A tab whose token was refused renewed with the
  shared cookie, received a session for whichever account had signed in last, and went on acting as
  that account while the shell still named the old one. What it did was recorded under the wrong
  person.
- **A sign-out the server never confirmed looked like a successful one.** The backend clears the
  refresh cookie and revokes the session only when the sign-out reaches it. After a gateway error,
  an unreachable backend or a tab closed straight after the click, the tab had already forgotten its
  token and offered the sign-in form, so a person on a shared computer walked away believing they
  had signed out, and the next reload restored their session.

Constraints that shape the answer:

- ADR-028 forbids `localStorage`, `sessionStorage` and any cookie the page writes for the token, and
  nothing about the session may be readable by a script beyond the tab's memory.
- The browser has no channel from the server to a tab; the API is request and response.
- A message between tabs is itself a leak if it names a person or carries a credential.
- The test suite runs in Node, which has a `BroadcastChannel` of its own.

## Decision

**A browser holds one session, and its tabs follow it.** The tabs tell each other over a
`BroadcastChannel` named `hrsched.session`, defined in `frontend/src/api/session.ts`.

- **The channel carries two words and nothing else:** `'sign-out-unconfirmed'`, posted as a sign-out
  is sent, and `'ended'`, posted once the sign-out is confirmed or a renewal is refused. Never a
  token, never who was signed in. A tab matches both strictly and ignores any other message.
- **A tab that hears either word forgets its session, and does not post again.** Two tabs answering
  each other would never stop.
- **A sign-out forgets everywhere before the request is sent.** `beginSignOut` clears this tab and
  posts `'sign-out-unconfirmed'`. The sign-out request is credentialed by the cookie and carries no
  bearer token (ADR-028), so nothing needs the token by then. The request is sent with `keepalive`,
  so a tab closing straight after the click does not abort it.
- **Every tab shows the not-confirmed warning until `'ended'`.** The sign-in form is not offered
  while the cookie may still be live. `'ended'` follows only when the backend confirms the sign-out;
  a failed or unanswered one leaves every tab on the warning, with a button that sends it again.
- **A request remembers the account it was sent as.** `request()` notes `sentAs` before sending, and
  `refreshOnce(expected)` judges the renewal against it, not against whatever the tab holds when the
  answer arrives. A request sent as nobody is not renewed. Requests refused together still share one
  renewal and are refused together.
- **A renewal that answers for any other account is refused, and forgets only in this tab.** The
  other account's session is live, so nothing is posted: `'ended'` would sign it out of every tab it
  is using. A renewal for the right account is not held if this tab's session ended while it was out.
- **Without `BroadcastChannel`, a sign-out stays in its own tab,** as it did before. The app is
  degraded, not broken.

## Consequences

**Easier**

- **No tab shows the plain sign-in form while the cookie may be live.** A sign-out nobody confirmed
  is reported as exactly that, in every tab.
- **A tab never acts as an account its shell does not name.** The mismatch is refused where it is
  found.
- **Nothing sensitive crosses the channel.** A script that listens learns that a session ended, and
  not whose.

**Harder**

- **Another account's tabs drop to the sign-in form only through their own renewals.** A mismatch
  forgets in the tab that found it and tells no one, because the account the cookie now belongs to
  is live and `'ended'` would sign it out of every tab. Each of the other tabs learns it only when
  its own renewal is refused.
- **A retry after another tab signed in again revokes that tab's session.** The sign-out is
  credentialed by the cookie alone, so once the cookie belongs to the newer sign-in, that is what a
  late retry ends.
- **The suite depends on how Vitest isolates files.** Node delivers a `BroadcastChannel` message
  across worker threads, so under a `threads` pool one test file's `endSession` would end sessions
  in the files running beside it. `vite.config.ts` pins `pool: 'forks'` for that reason; changing it
  for speed makes the suite nondeterministic.
- **The protection is only as wide as the browser's support.** Where there is no `BroadcastChannel`,
  the first failure above remains for the other tabs.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **`storage` events** | They fire only for `localStorage` writes, which ADR-028 forbids for anything the session touches. **Revisit** only by superseding ADR-028. |
| **Server push** | There is no channel from the server to the browser. **Revisit** if the application gains one for another reason. |
| **Per-tab sessions** | The cookie jar is shared, so one browser has one refresh cookie whatever the tabs hold; tabs cannot be given sessions the cookie does not have. **Revisit** if per-tab sessions become a requirement. |
| **Tell the other tabs only once the sign-out is answered** | The first version (`5ce525d`). A tab closed after the click, or a sign-out that never answered, left the other tabs showing what they had loaded; and `'ended'` after a failed sign-out put them on the sign-in form while the cookie was live. |
| **Adopt whichever account a renewal answers for** | Leaves a tab acting as an account its shell does not name, which is the second failure above. A restore with nothing held still adopts whoever the cookie belongs to, because it asks whose the cookie is. |
| **Do nothing** | It is what the scan found. |

**Revisit when** per-tab sessions become a requirement, or when a browser target lacks
`BroadcastChannel` and the per-tab fallback is no longer acceptable.
