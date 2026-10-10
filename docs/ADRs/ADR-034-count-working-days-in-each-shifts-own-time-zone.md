# ADR-034: Count working days in each shift's own time zone

- **Status:** Accepted
- **Date:** 2026-10-10

## Context

Two working-time rules are about calendar days: `MaximumDailyWorkingTime` and `MaximumConsecutiveWorkingDays`. `EmployeeSchedule.workingTimePerDay` splits work at midnight to feed them, and until now that midnight was always UTC's. `TimeRange` holds two instants and no zone. For any organisation whose midnight is not UTC's, work landed on the wrong day. A roster could then be published that broke a daily limit in the only calendar anybody there uses. That is the failure the product exists to prevent.

The owner decided on 2026-09-22 (Q17, R36): **working-time rules count days in each shift's own time zone.** Not the organisation's, and not the constraint set's.

Forces:

- **`TimeRange` is in the shared kernel.** Workforce uses it for unavailability and preferences, where no day is ever counted. A zone added there would be a scheduling decision imposed on every context, and ADR-008 keeps the kernel small.
- **No threshold may be hardcoded, and no default may stand in for a decision.** A zone a developer chose is a decision about somebody's day.
- **Every existing shift was judged in UTC.** Shifts have no edit path. Whatever a migration gives them is permanent.
- **One person may work in two zones** if an organisation runs sites in both, and their days still have to add up somewhere.
- **The browser is the only thing that knows which zone typed times mean.** `datetime-local` gives wall-clock text, and the Shifts page turns it into an instant in the browser's zone.

## Decision

**A shift carries a `ZoneId`, and each piece of work is split at its own zone's midnights.**

- **`Shift` has a non-null `zone`.** `Shift.workingPeriod()` pairs it with the period as a `WorkingPeriod`, a scheduling value object. `EmployeeSchedule` holds `WorkingPeriod`s. `TimeRange` is unchanged.
- **`workingTimePerDay` splits each period at midnight in that period's zone**, so a day is as long as its clocks make it: 23 hours when they go forward, 25 when they go back.
- **Work in different zones lands on each zone's own calendar date**, and work on the same date adds up whichever zone it was in. Days are keyed by `LocalDate`; there is no "organisation day" to reconcile them into.
- **Only time zone database names are accepted** at the HTTP boundary: the ids in `ZoneId.getAvailableZoneIds()`, such as `Europe/London` or `UTC`. A fixed offset like `+01:00` is refused. It ignores daylight saving, so a shift entered with one would have its days counted an hour wrong half the year. `ZoneId.of` would accept it.
- **There is no default.** A body without a zone is refused with a 400 whose detail is fixed and never repeats what was sent (ADR-024).
- **The schema stores the zone as `shift.zone text NOT NULL`** (V5). Existing rows are backfilled with `UTC`, which keeps exactly the days they were judged on, and the default is then dropped.
- **The Shifts page sends the browser's zone,** `Intl.DateTimeFormat().resolvedOptions().timeZone`. That is the zone it has just read the typed times in.

## Consequences

**Easier**

- **A daily cap and a consecutive-day cap mean the day the people there live**, daylight saving included. That holds for generation, assignment, publication and swap approval alike, because all of them judge through `EmployeeSchedule`.
- **An organisation with sites in several zones needs no special case.** Each shift says where it is.
- **The kernel is untouched**, and so is every workforce type.

**Harder**

- **The zone is the Scheduler's machine, not the workplace.** Someone planning from Lisbon for a Madrid site enters Madrid's times as Lisbon's, and those days are counted in Lisbon. Fixing that needs a zone picker and wall-clock conversion in a chosen zone, which `Date` cannot do (#136). **Revisit** when a Scheduler must plan for a site in another zone, which is #136's trigger.
- **The UTC backfill is permanent.** Every shift defined before V5 counts its days in UTC for good, which is how it was judged. Nothing shows a shift's zone yet either (#136).
- **The database cannot validate a zone.** PostgreSQL's zone list is not the JVM's, so the column is free text that only the HTTP boundary checks. A future writer that bypasses the boundary must check for itself.
- **Every request that defines a shift must carry `zone`.** That includes any client outside this repository; none exists today.

## Alternatives considered

| Alternative | Why it was rejected |
| --- | --- |
| **A zone on the organisation** | Simpler: one setting. Rejected by the owner's answer to Q17, because a multi-site organisation would count one site's days in another's zone. |
| **A zone on `TimeRange`** | One type for everything timed. Rejected because `TimeRange` lives in the shared kernel, and unavailability and preferences have no use for a zone. That would push one context's decision into the kernel ADR-008 keeps small. |
| **A zone on the constraint set** | It would sit beside the rules it serves. Rejected for the same multi-site reason as the organisation, and because a set is versioned per organisation: changing its zone would change which day historic work fell on. |
| **Accept anything `ZoneId.of` accepts** | Fewer refusals. Rejected because fixed offsets ignore daylight saving and would silently count days wrongly for half the year. |
| **Default a missing zone to UTC** | Lets older clients keep working. Rejected because it would reproduce, silently, the very bug this fixes. |
| **Store a zone offset per instant (ISO-8601 with offset)** | It keeps the offset in force when the shift was entered. Rejected because an offset is not a zone: it cannot say where midnight falls on a day the clocks change. |
