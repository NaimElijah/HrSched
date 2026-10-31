import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { endSession } from './api/session';
import { routeTo } from './test-support/routeTo';
import { A_TENANT } from './test-support/session';

/**
 * The application as `main.tsx` mounts it, which is the only place the boundary and the session
 * provider are nested: a test that nests them itself would pass whatever the entry point does.
 *
 * Importing the module mounts it, once per file, into the `#root` it looks for. The landing page
 * is replaced by one that throws while `broken` is set, so a render breaks inside the shell exactly
 * as an unexpected shape from the API would break it.
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
      const root = document.createElement('div');
      root.id = 'root';
      document.body.append(root);
      await import('./main');
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
