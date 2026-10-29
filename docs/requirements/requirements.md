# Requirements — HrSched

**Status: specified, partially built.** Every question that blocked modelling is answered. The
domain is modelled, and a React user interface offers every capability below — for the three roles
within an organisation and for the platform Operator — over a PostgreSQL that survives a restart.
People sign in, and every request is authenticated by a token HrSched signed and authorised
against the account it names. Generation fills every place the rules allow, then weighs the
objectives.

Nothing in the user interface *enforces* a requirement. The `Enforced by` column below names
backend classes and tests on purpose: a screen that appears to satisfy a rule is not evidence that
the rule holds.

This document states **what the system must do**. *How* it is built lives in
[architecture.md](../architecture/architecture.md); *why* a given solution was chosen lives
in the [ADRs](../ADRs/). Neither is restated here.

---

## Purpose and scope

HrSched is a **staff shift scheduling (rostering) system, sold as a product to multiple
organisations**. It exists to turn two inputs — a set of shifts that need covering, and a set
of employees with availability and eligibility constraints — into a published roster, and to
keep that roster correct as reality changes (sickness, swaps, demand shifts, late
cancellations).

The value is in the constraints, not the calendar: a roster that ignores rest periods,
qualifications, or contracted hours is worse than no roster, because it looks authoritative
while being wrong.

**Who it is for** — independent organisations, each running its own staff, shifts, and rules
on a shared deployment, with no visibility of one another. Within an organisation there are
three roles: **Employee**, **Scheduler**, and **Admin**.

