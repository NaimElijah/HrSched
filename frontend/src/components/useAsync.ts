import { useEffect, useRef, useState } from 'react';

interface AsyncState<T> {
  readonly value: T | null;
  readonly error: unknown;
  readonly busy: boolean;
  readonly reload: () => void;
}

/**
 * Loads something from the API and keeps the three states a caller has to render: the value, the
 * failure, and whether a request is in flight.
 *
 * Failures are held, never swallowed. A page that quietly showed an empty list when the server
 * refused would hide exactly the behaviour this UI exists to demonstrate.
 *
 * `busy` is derived by comparing the key the held state belongs to against the key being asked for,
 * rather than set at the top of the effect. Setting state synchronously inside an effect triggers a
 * cascading render, which React 19 flags; deriving it needs no extra render at all.
 *
 * @param load fetches the value; re-read on every render, so it may close over fresh props
 * @param key changes exactly when the answer would change — the whole identity of the request
 */
export function useAsync<T>(load: () => Promise<T>, key: string): AsyncState<T> {
  const [attempt, setAttempt] = useState(0);
  const [settled, setSettled] = useState<{ key: string; value: T | null; error: unknown }>({
    key: '',
    value: null,
    error: null,
  });

  const loader = useRef(load);
  useEffect(() => {
    loader.current = load;
  });

  const wanted = `${key}#${attempt}`;

  useEffect(() => {
    let live = true;
    loader
      .current()
      .then((value) => {
        if (live) {
          setSettled({ key: wanted, value, error: null });
        }
      })
      .catch((error: unknown) => {
        if (live) {
          setSettled({ key: wanted, value: null, error });
        }
      });
    return () => {
      live = false;
    };
  }, [wanted]);

  return {
    value: settled.key === wanted ? settled.value : null,
    error: settled.key === wanted ? settled.error : null,
    busy: settled.key !== wanted,
    reload: () => setAttempt((previous) => previous + 1),
  };
}
