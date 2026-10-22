# ADR-046: Generate in the background, and keep each generation in PostgreSQL

- **Status:** Accepted
- **Date:** 2026-10-23

## Context

ADR-044 generates with CP-SAT, and ADR-045 runs the solve outside any transaction, inside the HTTP
request that asked for it. Measured on the owner's development machine (8 cores):

- **A small or medium roster** is solved to proven optimality in well under a second.
- **At the largest stated size**, 1,000 employees and 500 shifts in a period, the model has 359,449
  choices.
  - Presolve alone takes 75 s, and the first real roster appears after 115 s.
  - The search reaches the coverage greedy finds (978 of 980 places) after 139 s, and proves it
    optimal after 150 s.
  - Within the 10 s a request can afford per search, it finds no roster at all, so generation at
    that size answered a 500.

Forces:

- **The requirements give generation at the largest size 10 minutes**, "so it may run as a
  background job" (requirements.md, *Performance and scale*).
- **A request cannot wait that long.** CloudFront, in front of the API in M3, waits about 30 s for an
  origin by default, and a request thread would be held for the whole solve.
- **More than one instance may run.** In M3 the backend runs as ECS tasks behind a load balancer, so a
  Scheduler asking whether a generation has finished may reach an instance other than the one solving.
- **An instance can stop part-way through a solve**: a deployment, a crash, a scale-in.
- **The owner chose this on 2026-10-22**, over keeping greedy as a fallback, raising the time limit,
  or shipping the 500 (#246, brought into #28).

## Decision

**Asking for a draft starts a generation and answers at once. The solve runs on a bounded pool of
threads in the same process, and the generation is a record in PostgreSQL that any instance can
read.**

- **`POST /api/rosters/generations` answers 202** with the generation and where to read it.
  `GET /api/rosters/generations/{id}` answers its status. The old `POST /api/rosters`, which waited
  for the draft, is gone.
- **A generation is `PENDING` until it ends**, in one of three ways:
  - `SUCCEEDED`, naming its draft, stored in the same unit of work as the draft itself;
  - `REFUSED`, listing the rules the work carried over from a neighbouring roster breaks (#130);
  - `FAILED`, a defect, logged at ERROR where it happened.
- **A generation still `PENDING` past its `lost_at` reads as `LOST`.** That instant is fixed when the
  generation is asked for, at `hrsched.generation.lost-after` later, which exceeds the longest a
  solve may run. The status is derived when it is read, from the clock, so nothing has to sweep, and
  every instance agrees. It stays lost: a solve that outlasts the instant stores no draft, because
  the Scheduler has already been told to generate again.
- **At most `hrsched.generation.concurrent-runs` solves run at once on an instance.** One asked for
  beyond that is refused with a 503 before anything runs, and its record says `FAILED`. Nothing
  queues, so a generation begins the moment it is accepted, and `lost_at` measures a solve rather
  than a wait in a queue.
- **Each of the solver's two searches may run `hrsched.generation.time-limit-per-phase`, now 5
  minutes.** Two of them come to the 10 minutes the requirements allow.
- **The use case marks out its own units of work, as ADR-045 has it.** The proxy now leaves
  `startGeneration` alone rather than `generateDraft`: the `PENDING` record commits before the
  solve is handed to a thread, so the thread always finds it.
- **The solve runs as the Scheduler who asked**, whose identity is carried to the thread as an
  argument, never read from anything ambient (ADR-009). The trace id is carried in the logging
  context, so the solve's log lines join the request's.
- **The SPA asks every second** until the generation ends, then opens the draft or shows why there
  is none.

## Consequences

**Easier**

- **The largest stated size gets the full 10 minutes**, and no request or connection is held while
  it runs.
- **Any instance answers** whether a generation has finished.
- **A solve lost with its instance is reported as lost**, rather than pending forever.
- **A refusal to carry work over is shown as the rules it breaks**, as publication shows them.

**Harder**

- **A new table, and records that accumulate.** Nothing deletes a generation. How long records are
  kept is Q23's to decide, as for the audit trail. **Revisit** when Q23 is answered.
- **Waiting is the client's job.** The SPA asks once a second, which is one cheap read per waiting
  Scheduler. **Revisit** server push when that read shows up in #143's measurements.
- **An instance is busy at `hrsched.generation.concurrent-runs`,** and a Scheduler asking then is
  told to try again. Each solve already uses every core, so running more at once makes each slower
  rather than the queue shorter. **Revisit** when generations in one deployment routinely queue,
  with a separate solver service (ADR-044's alternative) or a durable queue.
- **A solve outlives the request that started it.** An account disabled during a solve does not stop
  it. The draft is a draft, and publishing it still needs a Scheduler.
- **The HTTP tests generate inline.** They replace the thread pool with one that runs the work on the
  calling thread, so the suite stays deterministic. The pool itself has its own tests.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **Keep the generation in memory** | No table. Rejected because a Scheduler's next poll may reach another instance, and the record dies with the instance that held it. |
| **A message queue and a separate solver service** | Scales solving apart from the API and isolates native crashes. Rejected for now: a new piece of infrastructure and a new deployment unit, with nothing yet measuring the need. **Revisit** with ADR-044's separate-service trigger. |
| **Push the result** (server-sent events, a WebSocket) | No polling. Rejected because a long-lived connection through CloudFront and the load balancer is more to operate than a one-row read a second. |
| **Stay synchronous, with a longer limit** | No new moving parts. Rejected for CloudFront's origin timeout and the request thread held for minutes. |
| **Fall back to the greedy roster when the solver runs out of time** | Always answers within the request. Rejected by the owner in favour of giving the solver the time the requirements allow. |
| **Queue generations beyond the pool** | Nobody is told to retry. Rejected because a queued generation's wait would have to count against its `lost_at`, and a queue held in memory dies with the instance anyway. |
