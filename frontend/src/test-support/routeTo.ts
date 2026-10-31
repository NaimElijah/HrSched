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
 * The answers are looked up in `handlers` on every request, not copied, so a test may change one
 * after the page has loaded to stand for what the server holds by then.
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

/**
 * The calls a routed stub received with this method at exactly this path.
 *
 * Unlike the routing, the path is compared as it was sent, so a call carrying a query string
 * matches only when `path` includes it.
 */
export function callsTo(stub: ReturnType<typeof routeTo>, method: string, path: string) {
  return stub.mock.calls.filter(
    ([called, init]) => (init?.method ?? 'GET') === method && called === path,
  );
}

/**
 * Every call a routed stub received with a method other than GET, wherever it was sent. A check
 * that nothing was sent asserts this is empty, so a write to a path the test did not think of is
 * caught as well as one to the path it did.
 */
export function writes(stub: ReturnType<typeof routeTo>) {
  return stub.mock.calls.filter(([, init]) => (init?.method ?? 'GET') !== 'GET');
}

/**
 * Holds every write sent until the returned function is called, while reads are answered at once.
 * For a test of what a screen does while a write is on its way: a held write reaches the routed
 * stub, and so is counted by {@link writes} and {@link callsTo}, only once it is released.
 *
 * @param routed the stub {@link routeTo} returned, which answers each write once it is released
 * @returns the function that lets every held write through
 */
export function holdingWrites(routed: ReturnType<typeof routeTo>): () => void {
  let release!: () => void;
  const held = new Promise<void>((resolve) => {
    release = resolve;
  });
  vi.stubGlobal(
    'fetch',
    vi.fn((path: string, init?: RequestInit) =>
      (init?.method ?? 'GET') === 'GET' ? routed(path, init) : held.then(() => routed(path, init)),
    ),
  );
  return release;
}

/**
 * Holds every request sent, reads included, until the returned function is called. For a test of
 * what a screen shows and allows before anything it asked for has loaded.
 *
 * @param routed the stub {@link routeTo} returned, which answers each request once it is released
 * @returns the function that lets every held request through
 */
export function holdingEverything(routed: ReturnType<typeof routeTo>): () => void {
  let answer!: () => void;
  const answering = new Promise<void>((resolve) => {
    answer = resolve;
  });
  vi.stubGlobal(
    'fetch',
    vi.fn((path: string, init?: RequestInit) => answering.then(() => routed(path, init))),
  );
  return answer;
}
