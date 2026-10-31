package io.github.naimelijah.hrsched.scheduling.application.port.out;

import io.github.naimelijah.hrsched.scheduling.domain.model.Roster;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterNotEditableException;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterPublicationConflictException;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.List;
import java.util.Optional;

/** Driven port: storing rosters, scoped by organisation. */
public interface RosterRepository {

  /**
   * Records the state a roster has reached, without rewriting anything already recorded.
   *
   * <p>History is append-only (R37, ADR-037). A draft may be stored over its earlier self until it
   * is published; publishing records when; superseding adds when that happened, and nothing else.
   * Storing anything over a published roster except its supersession is refused, so a stale copy of
   * a draft cannot turn a published roster back into one.
   *
   * @param roster the roster as it now is
   * @throws RosterNotEditableException if what is stored has moved past what this save expects: a
   *     published roster stored over, or a roster superseded that is not currently published
   * @throws RosterPublicationConflictException if it is published and overlaps another roster of
   *     its organisation published over the same moments. Publication supersedes before it
   *     publishes, and takes turns through {@link #lockPublicationsOf}, so this means another
   *     writer published without taking that lock (ADR-026, ADR-037)
   * @throws IllegalStateException if its organisation holds no roster with that identity where one
   *     must already be stored, or another organisation holds it
   */
  void save(Roster roster);

  /**
   * Makes every other publication for this organisation wait until the calling use case finishes.
   *
   * <p>Publishing decides what to supersede from what it reads, so two publications reading at once
   * would each miss the other. Taken before that read, the second publication reads what the first
   * committed (ADR-027). Taking it again within the same use case is harmless.
   *
   * <p>Raising and rejecting a swap take it too, because a publication lapses the requests on what
   * it supersedes (R33): a request raised against a roster being superseded would miss the lapse,
   * and a rejection landing mid-publication would refuse it (ADR-039).
   *
   * @param organisation whose publications take turns
   * @throws IllegalStateException if called outside the unit of work a use case runs in, where
   *     there is nothing to hold the lock until
   */
  void lockPublicationsOf(OrganisationId organisation);

  /**
   * @param organisation whose roster; another organisation's is never found
   * @param id which roster
   * @return the roster, empty if this organisation holds none with that identity
   */
  Optional<Roster> findById(OrganisationId organisation, RosterId id);

  /**
   * Reads a roster and holds it until the calling use case finishes, so another use case reading it
   * the same way waits, and then reads what this one stored.
   *
   * <p>For a use case that changes the roster it reads. A roster is stored whole, so two edits
   * reading it at once would each store over the other's, and a publication reading it beside an
   * edit would store the copy it judged over that edit (#33, ADR-041). A use case that only reads
   * uses {@link #findById}: it runs read-only, where nothing can be held.
   *
   * @param organisation whose roster it must be
   * @param id the roster to read and hold
   * @return the roster, if this organisation holds one with that identity
   * @throws IllegalStateException if called outside a writable unit of work, where there is nothing
   *     to hold the roster until
   */
  Optional<Roster> findForUpdate(OrganisationId organisation, RosterId id);

  /**
   * @param organisation whose rosters
   * @return every roster that organisation holds, drafts, published and superseded, in no
   *     particular order
   */
  List<Roster> findByOrganisation(OrganisationId organisation);
}
