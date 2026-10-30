import { describe, expect, it } from 'vitest';
import {
  dayLabel,
  defaultSpan,
  localDate,
  shiftLabel,
  spanLabel,
  startOfLocalDay,
  timeLabel,
} from './localDates';

/**
 * These functions are zone-dependent on purpose — that is what the hazard in their comments is
 * about. Every run reads local time in America/Los_Angeles, pinned by `test.env` in vite.config.ts
 * whatever the machine's own zone is, so a developer machine and the CI runner agree.
 *
 * The zone is west of Greenwich on purpose. CI runs in UTC, where local time and UTC agree and a
 * function that used UTC by mistake would still pass every round trip below. Each literal instant
 * here is the Los Angeles wall-clock time it names, in daylight saving time (UTC-7) in October.
 */

/** Local midnight on 5 October 2026 in the pinned zone, as an instant. */
const MIDNIGHT_ON_THE_5TH = '2026-10-05T07:00:00.000Z';

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

  it('GivenADate_WhenTakingTheStartOfTheLocalDay_ThenItIsLocalMidnightNotUtcMidnight', () => {
    expect(startOfLocalDay('2026-10-05')).toBe(MIDNIGHT_ON_THE_5TH);
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
    // 23:30 on the 5th in Los Angeles, which is already the 6th in UTC.
    const lateEvening = '2026-10-06T06:30:00.000Z';

    expect(localDate(lateEvening)).toBe('2026-10-05');
  });

  it('GivenAnInstantJustAfterLocalMidnight_WhenAskedForItsDate_ThenItIsTheNewDay', () => {
    // 00:30 on the 6th in Los Angeles.
    const justAfterMidnight = '2026-10-06T07:30:00.000Z';

    expect(localDate(justAfterMidnight)).toBe('2026-10-06');
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

  it('GivenASpanWithinOneLocalDay_WhenDescribingIt_ThenItReadsAsAShiftDoes', () => {
    const start = new Date(2026, 9, 5, 8, 0, 0).toISOString();
    const end = new Date(2026, 9, 5, 16, 0, 0).toISOString();

    expect(spanLabel(start, end)).toBe(shiftLabel(start, end));
  });

  /** An absence can last days; naming only the day it starts would hide where it ends. */
  it('GivenASpanAcrossLocalDays_WhenDescribingIt_ThenBothDaysAreNamed', () => {
    const start = new Date(2026, 9, 5, 8, 0, 0).toISOString();
    const end = new Date(2026, 9, 7, 16, 0, 0).toISOString();

    expect(spanLabel(start, end)).toBe(
      `${timeLabel(start)} on ${dayLabel(start)} to ${timeLabel(end)} on ${dayLabel(end)}`,
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

    expect(span.from).toBe(MIDNIGHT_ON_THE_5TH);
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
