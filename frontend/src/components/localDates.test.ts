import { describe, expect, it } from 'vitest';
import {
  dayLabel,
  defaultSpan,
  localDate,
  shiftLabel,
  startOfLocalDay,
  timeLabel,
} from './localDates';

/**
 * These functions are zone-dependent on purpose — that is what the hazard in their comments is
 * about — so nothing here asserts a literal instant. A developer machine and the CI runner sit in
 * different zones, and a test pinned to an absolute time would pass in one and fail in the other
 * for reasons that have nothing to do with the code.
 *
 * Every assertion is therefore a round trip or a relative property, both of which hold everywhere.
 */

describe('reading a date input', () => {
  it('GivenAnEmptyField_WhenTakingTheStartOfTheLocalDay_ThenThereIsNoInstant', () => {
    expect(startOfLocalDay('')).toBeNull();
  });

  it.each(['not-a-date', '2026-13-45', 'yesterday'])(
    'GivenAnUnparseableField_WhenTakingTheStartOfTheLocalDay_ThenThereIsNoInstant (%s)',
    (value) => {
      expect(startOfLocalDay(value)).toBeNull();
    },
  );

  it('GivenADate_WhenTakingTheStartOfTheLocalDay_ThenAnInstantComesBack', () => {
    expect(startOfLocalDay('2026-10-05')).toMatch(/^\d{4}-\d{2}-\d{2}T/);
  });
});

describe('the local day an instant falls on', () => {
  it('GivenALocalDate_WhenRoundTrippedThroughAnInstant_ThenTheSameDateComesBack', () => {
    const instant = startOfLocalDay('2026-10-05');

    expect(instant).not.toBeNull();
    expect(localDate(instant as string)).toBe('2026-10-05');
  });

  it.each(['2026-01-01', '2026-06-15', '2026-12-31'])(
    'GivenAnyDateInTheYear_WhenRoundTripped_ThenItSurvives (%s)',
    (date) => {
      expect(localDate(startOfLocalDay(date) as string)).toBe(date);
    },
  );

  /**
   * The documented hazard: a span built from UTC midnight excludes shifts the reader has just
   * created, because west of Greenwich an evening lands on the next UTC day. Late local evening is
   * where that goes wrong, so it is asserted rather than reasoned about.
   */
  it('GivenAnInstantLateOnALocalEvening_WhenAskedForItsDate_ThenItIsTheDayTheUserWouldRead', () => {
    const lateEvening = new Date(2026, 9, 5, 23, 30, 0);

    expect(localDate(lateEvening.toISOString())).toBe('2026-10-05');
  });

  it('GivenAnInstantJustAfterLocalMidnight_WhenAskedForItsDate_ThenItIsTheNewDay', () => {
    const justAfterMidnight = new Date(2026, 9, 6, 0, 30, 0);

    expect(localDate(justAfterMidnight.toISOString())).toBe('2026-10-06');
  });
});

describe('labelling an instant for a reader', () => {
  it('GivenTwoInstantsOnTheSameLocalDay_WhenLabellingTheirDays_ThenTheLabelsAgree', () => {
    const morning = new Date(2026, 9, 5, 8, 0, 0).toISOString();
    const evening = new Date(2026, 9, 5, 22, 0, 0).toISOString();

    expect(dayLabel(morning)).toBe(dayLabel(evening));
  });

  it('GivenInstantsOnConsecutiveLocalDays_WhenLabellingTheirDays_ThenTheLabelsDiffer', () => {
    const lateEvening = new Date(2026, 9, 5, 23, 30, 0).toISOString();
    const justAfterMidnight = new Date(2026, 9, 6, 0, 30, 0).toISOString();

    expect(dayLabel(lateEvening)).not.toBe(dayLabel(justAfterMidnight));
  });

  /** One sentence for a shift, so the board, the queue, the inbox and Me cannot describe it apart. */
  it('GivenAShift_WhenDescribingIt_ThenItReadsAsItsTimesOnItsDay', () => {
    const start = new Date(2026, 9, 5, 8, 0, 0).toISOString();
    const end = new Date(2026, 9, 5, 16, 0, 0).toISOString();

    expect(shiftLabel(start, end)).toBe(
      `${timeLabel(start)} to ${timeLabel(end)} on ${dayLabel(start)}`,
    );
  });

  /** A shift is read to the minute; seconds would be noise nobody entered. */
  it('GivenInstantsWithinOneMinute_WhenLabellingTheirTimes_ThenTheLabelsAgree', () => {
    const onTheMinute = new Date(2026, 9, 5, 8, 0, 0).toISOString();
    const halfAMinuteLater = new Date(2026, 9, 5, 8, 0, 30).toISOString();

    expect(timeLabel(onTheMinute)).toBe(timeLabel(halfAMinuteLater));
  });
});

describe('the default planning span', () => {
  it('GivenAFixedClock_WhenTakingTheSpan_ThenItStartsOnTodaysLocalDate', () => {
    const span = defaultSpan(new Date(2026, 9, 5, 13, 45, 0));

    expect(localDate(span.from)).toBe('2026-10-05');
  });

  it('GivenAFixedClock_WhenTakingTheSpan_ThenItRunsAFortnight', () => {
    const span = defaultSpan(new Date(2026, 9, 5, 13, 45, 0));
    const days =
      (new Date(span.to).getTime() - new Date(span.from).getTime()) / (24 * 60 * 60 * 1000);

    expect(days).toBe(14);
  });

  it('GivenAnAfternoonClock_WhenTakingTheSpan_ThenItStartsAtLocalMidnightNotNow', () => {
    const afternoon = new Date(2026, 9, 5, 13, 45, 0);

    const span = defaultSpan(afternoon);

    expect(new Date(span.from).getHours()).toBe(0);
    expect(new Date(span.from).getMinutes()).toBe(0);
  });

  /** The regression guard for the hazard: neither end may shift when shown in a date input. */
  it('GivenTheSpan_WhenRoundTrippedThroughTheDateInputs_ThenNeitherEndShifts', () => {
    const span = defaultSpan(new Date(2026, 9, 5, 13, 45, 0));

    expect(startOfLocalDay(localDate(span.from))).toBe(span.from);
    expect(localDate(startOfLocalDay(localDate(span.to)) as string)).toBe(localDate(span.to));
  });

  it('GivenTheSameClockTwice_WhenTakingTheSpan_ThenTheAnswerIsTheSame', () => {
    const now = new Date(2026, 9, 5, 13, 45, 0);

    expect(defaultSpan(now)).toEqual(defaultSpan(now));
  });
});
