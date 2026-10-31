# ADR-058: Refuse a request member the request type does not have

- **Status:** Accepted
- **Date:** 2026-10-31

## Context

Every request body is bound to a record in a context's web adapter, and the record's components
are the whole of what the route takes. Spring Boot 4 configures Jackson 3 with
`DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES` off, so a member the record does not have was
dropped unread, and the request went on as if it had never been sent
([#256](https://github.com/NaimElijah/HrSched/issues/256)).

That is harmless only until the member is a misspelling, and two routes replace a whole set:

- `PUT /api/constraints` stores a new version of the rules, and a working-time value left out
  means that rule does not apply. A body with `maximumConsecutiveDay` for `maximumConsecutiveDays`
  answered 200 and stored a set with that rule switched off.
- `PUT /api/objectives` replaces the three weights the same way, so a misspelt weight stopped that
  objective being weighed, with a 200.

Nothing after the binder can tell: by the time a use case runs, the member is gone, and an absent
value is a legal one on both routes.

The same tolerance answered a mass-assignment attempt as a success. A body naming an organisation
on `POST /api/accounts` created the account in the acting Admin's own organisation, because the
organisation comes from the account and the record has no such member
([ADR-031](ADR-031-take-the-organisation-from-the-account.md)). The record's members are what keep
the organisation out of the caller's hands; the tolerant reader only made the attempt look the same
as an ordinary request, so the caller learned nothing and nothing set it apart.

The SPA ships with the backend today: one repository, one release, and every body it sends carries
only its record's members.

## Decision

**We refuse a member the request type does not have, on every request body**, with
`spring.jackson.deserialization.fail-on-unknown-properties=true` in `application.properties`. Boot
turns the feature off before it applies the property, so the property wins.

Jackson then throws `UnrecognizedPropertyException` for such a member, which Spring wraps in an
`HttpMessageNotReadableException`. The handler of every context that takes a body answers it as a
400 titled `Malformed request`, with the same constant detail every malformed body gets: it names
neither the member nor anything else sent, because Jackson's own message quotes both
([ADR-024](ADR-024-answer-unbindable-requests-with-a-problem-detail.md)). Nothing is stored,
because the body never reaches a use case.

It is global for the reason [ADR-056](ADR-056-refuse-a-fraction-where-a-whole-number-is-bound.md)
gives for fractions: a request record added later is covered without anybody remembering it.

## Consequences

**Easier.**

- A misspelt rule or weight is refused, and the caller fixes it, instead of finding the rule
  switched off later: `UnknownMemberHttpTest`, with a control sending the same body spelt right.
- A body naming an organisation is refused whole, so no account is created anywhere:
  `AccountHttpTest.GivenAnAdminNamingAnotherOrganisationInTheBody_WhenCreatingAnAccount_ThenItIsMalformedAndNoAccountExists`.
- A request's contract is exactly its record. A member the server does not take cannot be sent by
  mistake and believed to have worked.

**Harder.**

- **A new request member ships server-first.** A client may not send a member until the server
  that reads it is deployed, so a change adding one lands in the backend before any client sends
  it. With the SPA released beside the backend this costs nothing; it is what the revisit trigger
  below watches.
- **A 400 replaces a 403 where binding precedes the role check.** The body is bound before the use
  case runs, so a caller whose role the use case would refuse, and whose body also carries an
  unknown member, is answered as malformed, never reaching the refusal of their role.
- **A client may not echo a response back as a request.** A read answers members a write does not
  take, such as a rule set's `version`, so a client that sends back what it read is refused.
- **The detail does not say which member was wrong.** It is the same sentence every malformed body
  gets, for the reason ADR-024 gives.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **Refuse only on the set-replacing records**, with `@JsonIgnoreProperties(ignoreUnknown = false)` on `ConstraintSetRequest` and `ObjectiveWeightsRequest` | Protects only the records somebody remembered to annotate. The next record replacing a set, or the next field whose absence means something, would be open until somebody noticed, and a body naming an organisation would still be answered as a success. The global setting fails closed. |
| **Keep the tolerant reader**, and let a client send members the server ignores | Lets a client and the server evolve apart, which nothing here needs while they ship together. It costs a rule switched off with a 200 on every typo, and no later check can catch it: the member is gone before any code of ours runs. |

**Revisit when** the SPA and the API deploy independently, for example a rolling deploy in which a
new SPA can reach a task still running the old API; or when a second client exists that does not
ship with the backend. Either makes server-first ordering a constraint on somebody else's release,
and a tolerant reader on additive members, kept strict on the set-replacing records, becomes worth
weighing again.
