# ADR-053: Bound a request body in bytes, and accept nothing but UTF-8 JSON

- **Status:** Accepted
  - **Amended 2026-10-30:** A limit above `Integer.MAX_VALUE` in
    `hrsched.web.max-json-document-bytes` fails startup too. The same value is
    `server.tomcat.max-http-form-post-size`, which Boot narrows to an `int` without checking, so a
    larger one wrapped to a negative number, which Tomcat reads as no limit, or to zero, which Boot
    skips ([#254](https://github.com/NaimElijah/HrSched/issues/254)). Neither Tomcat's key nor
    Jackson's is checked when set directly, for instance by an environment override such as
    `SERVER_TOMCAT_MAXHTTPFORMPOSTSIZE=-1`.
  - **Amended 2026-10-31:** the multipart and unsupported-type 415s no longer carry Spring's
    default body: both are problem details now, answered by `RequestRoutingExceptionHandler`
    (issue #275; see ADR-024's note of the same date). A form over the server's form limit is still
    refused `413` by the container's error page, not with a problem detail.
- **Date:** 2026-10-30

## Context

The full-system security scan (#250) found that a JSON request body had no size limit (CF2). The
fix, in #274, was followed by two more findings in the same area: a form body read ahead of the
security chain (fixed in #277), and a body whose size was counted in characters, not bytes (#278,
still open). The policy the code now has was decided in the pull requests #274 and #277, merged on
2026-10-28, and not recorded then.

Forces:

- **Jackson bounds almost nothing by default.** Its own defaults limit a single string at a hundred
  million characters and a document not at all.
- **Sign-in takes no token.** Without a limit, one unauthenticated request could make the server
  buffer whatever it was sent.
- **Jackson counts bytes only when it reads them itself.** Spring lets it do that for a Unicode
  charset alone. For any other declared charset the body is decoded first and Jackson counts
  characters, and some charsets decode many bytes into very few: ISO-2022-JP escape sequences decode
  to nothing.
- **RFC 8259 section 8.1 requires JSON exchanged between systems to be UTF-8.** No client of this
  API sends another charset.
- **Boot parsed a multipart body, and wrote its parts to disk, before any handler was chosen,** for
  an anonymous caller on a route that would then refuse it. No route takes an upload.
- **Boot's form-content filter runs ahead of the security chain.** It reads a form body sent with
  PUT, PATCH or DELETE whole, with no limit like the JSON one. No route takes form content.
- **A form posted to a route that takes JSON is parsed by the server before it is refused as the
  wrong type,** up to the server's own form limit, which is 2 MiB by default.
- **Jackson reads a maximum of zero or less as no limit at all.**
- [ADR-024](ADR-024-answer-unbindable-requests-with-a-problem-detail.md) declined to answer a 415
  itself, because nothing in the repository sent one.

## Decision

**We bound a request body in bytes before any use case runs, and accept no body type but JSON in
UTF-8.** Five properties and two classes carry it, and one number sets two of the properties.

- **A JSON document is at most 1 MiB.** `hrsched.web.max-json-document-bytes` is 1048576 and is
  handed to Jackson as `spring.jackson.factory.constraints.read.max-document-length`. Jackson counts
  a UTF-8 body in bytes and checks the whole of it, because it looks for trailing tokens after the
  root value. A body over the limit, or over one of Jackson's nesting or value-length limits, is
  refused with a 413 titled `Request too large`. The detail is a fixed sentence, because Jackson's
  message quotes the input it failed on. The access, scheduling and workforce advices answer it.
- **A limit of zero or less in `hrsched.web.max-json-document-bytes` fails startup.**
  `RequestBodyLimitProperties` binds the value first and refuses it, where Jackson would read it as
  no limit. Setting the Jackson property directly is not checked.
- **JSON declared in any charset but UTF-8 is refused with a 415 before it is read.**
  `JsonCharsetFilter` judges `application/json` and every `application/*+json`, and writes a
  problem detail titled `Unsupported media type`. It runs last, after the security chain, so a route
  the chain refuses is refused there first. It logs and counts nothing: a body in the wrong charset
  is not a refusal of anything the application decided (ADR-018).
- **Multipart is off.** `spring.servlet.multipart.enabled=false`, so a multipart body is refused
  with a 415, unread, like any type a route does not take.
- **The form-content filter is off.** `spring.mvc.formcontent.filter.enabled=false`. Nothing reads
  a form body ahead of the security chain.
- **The form limit is the JSON one.** `server.tomcat.max-http-form-post-size` takes
  `${hrsched.web.max-json-document-bytes}`. A larger form posted to a JSON route is refused with a
  413 for its size, not parsed and then refused with a 415.

**Not decided here:** the byte bound for a body that declares no charset and that Jackson detects as
UTF-16 or UTF-32. It is #278. Its two candidates are to bound the request's input stream, or to
refuse such a body with a 415.

## Consequences

**Easier**

- **No one request can make the server hold more than the limit,** signed in or not. The limit
  applies to every route, and a mebibyte is generous for every request this API takes.
- **Most of the policy is pinned by tests, not by the properties alone.** `RequestBodyLimitHttpTest`
  pins a body one byte over and a body exactly at the limit. `RequestBodyCharsetHttpTest` pins the 415
  for Latin-1, ISO-2022-JP and UTF-16. `MultipartRequestHttpTest` pins a multipart body refused
  unparsed. `RequestBodyReadingFiltersTest` pins an anonymous form body left unread.
  `FormPostLimitHttpTest` pins a form over the limit refused with a 413. `JsonDocumentLimitStartupTest`
  pins the startup refusal.
- **Four claims above are not pinned by any test:**
  - (a) a body over a nesting or value-length limit gets a 413, because every 413 test oversizes
    the document;
  - (b) tokens after the root value are checked, because every padded test body pads inside the
    root;
  - (c) the filter's 415 is titled `Unsupported media type`, because no test reads `$.title`;
  - (d) the filter logs and counts nothing.

  [#306](https://github.com/NaimElijah/HrSched/issues/306) tracks the tests.
- **A refusal for size or charset is not a failed sign-in.** Neither
  `RequestBodyLimitHttpTest.GivenABodyOneByteOverTheLimitNamingARealAccount_WhenSigningIn_ThenNoFailedAttemptIsCounted`
  nor
  `RequestBodyCharsetHttpTest.GivenABodyDeclaredInLatin1NamingARealAccount_WhenSigningIn_ThenNoFailedAttemptIsCounted`
  counts an attempt against the account named.

**Harder**

- **The byte bound has a gap.** A body that declares no charset and that Jackson detects as UTF-16
  or UTF-32 is counted in characters, so it can run to a few times the limit in bytes before it is
  refused. The heap held stays near the limit in characters, so the cost is bandwidth and decoding
  time. #278 closes it.
- **Four of these settings are Boot properties, not code.** Turning one back on fails a test only
  because the tests above exist. A Boot upgrade that moved a default would be caught the same way
  and no other.
- **No route can take an upload or a form as things stand.** A route that must is a reversal of
  two of these settings, and needs a limit of its own.
- **A request over 1 MiB is refused for everyone,** including an Admin. The number is configuration,
  and a judgement ("generous for every request this API takes"), not a measurement.
- **The 415 has a second author.** `JsonCharsetFilter` writes its own problem detail, because it
  runs outside every controller advice. ADR-024's "nothing in this repository sends them" no longer
  holds for 415. The multipart and unsupported-type 415s still carry Spring's default body.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **Keep Jackson's defaults** | A document is unbounded and a string may be a hundred million characters, on a route that takes no token. |
| **Accept other charsets and count them as Jackson does** | Jackson would count decoded characters, and ISO-2022-JP carries a body far over the limit past it. RFC 8259 allows none of them, and no client sends one. |
| **A narrower Jackson converter, reading UTF-8 only** | Spring's converter reads every charset. Narrowing it means replacing the converter Boot configures, for every route. The filter needs no converter of its own and refuses before anything is read. |
| **Leave multipart on** | Boot wrote the parts of an anonymous caller's body to disk before a handler was chosen. **Revisit if** a route must take an upload, which then needs a limit of its own. |
| **Leave the form-content filter on** | It reads a PUT, PATCH or DELETE form body whole, ahead of the security chain, with no limit. **Revisit if** a route must take form content. |
| **Leave the server's form limit at 2 MiB** | A form posted to a JSON route would be parsed up to twice the JSON limit and then refused as the wrong type. |
| **Bound the input stream, or refuse a detected UTF-16 or UTF-32 body (#278)** | Not chosen yet. Either closes the gap above, and either needs a test at the limit in characters and over it in bytes. **Revisit when** #278 is decided, and amend this record with the choice. |

Also revisit when a caller other than our own UI depends on the API's error shape (ADR-024's own
trigger), because the 415 for an unsupported type is still Spring's default body.
