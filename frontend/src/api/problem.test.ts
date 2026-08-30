import { describe, expect, it } from 'vitest';
import { ApiError, toApiError } from './problem';

/**
 * The boundary where an untyped failure body becomes a typed one.
 *
 * This module validates rather than casts, which only means something if the invalid shapes are
 * actually exercised. Until now it was reached only through `client.test.ts`, and the paths that
 * matter most — a body that is not an object, a detail that is not a string — were never taken.
 */

describe('a body that is not the shape we expect', () => {
  it.each([[null], [undefined], ['plain text'], [42], [true]])(
    'GivenABodyThatIsNotAnObject_WhenTranslating_ThenOnlyTheStatusIsReported',
    (body) => {
      const error = toApiError(500, body);

      expect(error).toBeInstanceOf(ApiError);
      expect(error.status).toBe(500);
      expect(error.message).toBe('The server returned 500.');
      expect(error.violations).toEqual([]);
    },
  );

  /**
   * `typeof [] === 'object'`, so an array takes the record path. Nothing may be invented from it.
   */
  it('GivenAnArrayBody_WhenTranslating_ThenNoFieldIsInvented', () => {
    const error = toApiError(400, ['not', 'a', 'problem', 'detail']);

    expect(error.message).toBe('The server returned 400.');
    expect(error.violations).toEqual([]);
  });
});

describe('choosing the message', () => {
  it('GivenADetail_WhenTranslating_ThenTheDetailBecomesTheMessage', () => {
    expect(toApiError(400, { detail: 'name is required' }).message).toBe('name is required');
  });

  it('GivenOnlyATitle_WhenTranslating_ThenTheTitleBecomesTheMessage', () => {
    expect(toApiError(400, { title: 'Invalid request' }).message).toBe('Invalid request');
  });

  it('GivenBothATitleAndADetail_WhenTranslating_ThenTheDetailWins', () => {
    const error = toApiError(400, { title: 'Invalid request', detail: 'name is required' });

    expect(error.message).toBe('name is required');
  });

  it.each([[42], [null], [{ nested: true }], [['a']]])(
    'GivenADetailThatIsNotAString_WhenTranslating_ThenItFallsBackRatherThanCasting',
    (detail) => {
      expect(toApiError(400, { detail }).message).toBe('The server returned 400.');
    },
  );

  it('GivenANonStringTitleAndAStringDetail_WhenTranslating_ThenTheDetailIsStillUsed', () => {
    expect(toApiError(400, { title: 7, detail: 'name is required' }).message).toBe(
      'name is required',
    );
  });
});

describe('violations', () => {
  const valid = { employee: 'ada', constraint: 'maximum daily working time', detail: 'PT12H' };

  it('GivenWellFormedViolations_WhenTranslating_ThenTheyAreKept', () => {
    expect(toApiError(422, { violations: [valid] }).violations).toEqual([valid]);
  });

  it.each([['a string'], [42], [null], [{ employee: 1 }], [{ employee: 'ada' }], [{}]])(
    'GivenAnEntryThatIsNotAViolation_WhenTranslating_ThenItIsDiscarded',
    (entry) => {
      expect(toApiError(422, { violations: [valid, entry] }).violations).toEqual([valid]);
    },
  );

  it.each([['not an array'], [42], [null], [{ employee: 'ada' }]])(
    'GivenAViolationsFieldThatIsNotAnArray_WhenTranslating_ThenThereAreNone',
    (violations) => {
      expect(toApiError(422, { violations }).violations).toEqual([]);
    },
  );

  it('GivenNoViolationsField_WhenTranslating_ThenThereAreNone', () => {
    expect(toApiError(422, { detail: 'Refused' }).violations).toEqual([]);
  });
});

describe('what a status means', () => {
  it('GivenARuleBreach_WhenAsked_ThenOnlyThatPredicateIsTrue', () => {
    const error = toApiError(422, {});

    expect(error.isRuleBreach).toBe(true);
    expect(error.isForbidden).toBe(false);
    expect(error.isNotFound).toBe(false);
  });

  it('GivenAForbidden_WhenAsked_ThenOnlyThatPredicateIsTrue', () => {
    const error = toApiError(403, {});

    expect(error.isForbidden).toBe(true);
    expect(error.isRuleBreach).toBe(false);
    expect(error.isNotFound).toBe(false);
  });

  it('GivenANotFound_WhenAsked_ThenOnlyThatPredicateIsTrue', () => {
    const error = toApiError(404, {});

    expect(error.isNotFound).toBe(true);
    expect(error.isForbidden).toBe(false);
    expect(error.isRuleBreach).toBe(false);
  });

  it('GivenAServerFailure_WhenAsked_ThenNoRefusalPredicateIsTrue', () => {
    const error = toApiError(500, {});

    expect(error.isRuleBreach).toBe(false);
    expect(error.isForbidden).toBe(false);
    expect(error.isNotFound).toBe(false);
  });
});
