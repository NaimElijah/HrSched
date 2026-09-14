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
 * Reloading asks the same question again, so the last answer stays on screen until its replacement
 * arrives: clearing it tore down everything rendered from it, and the keyboard focus inside that,
 * after every change a page made. A different question is another matter — its old answer is never
 * shown, because it would be an answer for somebody else's organisation or another span.
 *
 * @param load fetches the value; re-read on every render, so it may close over fresh props
 * @param key changes exactly when the answer would change — the whole identity of the request
 */
export function useAsync<T>(load: () => Promise<T>, key: string): AsyncState<T> {
  const [attempt, setAttempt] = useState(0);
  const [settled, setSettled] = useState<{
    asked: string;
    key: string;
    value: T | null;
    error: unknown;
  }>({
    asked: '',
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
          setSettled({ asked: key, key: wanted, value, error: null });
        }
      })
      .catch((error: unknown) => {
        if (live) {
          setSettled({ asked: key, key: wanted, value: null, error });
        }
      });
    return () => {
      live = false;
    };
  }, [key, wanted]);

  const current = settled.key === wanted;
  const askedAgain = settled.asked === key;

  return {
    value: current || askedAgain ? settled.value : null,
    error: current ? settled.error : null,
    busy: !current,
    reload: () => setAttempt((previous) => previous + 1),
  };
}