**Deliberately out of scope** — see [Non-goals](#non-goals).

---

## Functional requirements

Each requirement is stated as something a user or caller can *do*, and names the test or
class that enforces it once one exists — so this table can be checked rather than trusted.

Every row was reviewed with the repository owner on 2026-09-22
([#50](https://github.com/NaimElijah/HrSched/issues/50)). A row naming a class and a test is
implemented; `not yet` means the requirement is decided and the linked issue tracks building it.
*Any member* means any of the three roles within an organisation. The Operator is not a member
([ADR-030](../ADRs/ADR-030-confine-the-operator-to-the-register-of-organisations.md)).

| ID | Role | Requirement | Enforced by |
| --- | --- | --- | --- |
| F1 | Scheduler | Define the shifts that need covering, with headcount and qualifications, marking any that people would rather not work as unpopular | `SchedulingService`, `RosteringHttpTest`; the mark: `Shift`, `JdbcShiftRepository`, `JdbcShiftRepositoryTest`, `RosteringHttpTest.GivenAShiftMarkedUnpopular_WhenDefined_ThenTheMarkIsEchoedAndReadBack` ([ADR-010](../ADRs/ADR-010-score-a-roster-by-three-objectives-each-organisation-weights.md)). *The mark is the Scheduler's to give: nothing infers it from the clock or from preferences, and it changes no rule a roster must obey. Shifts stored before V10 are not marked.* |
| F1a | Any member | See the shifts needing covering over a span | `SchedulingService`, `SchedulingServiceTest`; as an Employee: `SchedulingServiceTest.GivenDefinedShifts_WhenAnEmployeeListsThemForASpan_ThenTheyAreShown` |
| F2 | Scheduler | Assign an employee to a shift, and remove that assignment | `SchedulingService`, `SchedulingServiceTest`, `RosteringHttpTest`. *Drafts only: a published roster is superseded, never edited (R6).* |
| F3 | Scheduler | Publish a draft roster, which is refused if it breaks any rule | `SchedulingService`, `RosteringHttpTest`. Every breach at once, of every kind — R3, R35, R4, R5, R18 and the working-time rules: `SchedulingServiceTest.GivenADraftBreakingSeveralRulesAtOnce_WhenPublishing_ThenEveryViolationIsListed` (R3, R4, R18), `…GivenADraftBreakingEveryKindOfRule_WhenAssessedAndThenPublished_ThenPublicationRefusesWithExactlyTheAssessedViolations` (adds R5 and a working-time rule), `…GivenTwoDraftsPuttingOnePersonInTwoPlaces_WhenTheSecondIsPublished_ThenItIsRefused` (R35), [ADR-042](../ADRs/ADR-042-judge-a-roster-by-one-check-publication-and-a-read-share.md). *Absorbed F12, which restated it.* |
| F4 | Admin, Scheduler | See the roster and availability of every employee in their organisation | `EmployeeService`, `EmployeeServiceTest`, `EmployeeHttpTest`. *Availability, qualifications and contracted hours are on the listing; the roster half is served by F11a rather than repeated here.* |
| F5 | Any member linked to an employee | See their own published roster | `SchedulingService`, `SchedulingServiceTest`, `RosteringHttpTest`. *The assignments naming them, from published rosters only — the whole roster stays Admin and Scheduler (R12). Tested for an Employee, and for a Scheduler and an Admin linked to an employee: `SchedulingServiceTest.GivenASchedulerLinkedToAnEmployee_WhenReadingTheirOwnSchedule_ThenTheySeeTheirWork`, `…GivenAnAdminLinkedToAnEmployee_WhenReadingTheirOwnSchedule_ThenTheySeeTheirWork`.* |
| F6 | Employee, or an Admin or Scheduler on their behalf | Declare when they cannot work, and state soft preferences, and see what they declared | `EmployeeService`, `EmployeeServiceTest`, `EmployeeHttpTest` ([ADR-012](../ADRs/ADR-012-acting-identity-carries-its-employee-record.md)). *Seeing what they declared is authorised as declaring is, and refused for a colleague: `EmployeeServiceTest.GivenAnEmployee_WhenReadingTheirOwnDeclarations_ThenBothAreReturned`, `EmployeeHttpTest.GivenAColleague_WhenReadingSomebodyElsesUnavailability_ThenItIsForbidden`. Preferences are an objective: the draft check counts those a draft goes against (F34), and generation honours them where it can once every place it can fill is filled, if the organisation weighs them: `CpSatRosterGeneratorTest.GivenPreferencesWeighed_WhenGenerating_ThenNobodyIsPutWhereTheyWouldRatherNotBe`, `…ThenEachWhoAskedForAShiftGetsIt` ([ADR-044](../ADRs/ADR-044-generate-rosters-with-or-tools-cp-sat.md)). Declaring on somebody's behalf is tested for an Admin only ([#100](https://github.com/NaimElijah/HrSched/issues/100)); reading it back is tested for an Admin and a Scheduler.* |
| F7 | Employee, or an Admin or Scheduler on their behalf | Request a swap of one of their assignments with another employee: a transfer of that shift, or a two-way exchange of two | `SwapService`, `SwapServiceTest`, `SwapHttpTest` ([ADR-022](../ADRs/ADR-022-approving-a-swap-publishes-a-replacement-roster.md)). *The transfer is built; the exchange: not yet — [#82](https://github.com/NaimElijah/HrSched/issues/82). Acting on somebody's behalf is tested for an Admin and for a Scheduler: `SwapServiceTest.GivenAnAdmin_WhenAskingOnSomebodyElsesBehalf_ThenItIsPermitted`, `…GivenAScheduler_WhenAskingOnSomebodyElsesBehalf_ThenItIsRecordedAsTheirRequest`.* |
| F8 | Admin | Add employees | `EmployeeService`, `EmployeeServiceTest`, `EmployeeHttpTest` |
| F9 | Admin | Define qualifications, and grant them to employees | `QualificationService`, `QualificationServiceTest`, `QualificationHttpTest`; granting: `EmployeeService`, `EmployeeServiceTest` |
| F10 | Admin | Configure the organisation's working-time constraint set | `SchedulingService`, `RosteringHttpTest` |
| F11 | Scheduler | Generate a draft roster covering a period, filling every shift it legally can | `SchedulingService`, `RosteringHttpTest`, `CpSatRosterGenerator`, `CpSatRosterGeneratorTest.GivenSomebodyGreedyUsesUpEarly_WhenGenerating_ThenTheShiftOnlyTheyCouldTakeIsCoveredToo` ([ADR-044](../ADRs/ADR-044-generate-rosters-with-or-tools-cp-sat.md)). *In the background, answered at once and read back when it ends, so each of the solver's two searches may take the five minutes that make the ten the scale targets allow: `RosteringHttpTest.GivenAScheduler_WhenStartingAGeneration_ThenItIsAcceptedWithWhereToReadIt`, `SchedulingServiceTest`, `GenerationRunnerTest` ([ADR-046](../ADRs/ADR-046-generate-in-the-background-and-keep-the-job-in-postgresql.md), [#246](https://github.com/NaimElijah/HrSched/issues/246)).* |
| F11a | Admin, Scheduler | See the rosters their organisation holds | `SchedulingService`, `RosteringHttpTest`; as an Admin: `RosteringHttpTest.GivenAGeneratedRoster_WhenAnAdminListsRosters_ThenItIsReturned`, `…GivenAGeneratedRoster_WhenAnAdminReadsIt_ThenItIsReturnedWithItsAssignments` |
| F12 | — | *Retired on 2026-09-22: it restated F3. The number is not reused.* | — |
| F13 | Operator | Administer organisations across the platform | `OrganisationService`, `OrganisationServiceTest`, `OrganisationHttpTest` ([ADR-019](../ADRs/ADR-019-an-application-layer-for-the-access-context.md)). *Onboard, list and read. Any member may also read their own organisation's record, which is tested for an Admin only ([#100](https://github.com/NaimElijah/HrSched/issues/100)). Renaming is F30 and closing is F31. Onboarding creates the organisation's first Admin in the same transaction - the operator is refused everything inside an organisation but F33 ([ADR-030](../ADRs/ADR-030-confine-the-operator-to-the-register-of-organisations.md)), so one onboarded without an Admin would have nobody able to add one. That Admin must change the password they were given before anything else (ADR-029).* |
| F14 | Everyone | Sign in with a sign-in name and password, stay signed in across a reload, sign out, and change their own password | `SessionService`, `SessionServiceTest`, `AuthController`, `AuthHttpTest`, `SessionRefreshHttpTest`, `SessionHttpTest`; token verification: `AccessTokenDecoder`, `AccessTokenDecoderTest`, `ActorAuthenticationConverter`, `ActorAuthenticationConverterTest`, `SecurityConfiguration` ([ADR-011](../ADRs/ADR-011-issue-our-own-access-tokens.md), [ADR-028](../ADRs/ADR-028-keep-the-session-in-memory-and-a-rotating-refresh-cookie.md)). *A password somebody else chose must be changed before anything else; an account is never self-created (ADR-029).* |
| F15 | Admin | Administer the accounts of their own organisation: create one, list them, change what an account is, disable and restore one, and reset somebody's password | `AccountService`, `AccountAdministrationTest`, `AccountController`, `AccountHttpTest` ([ADR-029](../ADRs/ADR-029-provision-accounts-from-the-top-down.md), [ADR-030](../ADRs/ADR-030-confine-the-operator-to-the-register-of-organisations.md)). *The organisation is taken from the token, never from the request, so there is no identifier for an Admin to change.* |
| F16 | Any member linked to an employee | Read the notifications meant for them, and mark one read | `NotificationService`, `NotificationServiceTest`, `NotificationHttpTest` ([ADR-021](../ADRs/ADR-021-notifications-as-a-bounded-context.md)) |
| F17 | Scheduler | Approve or reject a pending swap | `SwapService`, `SwapServiceTest`, `SwapHttpTest` ([ADR-022](../ADRs/ADR-022-approving-a-swap-publishes-a-replacement-roster.md)) |
| F18 | Any member | See swap requests: every one for an Admin or a Scheduler, and only those they are party to for anybody else | `SwapService`, `SwapServiceTest`, `SwapHttpTest` |
| F19 | Admin | Set an employee's contracted hours, and clear them | `EmployeeService`, `EmployeeServiceTest`, `EmployeeHttpTest`. *Clearing: not yet — [#83](https://github.com/NaimElijah/HrSched/issues/83).* |
| F20 | Any member | See the organisation's qualifications | `QualificationService`, `QualificationServiceTest`, `QualificationHttpTest` |
| F21 | Any member | See the organisation's working-time rules, with their thresholds | `SchedulingService`, `RosteringHttpTest`, as an Admin, an Employee and a Scheduler: `RosteringHttpTest.GivenConfiguredRules_WhenAnEmployeeReadsThem_ThenTheirThresholdsAreShown`, `…GivenConfiguredRules_WhenASchedulerReadsThem_ThenTheirThresholdsAreShown`. Any earlier version too, which is what a roster published under it was judged against (Q9): `JdbcConstraintSetRepositoryTest`, `SchedulingServiceTest`, `RosteringHttpTest.GivenRulesRevisedAfterPublication_WhenTheRosterVersionIsRead_ThenItIsTheRulesItPassed`, as an Employee. |
| F22 | Admin | Remove an employee who leaves | not yet — [#84](https://github.com/NaimElijah/HrSched/issues/84) |
| F23 | Admin | Correct an employee's name | not yet — [#85](https://github.com/NaimElijah/HrSched/issues/85) |
| F24 | Admin | Revoke a qualification from an employee | not yet — [#86](https://github.com/NaimElijah/HrSched/issues/86) |
| F25 | Admin | Retire a qualification | not yet — [#87](https://github.com/NaimElijah/HrSched/issues/87) |
| F26 | Employee, or an Admin or Scheduler on their behalf | Withdraw declared unavailability or a stated preference | `Employee`, `EmployeeTest`, `EmployeeService`, `EmployeeServiceTest`, `EmployeeHttpTest`; nothing else of the employee lost in the save, `JdbcEmployeeRepositoryTest`; a withdrawn absence no longer keeps anybody off a generated roster, `RosteringHttpTest.GivenUnavailabilityWithdrawn_WhenGeneratingARoster_ThenTheShiftIsCovered` ([ADR-040](../ADRs/ADR-040-withdraw-a-declaration-by-what-it-says.md)). *Named exactly as it was declared: a span that only overlaps a declared one is refused, and withdrawing a preference withdraws every identical statement. Nothing of the declaration is kept; the audit line records that it was withdrawn.* |
| F27 | Scheduler | Change or delete a shift | not yet — [#89](https://github.com/NaimElijah/HrSched/issues/89) |
| F28 | Scheduler | Discard a draft roster | not yet — [#90](https://github.com/NaimElijah/HrSched/issues/90) |
| F29 | The employee who raised it | Withdraw a pending swap request | not yet — [#91](https://github.com/NaimElijah/HrSched/issues/91) |
| F30 | Admin | Rename their own organisation | not yet — [#92](https://github.com/NaimElijah/HrSched/issues/92) |
| F31 | Operator | Close an organisation | not yet — [#93](https://github.com/NaimElijah/HrSched/issues/93) |
| F32 | Admin | Apply a jurisdiction preset shipped with HrSched to their organisation's constraint set | not yet — [#105](https://github.com/NaimElijah/HrSched/issues/105) |
| F33 | Operator | Reset an organisation's Admin password, after verifying the request outside the application | not yet — [#107](https://github.com/NaimElijah/HrSched/issues/107). *A deliberate exception to [ADR-030](../ADRs/ADR-030-confine-the-operator-to-the-register-of-organisations.md), needing an ADR of its own (Q28, [#114](https://github.com/NaimElijah/HrSched/issues/114)).* |
| F34 | Admin, Scheduler | See whether a draft breaks any rule, which shifts it leaves short, and how it scores on the organisation's objectives, without publishing it | `SchedulingService.findAssessment`, `SchedulingServiceTest`, `RosteringHttpTest`; that it agrees with publication: `SchedulingServiceTest.GivenADraftBreakingEveryKindOfRule_WhenAssessedAndThenPublished_ThenPublicationRefusesWithExactlyTheAssessedViolations`, [ADR-042](../ADRs/ADR-042-judge-a-roster-by-one-check-publication-and-a-read-share.md). *Added from the owner's backlog ([#205](https://github.com/NaimElijah/HrSched/issues/205)) after the 2026-09-22 review. Advisory: publication decides. The score: `RosterMeasures`, `RosterMeasuresTest`, `RosterScore`, `RosterScoreTest`, `SchedulingServiceTest`, `RosteringHttpTest.GivenWeightedObjectives_WhenADraftIsAssessed_ThenEachMeasureAndTheTotalAreListed` ([ADR-010](../ADRs/ADR-010-score-a-roster-by-three-objectives-each-organisation-weights.md)). Judging a draft with proposed changes applied is not yet built, nor is the latency target measured ([#143](https://github.com/NaimElijah/HrSched/issues/143)).* |
| F35 | Admin | Weight the objectives that make one legal roster better than another: fairness, preferences and disruption | `SchedulingService`, `SchedulingServiceTest`, `ObjectivesHttpTest`, `JdbcObjectiveWeightsRepository`, `JdbcObjectiveWeightsRepositoryTest` ([ADR-010](../ADRs/ADR-010-score-a-roster-by-three-objectives-each-organisation-weights.md)). *Added from the owner's backlog ([#27](https://github.com/NaimElijah/HrSched/issues/27)) after the 2026-09-22 review, answering Q13. A revision replaces the weights whole, as a constraint revision does; an objective left out is not weighed.* |
| F36 | Any member | See the organisation's objective weights | `SchedulingService`, `SchedulingServiceTest`, `ObjectivesHttpTest.GivenAnEmployee_WhenReadingTheWeights_ThenTheyAreShown`. *As any member sees the rules (F21).* |

**Swaps (F7, F17)** are approved by a **Scheduler**; the other employee is informed rather than asked,
and being informed means a notification they can go and read. Approving does not edit the published
roster — R6 forbids that — it publishes a replacement that supersedes it, through the same
publication every other roster goes through, so a swap leaving the roster breaking any rule is
refused with no override — a working-time rule, or somebody else on it who has since become
unavailable or unqualified, who must be reassigned first. Whether the colleague may actually cover the shift is decided when a Scheduler answers
rather than when an Employee asks, because deciding it means reading colleagues' unavailability.

**Roster generation is automatic** — that is the point of the product. A Scheduler states what
needs covering and the system produces a legal roster, filling every place the rules allow and
then ranking those rosters by the objectives below ([ADR-044](../ADRs/ADR-044-generate-rosters-with-or-tools-cp-sat.md)).

---

## Business rules and invariants

### Working-time rules are configuration, not code

**The domain enforces a configured set of constraints. It does not encode any country's law.**
A jurisdiction — Israel, the EU directive, the UK regulations, a US state — is expressed as a
*preset*: a named constraint set with particular values. A constraint set belonging to no
jurisdiction at all is equally valid, and an organisation may define its own.

This has three consequences that the model must honour:

- **No legal threshold is hardcoded anywhere.** A minimum rest period is a value in a
  constraint set, never a constant in a domain class.
- **Each organisation configures its own set** (F10), because tenants may operate under
  different law or under stricter internal policy.
- **A preset is data, and its values must be sourced.** Before any preset ships, its numbers
  are taken from the statute and the source cited. A preset that is wrong produces rosters
  that are confidently illegal, which is the exact failure this product exists to prevent.

**The first preset is Israel's**, sourced from the Hours of Work and Rest Law, 5711-1951
([#105](https://github.com/NaimElijah/HrSched/issues/105)). Presets ship with HrSched, and an Admin
applies one to their organisation's set (F32). A preset is a starting point, not a floor: once
applied, the set is the organisation's own, and an Admin may change any value in it (F10).

### Constraints are invariants, not warnings

A roster that violates its organisation's constraint set **cannot be published**. There is no
override, and none is needed: emergency cover means publishing a replacement roster that is
itself legal, or leaving the shift uncovered so the business sees the real problem. That is why
a published roster is superseded rather than edited (R6).

### Rules that hold regardless of configuration

| ID | Rule |
| --- | --- |
| R1 | A time range must end strictly after it starts. *`TimeRange`, `TimeRangeTest`* |
| R2 | An employee, an organisation and a qualification must each have a name that is not blank. *`Employee`, `EmployeeTest`; `Organisation`, `OrganisationTest`; `Qualification`, `QualificationTest`* |
| R3 | An employee may not hold two overlapping assignments. *`Roster`, `RosterTest`; on the manual path `SchedulingService`, `SchedulingServiceTest`, `RosteringHttpTest`; at swap approval `SwapServiceTest`; in generation `RosterGeneratorContract`, run by `CpSatRosterGeneratorTest`* |
| R4 | An employee may only cover a shift whose qualifications they all hold. *`RosterCandidate`, `RosterCandidateTest`; on the manual path `SchedulingService`, `SchedulingServiceTest`; at swap approval `SwapServiceTest`; at publication `AssignmentEligibility`, `SchedulingServiceTest`* |
| R5 | An employee may only be assigned within their own organisation. *`SchedulingService`, `SchedulingServiceTest`, `RosteringHttpTest`; at publication `AssignmentEligibility`, `SchedulingServiceTest`* |
| R6 | A published roster may not be edited; it is superseded by a new version. *`Roster`, `RosterTest`, `SchedulingService`, `SchedulingServiceTest`* |
| R7 | An employee may not declare overlapping unavailability. *`Employee`, `EmployeeTest`* |
| R8 | A shift must require at least one person. *`Shift`, `ShiftTest`* |
| R9 | A shift may only require qualifications its own organisation has defined. *`SchedulingService`, `SchedulingServiceTest`* |
| R10 | A roster assigning a shift that cannot be resolved cannot be published. *`Roster`, `SchedulingServiceTest`* |
| R11 | Publishing a roster supersedes every published roster of that organisation whose period it overlaps. *`SchedulingService`, `SchedulingServiceTest`; against a publication in flight at the same time, `RosterRepository.lockPublicationsOf` (`SchedulingServiceTest`, `SwapServiceTest`, `JdbcRosterRepositoryTest`) and, behind it, the `roster_one_published_per_moment` constraint (`JdbcRosterRepository`, `JdbcRosterRepositoryTest`)* |
| R12 | A roster may only be read by an Admin or a Scheduler, because it is personal data about every colleague. *`SchedulingService`, `RosteringHttpTest`* |
| R13 | A sign-in name identifies one account across the whole platform, so two accounts can never hold the same one. *`JdbcAccountRepository` and the `account_sign_in_name_unique` constraint, `JdbcAccountRepositoryTest`, `AccountAdministrationTest`, `AccountHttpTest`* |
| R14 | An organisation always keeps at least one active Admin: the last one can be neither demoted nor disabled. *`AccountService`, `AccountAdministrationTest`, `AccountHttpTest`* |
| R15 | An account may be linked only to an employee of its own organisation, and an employee has at most one account. *`AccountService`, `AccountAdministrationTest`, `AccountHttpTest`; the second half also `JdbcAccountRepositoryTest`* |
| R16 | A password chosen by anybody other than the account's owner must be replaced before that account holds a role. *`Account`, `SessionService`, `ActorAuthentication`, `ActorAuthenticationConverterTest`, `SessionHttpTest`, `AccountHttpTest`* |
| R17 | Disabling an account, and resetting its password, end every session it holds. A reset also clears any lockout. An Admin resets other people's passwords, never their own: changing your own takes the current one, and a way around that would undo what it protects. *`AccountService`, `AccountAdministrationTest`, `AccountHttpTest`* |
| R18 | An employee may not be assigned across a span they declared unavailable, whether by hand, by generation or through a swap. *`RosterCandidate`, `AssignmentEligibility`, `RosterCandidateTest`; on the manual path `SchedulingServiceTest`; at swap approval `SwapServiceTest`; in generation `RosteringHttpTest`; at publication, for somebody who declared it after being assigned, `RosteringHttpTest.GivenUnavailabilityDeclaredAfterAssignment_WhenPublishing_ThenItIsRefusedWithThatViolation`* |
| R19 | A member reaching for another organisation's data is answered *not found*, never *forbidden*, because a refusal would confirm that the thing exists. *The exception handler of each web adapter: `AccessExceptionHandler`, `WorkforceExceptionHandler`, `SchedulingExceptionHandler`, `NotificationExceptionHandler`; `RosteringHttpTest`, `CrossTenantSchedulingWriteHttpTest`, `OrganisationHttpTest`, `AccountHttpTest`, and in `NotificationHttpTest` `GivenAColleaguesNotification_WhenAcknowledgingIt_ThenItIsNotFoundAndStaysUnread` and `GivenAnotherOrganisationsNotification_WhenOurMemberAcknowledgesIt_ThenItIsNotFoundAndStaysUnread`* |
| R20 | A swap may be requested only for an assignment the requester holds on the roster currently published. *`SwapService`, `SwapServiceTest`* |
| R21 | A swap is always with somebody else. *`SwapRequest`, `SwapServiceTest`* |
| R22 | A shift's headcount is the least it needs, not the most it may have: more people may be assigned to it by hand. A shift that an authoritative roster beside a draft already staffs keeps those people when the draft is generated, and they count toward its headcount there; generation fills only the places left (decided 2026-10-22, [#130](https://github.com/NaimElijah/HrSched/issues/130)). *`Shift`, `SchedulingService`; `RosteringHttpTest.GivenAStraddlingShiftStaffedOnLastWeeksPublishedRoster_WhenNextWeekIsGenerated_ThenTheyStay`, `RosterGeneratorContract`. Overstaffing by hand has no test yet — [#99](https://github.com/NaimElijah/HrSched/issues/99)* |
| R23 | A qualification's name is unique within its organisation. *Not yet — [#95](https://github.com/NaimElijah/HrSched/issues/95)* |
| R24 | A constraint set holds at most one constraint of each kind. *Not yet — [#96](https://github.com/NaimElijah/HrSched/issues/96)* |
| R25 | Every working-time threshold is greater than zero, and each revision of a constraint set raises its version by one. *`WorkingTimeConstraint`, `WorkingTimeConstraintTest`, `ConstraintSet`, `ConstraintSetTest`* |
| R26 | Stated preferences may overlap one another, unlike unavailability (R7). *`Employee`; no test pins it yet — [#99](https://github.com/NaimElijah/HrSched/issues/99)* |
| R27 | A sign-in name ignores case and surrounding spaces, so two names differing only in those are the same name. *`SignInName`, `SignInNameTest`, `AuthHttpTest`* |
| R28 | A new password must differ from the current one. *`SessionService`, `SessionHttpTest`* |
| R29 | Consecutive failed sign-ins lock an account for a while, and a successful sign-in clears the count. A wrong current password given when changing it counts as a failure, and a locked account cannot change its password either, whatever password it gives, with nothing counted. How many failures, and for how long, is configuration (Q27). *`Account`, `SessionService`, `AuthHttpTest`, `SessionHttpTest`, `LockedAccountPasswordChangeHttpTest`, `PasswordChangeRefusalLogHttpTest`* |
| R30 | Presenting a refresh token that was already exchanged ends the whole session it belongs to. *`SessionService`, `SessionServiceTest`, `SessionRefreshHttpTest`* |
| R31 | The Operator's account never holds a role within an organisation, and is never linked to an employee. *`Account`, `AccountTest`* |
| R32 | An assignment has at most one pending swap request. *`SwapService`, `SwapServiceTest`, `SwapHttpTest`; the schema's `swap_request_one_pending_per_assignment` for a writer that skips the publication lock, `JdbcSwapRequestRepositoryTest`; duplicates already stored lapsed by V8, `SwapRequestMigrationTest` ([ADR-039](../ADRs/ADR-039-lapse-pending-swaps-in-the-publication-that-supersedes-their-roster.md)). The assignment is the roster, the shift and the person on it, so the same shift on a replacement roster is another one, which leaves [#135](https://github.com/NaimElijah/HrSched/issues/135) open* |
| R33 | A pending swap request lapses when the roster it names is superseded, and its parties are told. *`SwapRequest`, `SwapRequestTest`; in the publication that supersedes the roster `SchedulingService`, `SwapServiceTest`, `SwapHttpTest`; requests already pending on a superseded roster lapsed by V7, `SwapRequestMigrationTest` ([ADR-039](../ADRs/ADR-039-lapse-pending-swaps-in-the-publication-that-supersedes-their-roster.md))* |
| R34 | An account holding the Employee role is always linked to an employee of its own organisation. One stored unlinked before the rule is disabled, and may be restored only once it is linked; linking it does not restore it. *`Account`, `AccountTest`; `AccountService`, `AccountAdministrationTest`, `SessionServiceTest`, `AccountHttpTest`; the ones already stored disabled by migration `V9`, which adds the `account_employee_role_is_linked` constraint, `EmployeeAccountLinkMigrationTest`* |
| R35 | A shift straddling two roster periods may be carried by either roster, and R3 is judged across every authoritative roster rather than within one. *`Roster`, `HeldWork`, `RosterTest`; at assignment and publication `SchedulingService`, `SchedulingServiceTest`, `RosteringHttpTest`; at swap approval `SwapServiceTest` ([ADR-033](../ADRs/ADR-033-judge-r3-across-authoritative-rosters-under-the-publication-lock.md)). Generation is given that work as time each person is busy, so it never drafts what publication refuses for this: `RosteringHttpTest.GivenTheirEveningOnLastWeeksPublishedRoster_WhenNextWeekIsGenerated_ThenItIsPublishable` ([#130](https://github.com/NaimElijah/HrSched/issues/130))* |
| R36 | Working-time rules count days in each shift's own time zone. *`Shift`, `WorkingPeriod`, `EmployeeSchedule`, `EmployeeScheduleTest`, `ConstraintSetTest`, `WorkingPeriodTest`; in generation `RosterGeneratorContract`, run by `CpSatRosterGeneratorTest`; at publication `SchedulingServiceTest`, `RosteringHttpTest`; stored by `JdbcShiftRepository`, `JdbcShiftRepositoryTest` ([ADR-034](../ADRs/ADR-034-count-working-days-in-each-shifts-own-time-zone.md)). The zone sent is the browser's, so a site in another zone cannot yet be planned for — [#136](https://github.com/NaimElijah/HrSched/issues/136)* |
| R37 | Roster history is append-only: what was authoritative at any past moment can be reconstructed exactly. *`Roster` (`RosterTest`), `SchedulingService` (`SchedulingServiceTest`), `JdbcRosterRepository` and the `roster_one_published_per_moment` constraint (`JdbcRosterRepositoryTest`), migration `V6` (`AppendOnlyRosterHistoryMigrationTest`) ([ADR-037](../ADRs/ADR-037-keep-roster-history-as-a-record-in-time.md)). Exact from V6 on: nothing earlier was recorded.* |
| R38 | A sign-in name is an email address. *Not yet — [#108](https://github.com/NaimElijah/HrSched/issues/108)* |
| R39 | Operators and Admins sign in with a second factor as well as a password. *Not yet — [#109](https://github.com/NaimElijah/HrSched/issues/109)* |
| R40 | Every objective weight is a whole number of at least one, and an objective with no weight is left out of the score. *`ObjectiveWeights`, `ObjectiveWeightsTest`; the schema's checks on `objective_weights`, `JdbcObjectiveWeightsRepositoryTest`; `ObjectivesHttpTest`* |

A roster moves `DRAFT → PUBLISHED → SUPERSEDED`, and no other transition is legal. Exactly one
roster is authoritative for any given moment, which is what R11 enforces.

A swap request moves from `REQUESTED` to exactly one of approved or rejected (F17), withdrawn (F29)
or lapsed (R33), and each of those is final. Approved, rejected and lapsed exist today; withdrawn is
not built yet ([#91](https://github.com/NaimElijah/HrSched/issues/91)).

**Only a Scheduler builds rosters.** An Admin manages people, qualifications and the constraint
set and cannot define shifts, generate or publish — F1/F3/F11 against F8/F9/F10.

**Unavailability and rosters are personal data.** Listing the people in an organisation, and
reading any roster, are both restricted to Admins and Schedulers; an Employee cannot read a
colleague's. F5 — an employee seeing *their own* work — is a narrower capability beside that one
rather than a widening of it: it answers only for the personnel record the caller carries, so it
discloses nothing about anybody else. An actor with no personnel record is refused rather than
shown an empty week. An Employee learns nothing about colleagues, not even their names, so the
colleague taking a swapped shift is named by identifier (Q25).

**Revising the rules replaces the whole set.** There is no partial update: a caller sends the rules
that should apply from now on, and any rule omitted is switched off. `GET /api/constraints`
therefore returns the thresholds, not only the rule names, so a caller changing one rule can resend
the rest instead of silently deleting them.

### Constraints a set may express

Implemented: **minimum rest between assignments**, **maximum daily working time**, **maximum
working time across the roster period**, **maximum consecutive working days**. Each is optional;
an unset constraint is simply not enforced, and an empty set is a valid configuration.

Required but not yet built, each with its threshold a configured value (R25):

- **minimum uninterrupted weekly rest** — [#101](https://github.com/NaimElijah/HrSched/issues/101);
- **limits specific to night work**, where what counts as night is itself configured —
  [#102](https://github.com/NaimElijah/HrSched/issues/102);
- **contracted-hours bounds**: a roster may not give somebody fewer or more hours than their
  contract says — [#103](https://github.com/NaimElijah/HrSched/issues/103);
- **a weekly maximum averaged over a configured reference period** longer than the roster, which
  needs the work of earlier rosters (Q14) — [#104](https://github.com/NaimElijah/HrSched/issues/104).

### What makes one legal roster better than another

Three objectives, explicitly *not* constraints (ADR-005): they rank legal rosters and decide
nothing, so a roster scoring well is still refused if it breaks a rule. Each is a count, and lower
is better ([ADR-010](../ADRs/ADR-010-score-a-roster-by-three-objectives-each-organisation-weights.md)
defines them, and says why):

- **fairness** — the most unpopular shifts any one person carries, where a shift is unpopular
  because the Scheduler marked it so (F1);
- **respect for stated preferences** — the preferences a roster goes against (F6);
- **minimal disruption** — the assignments a roster changes from the published rosters it would
  supersede.

**Each organisation weights the objectives itself** (Q13), as it configures its own constraint set:
a weight is configuration, never a constant (F35, R40). The draft check scores a draft under those
weights (F34). **Generation minimises the same score, second to coverage**: once the solver has
filled every place it can, it keeps at least that many filled and minimises the weighted total
(`CpSatRosterGeneratorTest`, `GenerationBenchmarkTest`, [ADR-044](../ADRs/ADR-044-generate-rosters-with-or-tools-cp-sat.md)).

---

## Non-functional requirements

- **Authentication and authorisation** — three roles per organisation: **Employee**,
  **Scheduler**, **Admin**, plus a platform **Operator** belonging to no organisation. The
  Operator administers the **register** of organisations across tenants — onboarding, listing and
  reading them (F13) — and is **refused inside any organisation**
  ([ADR-030](../ADRs/ADR-030-confine-the-operator-to-the-register-of-organisations.md)), enforced by
  `Actor.requireAccessTo`, every application service, and their tests. Authorisation is enforced in
  the application services. Authentication is HrSched's own (F14, ADR-011): signing in issues a
  signed access token, and the acting identity is resolved from a stored account and a live session
  on **every** request, so disabling somebody, changing their role or signing them out takes effect
  on their next call rather than whenever a token happens to expire. The security chain ends in
  `denyAll`, so a route nobody wrote a rule for is refused
  ([ADR-032](../ADRs/ADR-032-close-the-security-chain-by-default.md)). There are no identity
  headers left: the organisation comes from the account
  ([ADR-031](../ADRs/ADR-031-take-the-organisation-from-the-account.md)).
  There is one exception to the Operator's confinement, decided on 2026-09-22: the Operator may
  reset an organisation's Admin password after verifying the request outside the application, so
  an organisation whose only Admin cannot sign in can recover (F33, Q28). It needs a new ADR amending
  ADR-030. There may be more than one Operator (Q30); what creates a second one is still open.
  Operators and Admins must sign in with a second factor (R39, Q31). The shipped security defaults
  are confirmed (Q27): an access token lives 10 minutes; a session lasts 12 hours at most and ends
  after 1 hour idle; 10 consecutive failures lock an account for 15 minutes; a password is at least
  15 characters and at most 72 bytes; bcrypt strength is 12. Each is configuration a deployment may
  override.
- **Tenant isolation** — the deployment is shared by independent organisations that must never
  see one another's data. Every aggregate belongs to exactly one organisation, and every query
  is scoped to it. A cross-tenant read is a security incident, not a bug, and isolation must be
  covered by its own tests rather than assumed. **A request cannot name an organisation at all**:
  it acts in the one its own account belongs to, read from a row the caller cannot write, so a
  tenant can be neither invented nor borrowed.
  *Enforced by `Actor.requireTenantMembership` and `Actor.requireAccessTo` (`ActorTest` covers the
  second), and by the helper in each web adapter that takes the organisation from the actor
  (`actingIn`; `organisationOf` in `AccountController`), covered by `EmployeeHttpTest`, `CrossTenantEmployeeWriteHttpTest`, `CrossTenantSchedulingWriteHttpTest`, `RosteringHttpTest`, `SwapHttpTest`, `NotificationHttpTest`
  and `AccountHttpTest`;
  [ADR-031](../ADRs/ADR-031-take-the-organisation-from-the-account.md). On the write side, by the
  guard on every upsert by identity, which refuses to store over another organisation's row:
  `JdbcAccountRepository`, `JdbcEmployeeRepository`, `JdbcQualificationRepository`,
  `JdbcShiftRepository`, `JdbcRosterRepository`, `JdbcSwapRequestRepository` and
  `JdbcNotificationRepository`, each covered by its own `…RepositoryTest`.*
- **Persistence guarantees** — every accepted write is durable: once a request answers 2xx the
  change has committed and survives a crash or a restart. **A use case is one transaction**, so a
  request refused part-way leaves nothing behind — including the replacement roster a refused swap
  approval used to strand. Every aggregate lives in PostgreSQL, and the schema is changed only by a
  versioned migration. Roster history is **append-only**, so that what was authoritative at any
  past moment can be reconstructed (R37, Q24): a roster records when it was published and when it
  was superseded, each once, and nothing recorded is rewritten. Nothing is purged and no retention
  period is set — Q23. **Concurrent changes to one roster, employee or account take turns, and
  none is lost** ([#33](https://github.com/NaimElijah/HrSched/issues/33)): a failed sign-in cannot
  undo an Admin's disable or reset, nor open a session with the password a reset replaced.
  *Enforced by `Jdbc*Repository` (×10) and their adapter tests, `TransactionalUseCases`,
  `SwapHttpTest`; for concurrent changes, `findForUpdate` in `JdbcRosterRepository`,
  `JdbcEmployeeRepository` and `JdbcAccountRepository`, raced on real threads in their adapter
  tests and in `RosteringHttpTest.GivenTwoSchedulersAssigningToOneDraftAtOnce_WhenBothCommit_ThenBothAssignmentsAreKept`,
  and sign-in in `SessionServiceTest`;
  [ADR-041](../ADRs/ADR-041-make-concurrent-changes-take-turns-on-the-row.md),
  [ADR-004](../ADRs/ADR-004-postgresql-behind-the-driven-ports.md),
  [ADR-023](../ADRs/ADR-023-the-transaction-boundary-is-the-use-case.md),
  [ADR-037](../ADRs/ADR-037-keep-roster-history-as-a-record-in-time.md).*
- **Performance and scale** — one deployment holds up to **100 organisations**; one organisation
  has up to **1,000 employees**; one planning period holds up to **500 shifts**; up to **10
  Schedulers** work in one organisation at once. Interactive requests answer within **300 ms at the
  95th percentile**. Generating a draft at the largest size takes at most **10 minutes**, so it runs
  as a background job ([ADR-046](../ADRs/ADR-046-generate-in-the-background-and-keep-the-job-in-postgresql.md)). Decided on
  2026-09-22 ([#36](https://github.com/NaimElijah/HrSched/issues/36)). *`GenerationAtScaleRun`
  checks generation at the largest size against the configured limits, run by hand rather than in
  the build, but no run of it has yet completed: the one attempt was stopped for want of memory
  before both searches ended (see its javadoc). Nothing measures the rest yet.*
- **Availability** — HrSched is up around the clock, because people read their roster before a
  shift at any hour: **99.9%** of each month. A disaster loses **no committed write**, and service
  is restored within **1 hour**. Decided on 2026-09-22
  ([#36](https://github.com/NaimElijah/HrSched/issues/36)). *Nothing measures or provides this yet;
  there is no production deployment ([#38](https://github.com/NaimElijah/HrSched/issues/38)).*
- **Deployment** — containerised with externalised configuration; the provider is deliberately
  deferred to its own ADR so the choice does not leak into the design.
- **Observability** — every request is traced, and the trace id appears in every log line it
  produces and on the HTTP response, so a report from a person can be joined to what the process
  recorded. Logs are structured and machine-readable on stdout. Liveness and readiness are exposed
  as probes. Rate, latency, error rate, saturation and the product's own coverage measure are
  published for a scraper to pull. Instrumentation is vendor-neutral (OpenTelemetry), and nothing is
  exported until there is somewhere to export to — Q19.
  *Enforced by `TraceIdResponseFilter`, `TraceIdHttpTest`, `MetricsHttpTest`;
  [ADR-016](../ADRs/ADR-016-structured-logs-and-a-propagated-trace-id.md),
  [ADR-017](../ADRs/ADR-017-expose-metrics-to-be-scraped.md). Publishing to a scraper: not yet —
  `/actuator/metrics` and `/actuator/prometheus` are refused to every caller meanwhile
  ([ADR-049](../ADRs/ADR-049-refuse-the-metrics-to-every-caller-until-a-scraper-exists.md),
  [#78](https://github.com/NaimElijah/HrSched/issues/78)).*
- **Auditability** — every state change records who made it, in which organisation, and the
  identifier of what changed; every refusal, including a cross-tenant attempt, is recorded too. The
  record carries identifiers only and never a name, so the trail says who to ask rather than the
  answer. The trail must be **durable**, stored in the same transaction as the change it records,
  and an Admin may read their own organisation's trail and nobody else's (Q20). That is not built
  yet ([#31](https://github.com/NaimElijah/HrSched/issues/31)): today the record exists only in the
  log stream, and a log line cannot be rolled back. How long it is kept is Q23.
  *Enforced by `SchedulingService`, `EmployeeService`, `QualificationService` and their tests;
  `SchedulingAuditLogTest`, `WorkforceAuditLogTest`, `SchedulingExceptionHandlerTest`,
  `WorkforceExceptionHandlerTest`, `SignInNameTakenRefusalHttpTest`;
  [ADR-018](../ADRs/ADR-018-audit-through-a-port-not-a-queryable-trail.md).*
- **Compliance** — availability, absence, and qualification records are personal data across
  multiple client organisations. HrSched must satisfy both Israel's **Protection of Privacy Law,
  5741-1981**, and the **EU GDPR** (Regulation (EU) 2016/679). Each organisation owns its data, and
  HrSched is its **processor** (under Israeli law, its *holder*). Where the data is stored is not
  constrained. Decided on 2026-09-22 ([#36](https://github.com/NaimElijah/HrSched/issues/36)).
  `TODO`: the specific obligations each regime places on a processor
  ([#113](https://github.com/NaimElijah/HrSched/issues/113)), and how long each kind of
  record is kept and what is erased on request — Q23
  ([#41](https://github.com/NaimElijah/HrSched/issues/41)).

---

## Non-goals

Deliberately not written yet, at the repository owner's direction. Candidates worth deciding on
explicitly rather than drifting into: payroll and pay calculation, time and attendance clocking,
applicant tracking, and being the HR system of record.

---

## Open questions

| # | Question | Owner | Blocks |
| --- | --- | --- | --- |
| Q15 | What are the non-goals? | NaimElijah | Deferred deliberately ([#111](https://github.com/NaimElijah/HrSched/issues/111)) |
| Q19 | Which observability backend receives the OpenTelemetry export, and at what sampling rate? | NaimElijah | Turning OTLP export on; sampling below 1.0 ([#112](https://github.com/NaimElijah/HrSched/issues/112)) |
| Q21 | By what channel is a colleague actually informed, and how quickly must that happen? | NaimElijah | Anything beyond the in-app inbox: email, push, an unread badge. Today somebody is informed when they next open the application, which for a rota change may be too late ([ADR-021](../ADRs/ADR-021-notifications-as-a-bounded-context.md)) |
| Q23 | How long is each kind of record kept, and what is erased on request? | NaimElijah | A retention policy and a purge; part of the Compliance TODO, and it constrains Q20 |
| Q30 | What creates a second operator, and how is a lost operator credential rotated? That there may be more than one was decided on 2026-09-22; today configuration creates exactly one, once, and never overwrites it (ADR-029). | NaimElijah | Whether operator accounts get an administration surface of their own ([#75](https://github.com/NaimElijah/HrSched/issues/75)) |

**Resolved**

| # | Question | Answer | Date |
| --- | --- | --- | --- |
| Q1 | Language, runtime, framework | Java 21, Spring Boot, Maven — [ADR-001](../ADRs/ADR-001-java-spring-boot-maven.md) | 2026-08-29 |
| Q2 | Which roles use the system | Employee, Scheduler, Admin | 2026-08-30 |
| Q3 | Single organisation or multi-tenant | Multi-tenant: independent organisations, strict isolation | 2026-08-30 |
| Q4 | Whose working-time rules apply | None hardcoded. A configurable constraint set; jurisdictions are presets | 2026-08-30 |
| Q5 | Where it runs | Containerised, provider deferred to its own ADR | 2026-08-30 |
| Q6 | How rosters are built | Automatic generation is the point — [ADR-005](../ADRs/ADR-005-roster-generation-behind-a-port.md) | 2026-08-30 |
| Q7 | Who administers organisations | A platform Operator, the only identity crossing tenants | 2026-08-30 |
| Q9 | A published roster when rules change | It keeps the version that approved it; changes are never retroactive | 2026-08-30 |
| Q10 | Emergency cover without an override | Supersede with a new compliant roster | 2026-08-30 |
| Q8 | What a constraint set is attached to | One per organisation; per-person variation is contract terms, held separately | 2026-08-30 |
| Q11 | Who approves a swap | A Scheduler; the colleague is informed | 2026-08-30 |
| Q12 | What identity provider, and how an Actor is built from a real credential | None: HrSched holds its own accounts and signs its own access tokens. An `Actor` is built from the stored account and session the token names, on every request — [ADR-011](../ADRs/ADR-011-issue-our-own-access-tokens.md), [ADR-028](../ADRs/ADR-028-keep-the-session-in-memory-and-a-rotating-refresh-cookie.md), [ADR-031](../ADRs/ADR-031-take-the-organisation-from-the-account.md) | 2026-09-21 |
| Q18 | Whether an employee sees their own roster, and what "their own" means | Yes: the assignments naming them, from published rosters only. A narrower capability beside R12, not a widening of it | 2026-09-01 |
| Q22 | Is a swap a transfer of one shift, or a two-way exchange of two? | Both: an employee may offer either (F7). The exchange is [#82](https://github.com/NaimElijah/HrSched/issues/82) | 2026-09-22 |
| Q16 | When a new roster only partly overlaps a published one, is the old one superseded whole, or only for the days replaced? | Whole, as R11 states | 2026-09-22 |
| Q25 | May an employee see a colleague's name, for instance to choose who takes a shift? | No: an Employee learns nothing about colleagues, not even their names. The colleague is named by identifier | 2026-09-22 |
| Q26 | Which roster governs a shift that straddles two roster periods, and is R3 judged across rosters or within one? | Either roster may carry it, and R3 is judged across every authoritative roster — R35, [#98](https://github.com/NaimElijah/HrSched/issues/98) | 2026-09-22 |
| Q32 | Must an account holding the EMPLOYEE role always be linked to an employee? | Yes, and existing unlinked ones are disabled until linked — R34, [#97](https://github.com/NaimElijah/HrSched/issues/97) | 2026-09-22 |
| Q33 | May an assignment have more than one pending swap request, and what becomes of a pending request once its roster is superseded? | One only (R32), and it lapses (R33) — [#94](https://github.com/NaimElijah/HrSched/issues/94) | 2026-09-22 |
| Q13 | How are the generator objectives weighted against one another? | Each organisation weights them itself, as configuration — [ADR-010](../ADRs/ADR-010-score-a-roster-by-three-objectives-each-organisation-weights.md), [#27](https://github.com/NaimElijah/HrSched/issues/27) | 2026-09-22 |
| Q14 | Over what reference period is a weekly maximum averaged? | A reference period configured in the constraint set — [#104](https://github.com/NaimElijah/HrSched/issues/104) | 2026-09-22 |
| Q17 | Which time zone are working-time rules evaluated in? | Each shift's own — R36, [#29](https://github.com/NaimElijah/HrSched/issues/29) | 2026-09-22 |
| Q20 | Should an Admin be able to read their organisation's audit trail? | Yes: the trail is durable, stored with the change it records, and an Admin reads their own organisation's only — [#31](https://github.com/NaimElijah/HrSched/issues/31). Its retention is Q23 | 2026-09-22 |
| Q24 | Must roster history be append-only? | Yes — R37, [#106](https://github.com/NaimElijah/HrSched/issues/106) | 2026-09-22 |
| Q27 | Are the security defaults right? | Yes, all of them, as stated under *Authentication and authorisation* | 2026-09-22 |
| Q28 | How does a tenant recover when its only Admin cannot sign in? | The Operator resets that Admin's password after verifying the request outside the application, as a deliberate exception to ADR-030 — F33, [#107](https://github.com/NaimElijah/HrSched/issues/107) | 2026-09-22 |
| Q29 | Must a sign-in name be an email address? | Yes — R38, [#108](https://github.com/NaimElijah/HrSched/issues/108) | 2026-09-22 |
| Q31 | Is multi-factor authentication required, and for which roles? | Yes, for Operators and Admins — R39, [#109](https://github.com/NaimElijah/HrSched/issues/109) | 2026-09-22 |

Question numbers are never reused. The answers above raised Q12 to Q15; modelling raised Q16 and Q17; building the UI raised Q18; instrumenting the backend raised Q19 and Q20; building the notification inbox raised Q21; building swaps raised Q22; giving the system a database raised Q23 and Q24; putting an employee's side of swaps into the user interface raised Q25; authentication raised
Q27 to Q32; and Q33 numbers the two questions [#55](https://github.com/NaimElijah/HrSched/issues/55)
raised about pending swaps, answered as they were numbered.
