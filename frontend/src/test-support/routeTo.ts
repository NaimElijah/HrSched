import { vi } from 'vitest';

/** What a stubbed endpoint answers with. A null body is sent as no body at all, as a 204 is. */
export interface Answer {
  readonly status: number;
  readonly body: unknown;
}

/**
 * Replaces `fetch` with a router over the given answers, and returns the stub to inspect.
 *
 * A key is either `METHOD path`, which answers only that method at exactly that path, or a bare
 * path, which answers only a GET at exactly that path. The query string is not part of the match.
 * Anything unmatched is a 404, so a request a test did not expect fails loudly rather than
 * receiving somebody else's body: a write to the wrong endpoint, or with the wrong method, is
 * refused rather than answered as if the test had named it.
 *
 * `fetch` is stubbed rather than mocked with a library: the HTTP API is our own port (ADR-013).
 */
export function routeTo(handlers: Readonly<Record<string, Answer>>) {
  const stub = vi.fn((path: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET';
    const bare = path.split('?')[0] ?? path;
    const match = handlers[`${method} ${bare}`] ??
      (method === 'GET' ? handlers[bare] : undefined) ?? { status: 404, body: {} };
    return Promise.resolve({
      ok: match.status < 400,
      status: match.status,
      headers: new Headers(),
      text: () => Promise.resolve(match.body === null ? '' : JSON.stringify(match.body)),
    });
  });
  vi.stubGlobal('fetch', stub);
  return stub;
}

/** The calls a routed stub received with this method at exactly this path. */
export function callsTo(stub: ReturnType<typeof routeTo>, method: string, path: string) {
  return stub.mock.calls.filter(
    ([called, init]) => (init?.method ?? 'GET') === method && called === path,
  );
}
