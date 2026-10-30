-- The scheduling schema refuses what its domain refuses, where V1 to V13 left that to the domain
-- alone (#255). See ADR-004.
--
-- V1 said constraints mirror domain invariants and express nothing the domain does not. Each
-- constraint added below mirrors a rule the domain already enforces, so none is a new rule
-- smuggled in as a storage detail. A migration that has run cannot be edited, so what this makes
-- of V1's comments is corrected here, as V6 and V8 did.

-- R24 --------------------------------------------------------------------------------------------
--
-- A constraint set holds at most one constraint of each kind (R24, #96). V1 listed two constraints
-- of one kind in a set among the untidy things the model permitted, and keyed
-- working_time_constraint by ordinal because ConstraintSet was a plain list that permitted two of a
-- kind. ConstraintSet now refuses them, so the schema refuses them too, for a writer that skips the
-- domain. The ordinal stays the key: it is the order the rules were given in, which reads back as
-- it was stored.
--
-- JdbcConstraintSetRepository translates only constraint_set's primary key, as a racing revision,
-- so a violation of this one surfaces as the store reports it.
--
-- A database already holding two of a kind fails here, deliberately, as V2 does for two published
-- rosters: which of the two limits the organisation meant is not something a migration can decide.
-- Such a set could not be read since #96, so its organisation could neither revise, generate nor
-- publish; delete the row that is wrong by hand and migrate again.

ALTER TABLE working_time_constraint
    ADD CONSTRAINT working_time_constraint_one_of_each_kind UNIQUE (organisation_id, version, kind);

-- A roster is named only within its organisation, and only as it was stored -----------------
--
-- A generation names the draft it produced, and a swap request the assignment it asks to hand
-- over. Both are rows of the organisation that holds them. roster_generation's foreign key (V12)
-- named the roster by identity alone, so a generation of one organisation could name another's
-- roster; swap_request had none at all, so a request could name a roster, or an assignment, that
-- does not exist. Every read of a roster is scoped to its organisation, so the roster such a row
-- named would not be shown to the wrong tenant, but its identity would be, in the generation read
-- back, and the row itself is one forgotten predicate from more (ADR-009).
--
-- The roster's identity is made unique within its organisation as well as alone, so the two can be
-- named together. That index leads with organisation_id and so answers every lookup
-- roster_by_organisation did, which goes.
--
-- A swap request names its roster with its organisation, and the requester's assignment on it. The
-- service raises one only for an assignment of a published roster, and a published roster's
-- assignments are never deleted or rewritten: JdbcRosterRepository deletes and reinserts them only
-- once its guarded upsert has found the row still a draft, a supersession writes superseded_at and
-- nothing else, and approving a swap publishes a replacement roster rather than editing the one
-- the request names (ADR-022). So nothing deletes an assignment a request names, and were anything
-- to, this refuses it.
--
-- That foreign key has an index of its own, which departs from the rule that an index on a root
-- leads with organisation_id (ADR-004). It answers no repository lookup, but the check PostgreSQL
-- runs on every assignment a draft save deletes, which carries no organisation because
-- roster_assignment has none: without it, each deleted assignment is checked by reading the whole
-- of swap_request, which grows without end. Measured on an invented 200,000 requests, deleting a
-- draft's 2,000 assignments took 187 s without it and 0.18 s with it.
--
-- Each foreign key is added NOT VALID and then validated. A database already holding a reference
-- that dangles, or crosses organisations, fails here, deliberately: which roster such a row meant is
-- not something a migration can decide.
--
-- None of these is translated. Each refuses only a writer that went past the domain, and its
-- violation surfaces as the store reports it.
--
-- V1 said the five child tables carry no organisation_id. roster_generation_violation, added by
-- V12, is a sixth: it is reached only through its generation, which does. V1 also gave
-- roster_assignment no foreign key to shift, explaining it by context, but only employee belongs
-- to another context; shift is this one's own table. roster_assignment.shift_id still has no
-- foreign key to shift, and nothing records why.

ALTER TABLE roster
    ADD CONSTRAINT roster_identity_within_organisation UNIQUE (organisation_id, id);

DROP INDEX roster_by_organisation;

ALTER TABLE roster_generation DROP CONSTRAINT roster_generation_roster_id_fkey;
ALTER TABLE roster_generation
    ADD CONSTRAINT roster_generation_names_a_draft_of_its_organisation
        FOREIGN KEY (organisation_id, roster_id) REFERENCES roster (organisation_id, id) NOT VALID;
ALTER TABLE roster_generation
    VALIDATE CONSTRAINT roster_generation_names_a_draft_of_its_organisation;

ALTER TABLE swap_request
    ADD CONSTRAINT swap_request_names_a_roster_of_its_organisation
        FOREIGN KEY (organisation_id, roster_id) REFERENCES roster (organisation_id, id) NOT VALID,
    ADD CONSTRAINT swap_request_names_an_assignment_on_its_roster
        FOREIGN KEY (roster_id, shift_id, requester_id)
        REFERENCES roster_assignment (roster_id, shift_id, employee_id) NOT VALID;
ALTER TABLE swap_request VALIDATE CONSTRAINT swap_request_names_a_roster_of_its_organisation;
ALTER TABLE swap_request VALIDATE CONSTRAINT swap_request_names_an_assignment_on_its_roster;

CREATE INDEX swap_request_by_assignment ON swap_request (roster_id, shift_id, requester_id);

-- A generation ends while it still could -------------------------------------------------------
--
-- RosterGeneration ends at the clock's reading, or at its request if the clock was stepped back
-- before it, and refuses to end once it is lost, from lost_at on (ADR-046). So no ending it writes
-- is before its request or after it was lost, and the schema now refuses one that is, for a writer
-- that skips the domain. The ending may equal lost_at: the domain allows one up to the nanosecond
-- before, and timestamptz rounds an instant to the nearest microsecond, so an ending in the last
-- half microsecond is stored at lost_at itself.
--
-- That a REFUSED generation, and only a REFUSED one, has rows in roster_generation_violation is NOT
-- held here, deliberately. A check sees one row of one table, and those rows are written after their
-- generation's, so holding it would take a deferred constraint trigger. RosterGeneration refuses to
-- be loaded from a row that breaks it, so such a row fails loudly when it is read.

ALTER TABLE roster_generation
    ADD CONSTRAINT roster_generation_finished_while_it_could
        CHECK (finished_at IS NULL OR (finished_at >= requested_at AND finished_at <= lost_at));
