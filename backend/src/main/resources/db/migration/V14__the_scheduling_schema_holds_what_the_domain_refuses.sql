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
