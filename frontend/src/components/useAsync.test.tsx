import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { useAsync } from './useAsync';

/**
 * The only state machine in the application, and until now the only one nothing asserted.
 *
 * Three of its behaviours are invisible from any page test and would be deleted without a single
 * failure: that a stale answer never reaches the screen, the derivation of `busy` from a key
 * comparison rather than a setState inside the effect, and the loader held in a ref so it can close
 * over fresh props.
 *
 * Both guards are load-bearing and both are covered. The `live` flag stops a late answer being
 * stored at all; the `settled.key === wanted` comparison in the return stops one being shown.
 * Removing either fails this file — checked, in both directions.
 *
 * Flushing matters here more than anywhere else in the suite. An earlier version of the
 * stale-answer test awaited a single microtask, which does not flush a React re-render, so it
 * asserted against the previous DOM and passed with the `live` flag deleted. It reported a
 * property the hook does not have. Anything that resolves a promise and then inspects the tree
 * needs a macrotask and a `waitFor`, not `await Promise.resolve()`.
 */

/** A promise whose settlement this test decides, so response ordering is chosen and never raced. */
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

function Probe({ load, asked }: { load: () => Promise<string>; asked: string }) {
  const { value, error, busy, reload } = useAsync(load, asked);
  return (
    <div>
      <span data-testid="value">{value ?? 'none'}</span>
      <span data-testid="error">{error === null ? 'none' : String(error)}</span>
      <span data-testid="busy">{busy ? 'busy' : 'settled'}</span>
      <button onClick={reload}>Reload</button>
    </div>
  );
}

const value = () => screen.getByTestId('value').textContent;
const failure = () => screen.getByTestId('error').textContent;
const busy = () => screen.getByTestId('busy').textContent;

describe('holding the three states a caller has to render', () => {
  it('GivenALoadThatResolves_WhenMounted_ThenTheValueIsHeldAndBusyClears', async () => {
    render(<Probe load={() => Promise.resolve('shifts')} asked="a" />);

    await waitFor(() => expect(value()).toBe('shifts'));
    expect(busy()).toBe('settled');
    expect(failure()).toBe('none');
  });

  it('GivenALoadThatRejects_WhenMounted_ThenTheFailureIsHeldRatherThanSwallowed', async () => {
    render(<Probe load={() => Promise.reject(new Error('refused'))} asked="a" />);

    await waitFor(() => expect(failure()).toContain('refused'));
    expect(value()).toBe('none');
    expect(busy()).toBe('settled');
  });

  /**
   * A loader can fail before it has a promise to return — a path it must refuse to build, for one.
   * That failure is held like any other, rather than thrown out of the effect, where it would take
   * the whole page down with it.
   */
  it('GivenALoaderThatThrowsBeforeReturningAPromise_WhenMounted_ThenTheFailureIsHeld', async () => {
    render(
      <Probe
        load={() => {
          throw new Error('refused before asking');
        }}
        asked="a"
      />,
    );

    await waitFor(() => expect(failure()).toContain('refused before asking'));
    expect(busy()).toBe('settled');
  });

  it('GivenALoadStillInFlight_WhenRendered_ThenItReportsBusy', () => {
    render(<Probe load={() => deferred<string>().promise} asked="a" />);

    expect(busy()).toBe('busy');
    expect(value()).toBe('none');
  });
});

