# ADR-056: Refuse a fraction where a whole number is bound

- **Status:** Accepted
- **Date:** 2026-10-30

## Context

Every whole-number field the API takes is bound to an `int`, `Integer` or `long` in a request
record: a shift's `requiredHeadcount`, the four working-time thresholds of `PUT /api/constraints`,
the three objective weights of `PUT /api/objectives`, and an employee's `weeklyContractedMinutes`.
R40 requires a weight to be a whole number, and the others are whole numbers because their request
types say so.

Jackson 3, as Spring Boot 4 configures it, has `DeserializationFeature.ACCEPT_FLOAT_AS_INT` on. A
floating-point token sent for any of those fields was truncated toward zero and bound without
complaint ([#237](https://github.com/NaimElijah/HrSched/issues/237)):

- `PUT /api/objectives {"fairness":2.5}` answered 200 and stored 2, a number nobody sent;
- a headcount of 0.5 became 0, and was then refused for being under one, which is the wrong
  reason;
- nothing in a response said the value had changed.

The SPA cannot send a fraction: its inputs carry `step="1"`, and `JSON.stringify(2.0)` writes `2`.
A direct API caller can, and so can a client in a language whose serialiser writes a float-typed
whole number with a fraction part, such as Python's `json.dumps(480.0)`, which writes `480.0`.

Jackson can be told to refuse in three places: globally, through the feature; per field, through
an annotation or a coercion setting; or by a deserializer of our own.

## Decision

**We turn `ACCEPT_FLOAT_AS_INT` off for the application's mapper**, with
`spring.jackson.deserialization.accept-float-as-int=false` in `application.properties`.

Jackson then refuses any floating-point token where a whole number is bound. It reads the token,
not its value, so a number written with a fraction part or an exponent is refused even when its
value is whole: `2.5`, but also `2.0` and `2e3`. The refusal is an
`HttpMessageNotReadableException`, which the handler of every context that takes a body already
answers as a 400 titled `Malformed request`, whose detail repeats nothing sent (ADR-024). Nothing
is stored, because the body never reaches a use case.

This holds for every whole-number field at once, including any added later, because it is a
property of the mapper rather than of a field.

## Consequences

**Easier.**

- A number nobody sent can no longer be stored. A caller who sends `2.5` is told the body is
  malformed, and fixes it, instead of finding `2` later.
- A new whole-number field is protected without anybody remembering to protect it.
- `WholeNumberFieldHttpTest` pins the refusal for every field family, with a control that sends the
  same body with a whole number and is accepted.

**Harder.**

- **A whole number written as a float is refused too.** `{"fairness":2.0}` is a 400, though its
  value is whole. A client whose serialiser writes integers that way has to send them as integers.
  This is the strict reading, chosen on purpose: it is one rule a caller can learn from a single
  refusal, and the server never has to decide which floats are close enough to whole.
- **The detail does not say which field was wrong.** It is the same fixed sentence every malformed
  body gets, because Jackson's own message quotes the input (ADR-024).
- **It covers only what Jackson binds.** A whole number in a path or a query parameter goes through
  Spring's conversion instead, which this setting does not touch.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **Refuse per field**, with an annotation or a coercion setting on each whole-number field | Protects only the fields somebody remembered to annotate. The four families here already span two contexts, and the next field added would be open until somebody noticed. The global setting fails closed. |
| **A custom deserializer that accepts an integral float**, so `2.0` binds as 2 and `2.5` is refused | Accepts more of what a client might send, at the cost of code of our own in the read path of every whole-number field, and of a rule about which floats count as whole (`2.0000000000000001` parses as `2.0`). No client sends integral floats today. |
| **Leave coercion on**, and check for a fraction in each use case | Too late: by the time a use case runs, the fraction is gone. The binder truncates it before anything of ours sees the value. |

**Revisit** when a first-party or partner client has to serialise integers as floats. Then the
custom deserializer accepting integral floats becomes worth its code.
