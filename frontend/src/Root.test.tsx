import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { endSession } from './api/session';
import { Root } from './Root';
import { routeTo } from './test-support/routeTo';
import { A_TENANT } from './test-support/session';

/**
 * The application as `main.tsx` mounts it: `Root` is the one place the boundary and the session
 * provider are nested, so a test that nested them itself would pass whatever the app does. It is
 * rendered rather than `main.tsx` imported, so the tree is unmounted after the test instead of
 * outliving its environment.
 *
 * The landing page is replaced by one that throws while `broken` is set, so a render breaks inside
 * the shell exactly as an unexpected shape from the API would break it.
 */

const theLandingPage = vi.hoisted(() => ({ broken: true }));

vi.mock('./pages/PeoplePage', () => ({
  PeoplePage: () => {
    if (theLandingPage.broken) {
      throw new Error('the render broke');
    }
    return (
      <section>
        <h2>People</h2>
      </section>
    );
  },
}));

afterEach(() => {
  // Unmounted before the session ends: hooks run last-registered first, so Testing Library's own
  // cleanup would come after endSession, whose update to a still-mounted provider can leave React
  // work queued past this one-test file's environment ("window is not defined" in CI).
  cleanup();
  vi.unstubAllGlobals();
  endSession();
});

/**
 * "Try again" removes the button that had focus, so what comes back has to take it (#257, C4).
 * Were the session provider inside the boundary, a retry would remount it, and the app would come
 * back restoring the session, a screen with no heading, and land with focus on nothing.
 */
describe('recovering from a broken render in the real app', () => {
  it('GivenAPageThatThrowsOnce_WhenTryAgainIsPressed_ThenFocusIsOnItsHeading', async () => {
    const logged = vi.spyOn(console, 'error').mockImplementation(() => {});
    try {
      routeTo({
        'POST /api/auth/refresh': {
          status: 200,
          body: { accessToken: 'token-1', tokenType: 'Bearer', expiresIn: 600, session: A_TENANT },
        },
        '/api/auth/session': { status: 200, body: A_TENANT },
        '/api/employees': { status: 200, body: [] },
      });
      render(<Root />);
      const tryAgain = await screen.findByRole('button', { name: 'Try again' });

      theLandingPage.broken = false;
      await userEvent.click(tryAgain);

      const heading = await screen.findByRole('heading', { name: 'People' });
      await waitFor(() => expect(heading, 'focus on the "People" heading').toHaveFocus());
    } finally {
      logged.mockRestore();
    }
  });
});