describe('when the question changes', () => {
  /**
   * A user who changes the span twice must not be shown the first answer because it arrived last.
   * Verified to bite in both directions: deleting the `live` flag fails this test, and replacing
   * the keyed return with a bare `settled.value` fails the one below it.
   */
  it('GivenAKeyChange_WhenTheFirstResponseArrivesLast_ThenItIsDropped', async () => {
    const first = deferred<string>();
    const second = deferred<string>();
    const { rerender } = render(<Probe load={() => first.promise} asked="a" />);

    rerender(<Probe load={() => second.promise} asked="b" />);
    second.resolve('second answer');
    await waitFor(() => expect(value()).toBe('second answer'));

    first.resolve('first answer');
    await new Promise((settle) => setTimeout(settle, 0));

    await waitFor(() => expect(value()).toBe('second answer'));
  });

  it('GivenAKeyChange_WhenTheAnswerIsStillInFlight_ThenItIsBusyAgain', async () => {
    const first = deferred<string>();
    const { rerender } = render(<Probe load={() => first.promise} asked="a" />);
    first.resolve('first answer');
    await waitFor(() => expect(value()).toBe('first answer'));

    rerender(<Probe load={() => deferred<string>().promise} asked="b" />);

    expect(busy()).toBe('busy');
  });

  it('GivenAKeyChange_WhenTheNewAnswerHasNotArrived_ThenTheOldValueIsNotShown', async () => {
    const first = deferred<string>();
    const { rerender } = render(<Probe load={() => first.promise} asked="a" />);
    first.resolve('first answer');
    await waitFor(() => expect(value()).toBe('first answer'));

    rerender(<Probe load={() => deferred<string>().promise} asked="b" />);

    expect(value()).toBe('none');
  });

  it('GivenAHeldFailure_WhenTheKeyChanges_ThenTheOldFailureIsNotShown', async () => {
    const { rerender } = render(
      <Probe load={() => Promise.reject(new Error('refused'))} asked="a" />,
    );
    await waitFor(() => expect(failure()).toContain('refused'));

    rerender(<Probe load={() => deferred<string>().promise} asked="b" />);

    expect(failure()).toBe('none');
  });
});

describe('reloading', () => {
  it('GivenAFailure_WhenReloaded_ThenTheLoaderRunsAgainAndCanSucceed', async () => {
    let attempt = 0;
    const load = () => {
      attempt += 1;
      return attempt === 1 ? Promise.reject(new Error('refused')) : Promise.resolve('recovered');
    };
    render(<Probe load={load} asked="a" />);
    await waitFor(() => expect(failure()).toContain('refused'));

    await userEvent.click(screen.getByRole('button', { name: 'Reload' }));

    await waitFor(() => expect(value()).toBe('recovered'));
    expect(failure()).toBe('none');
  });

  /**
   * The loader ref. The key has not changed, so only a loader re-read on every render can see the
   * new prop — which is the whole reason the ref exists.
   */
  it('GivenAChangedLoaderButAnUnchangedKey_WhenReloaded_ThenTheFreshLoaderRuns', async () => {
    const { rerender } = render(<Probe load={() => Promise.resolve('first')} asked="a" />);
    await waitFor(() => expect(value()).toBe('first'));

    rerender(<Probe load={() => Promise.resolve('second')} asked="a" />);
    await userEvent.click(screen.getByRole('button', { name: 'Reload' }));

    await waitFor(() => expect(value()).toBe('second'));
  });

  /**
   * The same question asked again. Clearing the answer while it was re-fetched unmounted everything
   * rendered from it — a list, and the keyboard focus inside it — after every change a page made.
   * A different question still clears at once; that is the stale-answer guard above.
   */
  it('GivenAHeldValue_WhenReloaded_ThenItStaysOnScreenUntilTheNewAnswerArrives', async () => {
    const before = deferred<string>();
    const after = deferred<string>();
    const answers = [before.promise, after.promise];
    render(
      <Probe
        load={() => answers.shift() ?? Promise.reject(new Error('asked too often'))}
        asked="a"
      />,
    );
    before.resolve('before');
    await waitFor(() => expect(value()).toBe('before'));

    await userEvent.click(screen.getByRole('button', { name: 'Reload' }));

    expect(busy()).toBe('busy');
    expect(value()).toBe('before');
    after.resolve('after');
    await waitFor(() => expect(value()).toBe('after'));
  });

  it('GivenAnUnchangedKey_WhenTheParentReRenders_ThenNothingIsFetchedAgain', async () => {
    const load = vi.fn(() => Promise.resolve('shifts'));
    const { rerender } = render(<Probe load={load} asked="a" />);
    await waitFor(() => expect(value()).toBe('shifts'));

    rerender(<Probe load={load} asked="a" />);

    expect(load).toHaveBeenCalledTimes(1);
  });
});
