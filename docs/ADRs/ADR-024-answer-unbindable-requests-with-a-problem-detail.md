# ADR-024: Answer a request Spring cannot bind with a problem detail, from one fallback handler

- **Status:** Accepted
  - **Amended 2026-10-30:** `ActorFromHeaders`, which the text says gives a blank header the
    "Invalid request" title, no longer exists: the actor comes from the access token
    ([ADR-011](ADR-011-issue-our-own-access-tokens.md)), so there is no identity header to be
    blank. `RequestBindingExceptionHandler` keeps the title.
  - **Amended 2026-10-30:** "Nothing in this repository sends" a 415 no longer holds:
    [ADR-053](ADR-053-bound-a-request-body-and-accept-only-utf-8-json.md) refuses JSON declared in
    a charset other than UTF-8 with a 415, and the problem detail is written by
    `JsonCharsetFilter`. That is the only 415 this repository sends. A multipart body also gets a
    415, but it is Spring's default, with Spring's default body.
  - **Amended 2026-10-31:** every problem detail now carries a `type` of its own, and the 400 this
    handler writes is `urn:hrsched:problem:invalid-request`; the type is no longer `about:blank`.
    See [ADR-057](ADR-057-name-every-kind-of-problem-with-a-urn.md).
  - **Amended 2026-10-31:** the consequence that 405, 415 and an unmapped path answer with
    Spring's default body, and the alternative of answering them, are overtaken (issue #275).
    `RequestRoutingExceptionHandler`, in `configuration.web`, answers
    `HttpRequestMethodNotSupportedException` (405), `HttpMediaTypeNotAcceptableException` (406),
    `HttpMediaTypeNotSupportedException` (415, a multipart body included) and
    `NoResourceFoundException` (404) with a problem detail whose detail repeats nothing sent. It is
    unscoped, because a request refused while a handler is being chosen has no handler and a scoped
    advice is never consulted for it, so it answers on every path, actuator's included. It handles
    those four types and nothing else, and no other advice handles any of them, which keeps this
    record's rule that no two advices claim one type. `spring.mvc.problemdetails.enabled` was
    rejected again for the reason above, and a `ResponseEntityExceptionHandler` subclass of our own
    for the same one: its single handler method claims `HttpMessageNotReadableException` and the
    binding failures as well, and cannot be narrowed. What is still not a problem detail is listed
    in `docs/api.md`.
- **Date:** 2026-09-14

## Context

Every refusal the application decides is answered as an RFC 9457 problem detail by one of four
per-context `@RestControllerAdvice` classes, each scoped with `assignableTypes` to its own
controllers. The user interface depends on that shape: `problem.ts` reads `detail` and shows it.

Some requests never reach a decision. A missing required header, a missing `from` or `to` on
`GET /api/shifts`, or a path identifier that is not a UUID fails while Spring binds the handler's
parameters, as `MissingRequestHeaderException`, `MissingServletRequestParameterException` or
`MethodArgumentTypeMismatchException`. None of the four advices handles those, so they fell through
to Spring's default resolution. Observed under MockMvc before this change: status 400, no content
type, no body. The UI could only render that as "The server returned 400." Three HTTP tests —
`KnownOrganisationHttpTest`, `RosteringHttpTest` and `NotificationHttpTest` — asserted the status of
exactly these cases and never the body, which is how it went unnoticed.

Boot 4.1.1 has a switch for this, `spring.mvc.problemdetails.enabled`. It registers
`ProblemDetailsExceptionHandler`, a `ResponseEntityExceptionHandler`, from a bean method annotated
`@Order(0)` (read with `javap` from `spring-boot-webmvc-4.1.1.jar`). That handler also claims
`HttpMessageNotReadableException`, which the context advices already answer with the title
"Malformed request", and at order 0 it would be consulted before them, which carry no order.

## Decision

**We answer those three binding failures from one `@RestControllerAdvice`,
`RequestBindingExceptionHandler`, in `configuration.web`.** It returns a 400 problem detail titled
"Invalid request", the title `ActorFromHeaders` already gives a blank header, so an absent header
and an empty one read the same.

**It names the parameter and its detail never repeats the value.** The detail is
`X-User is required` or `from could not be read as Instant`. What the caller sent is not echoed in
anything the server says about it, for the reason ADR-020's refusal echoes nothing. The `instance`
member is another matter: Spring sets it to the request path on every problem detail it writes in
this application, so a malformed path variable does appear there — as the address the caller asked
for, which is not a statement about it. A query string is not part of that path.

**It handles the three leaf types, not their parents.** `MissingRequestValueException` also covers
`MissingPathVariableException`, and `TypeMismatchException` also covers
`ConversionNotSupportedException` (both read with `javap` from spring-web 7.0.9). Those two mean a
mapping of ours is wrong, which Spring answers with a 500. Catching the parent would report our
defect to the caller as their malformed request.

**It is scoped with `basePackageClasses` to the application's own controllers**, so actuator's
endpoints answer exactly as they did.

**It records nothing.** Like the malformed-input handlers in each context, it neither logs nor
counts: a request that could not be bound is not a refusal of anything the application decided
(ADR-018).

## Consequences

**Easier**

- Every 400 the API's own controllers produce now carries a `detail`, and that detail says which
  parameter was wrong.
- A concern that belongs to no context is answered in one place, the same shape
  `KnownOrganisationFilter` already has.

**Harder**

- It relies on the three types not overlapping anything a context advice handles. Nothing orders
  the advices, because today nothing needs to; if a context advice ever handled one of these types
  too, which of them answered would depend on bean registration order rather than on anything
  declared.
- Some framework failures still answer with Spring's default body: 405 Method Not Allowed, 415
  Unsupported Media Type, and 404 for a path no controller maps. Nothing in this repository sends
  them.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **`spring.mvc.problemdetails.enabled=true`** | One line, and it covers 405 and 415 as well. Rejected because its handler is registered at `@Order(0)` and handles `HttpMessageNotReadableException`, pre-empting the context advices that title that failure "Malformed request", a title `EmployeeHttpTest` asserts. **Revisit if** the per-context unreadable-body handlers are ever unified, when a subclass of Boot's handler could replace both. |
| **Catch the parents, `ServletRequestBindingException` and `TypeMismatchException`** | Fewer types to list. Rejected: the parents include `MissingPathVariableException` and `ConversionNotSupportedException`, which mean our own mapping is wrong, and turning a server defect into a 400 hides it. |
| **A copy of the three handlers in each context's advice** | Keeps each context's HTTP answers in one class. Rejected: four identical copies of a rule that has nothing to do with any context, and a fifth context would have to remember to add a fifth. |
| **Leave it** | Nothing was broken in the status. Rejected because the body is the part a caller shows a person, and "The server returned 400" tells them nothing they can fix. |
| **Also answer 405, 415 and unmapped paths** | Completeness. Rejected for now: nothing here sends those, and this handler's value is in being narrow. **Revisit when** a caller other than our own UI depends on the API's error shape. |
