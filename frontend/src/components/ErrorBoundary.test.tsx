import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ReactNode } from 'react';
import { ErrorBoundary } from './ErrorBoundary';

/**
 * React unmounts the whole root when a render throws and nothing catches it, so without this
 * boundary a single unexpected shape from the API leaves the user looking at white space.
 *
 * Both React and the boundary write to `console.error`, so it is spied on rather than left to fill
 * the test output. `vi.spyOn(console, 'error')` passes `console` as a plain identifier and does not
 * trip the repository's no-console rule; assigning to `console.error` would.
 */

let logged: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  logged = vi.spyOn(console, 'error').mockImplementation(() => {});
});

afterEach(() => {
  logged.mockRestore();
});

/** Always throws; the explicit return type is needed because an inferred `never` is not a JSX
 * component as far as TypeScript is concerned. */
function Throws({ message = 'the render broke' }: { message?: string }): ReactNode {
  throw new Error(message);
}

describe('catching a broken render', () => {
  it('GivenAChildThatThrows_WhenRendered_ThenTheFailureIsShownRatherThanABlankPage', () => {
    render(
      <ErrorBoundary>
        <Throws />
      </ErrorBoundary>,
    );

    expect(screen.getByRole('alert')).toHaveTextContent('Something in the interface broke.');
  });

  it('GivenAChildThatThrows_WhenRendered_ThenTheMessageIsShown', () => {
    render(
      <ErrorBoundary>
        <Throws message="shifts.map is not a function" />
      </ErrorBoundary>,
    );

    expect(screen.getByText('shifts.map is not a function')).toBeInTheDocument();
  });

  /** The comment on componentDidCatch promises the stack is not lost. Nothing enforced it. */
  it('GivenAChildThatThrows_WhenCaught_ThenTheFailureIsLoggedRatherThanSwallowed', () => {
    render(
      <ErrorBoundary>
        <Throws message="shifts.map is not a function" />
      </ErrorBoundary>,
    );

    expect(logged).toHaveBeenCalledWith(
      'HrSched failed to render',
      expect.objectContaining({ message: 'shifts.map is not a function' }),
      expect.anything(),
    );
  });
});

describe('recovering', () => {
  it('GivenAChildThatDoesNotThrow_WhenRendered_ThenItIsShownUntouched', () => {
    render(
      <ErrorBoundary>
        <p>the application</p>
      </ErrorBoundary>,
    );

    expect(screen.getByText('the application')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('GivenACaughtFailure_WhenTryAgainIsClicked_ThenTheChildrenRenderAgain', async () => {
    let broken = true;
    function SometimesThrows() {
      if (broken) {
        throw new Error('the render broke');
      }
      return <p>recovered</p>;
    }
    render(
      <ErrorBoundary>
        <SometimesThrows />
      </ErrorBoundary>,
    );
    expect(screen.getByRole('alert')).toBeInTheDocument();

    broken = false;
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));

    expect(screen.getByText('recovered')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});

/**
 * The fallback replaces the whole application, its landmarks with it, so it is the main content
 * itself. Pressing "Try again" removes the button that had focus, so focus moves to the first
 * heading of what came back, never to the page (#257, C2 and C4).
 */
describe('what the fallback offers a screen-reader user', () => {
  it('GivenAChildThatThrows_WhenRendered_ThenTheFailureIsInTheMainLandmark', () => {
    render(
      <ErrorBoundary>
        <Throws />
      </ErrorBoundary>,
    );

    expect(screen.getByRole('main'), 'the main landmark').toContainElement(
      screen.getByRole('alert'),
    );
  });

  it('GivenACaughtFailure_WhenTryAgainRendersTheChildren_ThenFocusIsOnTheirFirstHeading', async () => {
    let broken = true;
    function SometimesThrows() {
      if (broken) {
        throw new Error('the render broke');
      }
      return (
        <section>
          <p>before any heading</p>
          <h1>The application</h1>
          <h2>A view in it</h2>
        </section>
      );
    }
    render(
      <ErrorBoundary>
        <SometimesThrows />
      </ErrorBoundary>,
    );

    broken = false;
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));

    const heading = screen.getByRole('heading', { name: 'The application' });
    await waitFor(() => expect(heading, 'focus on the first heading').toHaveFocus());
  });
});
