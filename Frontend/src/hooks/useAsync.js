import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * Loads data once and exposes the four states every surface renders.
 *
 * The in-flight request is tracked so a response that arrives after the
 * component unmounted, or after a newer request superseded it, is discarded
 * rather than written into stale state.
 */
export function useAsync(loader, deps = [], { immediate = true } = {}) {
  const [state, setState] = useState({
    data: null,
    error: null,
    loading: immediate,
  });
  const generation = useRef(0);
  const mounted = useRef(true);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  const run = useCallback(async (...args) => {
    const current = ++generation.current;
    setState((previous) => ({ ...previous, loading: true, error: null }));
    try {
      const data = await loader(...args);
      if (mounted.current && current === generation.current) {
        setState({ data, error: null, loading: false });
      }
      return data;
    } catch (error) {
      if (mounted.current && current === generation.current) {
        setState({ data: null, error, loading: false });
      }
      throw error;
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);

  useEffect(() => {
    if (immediate) {
      run().catch(() => {});
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [run, immediate]);

  const setData = useCallback((updater) => {
    setState((previous) => ({
      ...previous,
      data: typeof updater === 'function' ? updater(previous.data) : updater,
    }));
  }, []);

  return { ...state, reload: run, setData };
}

/**
 * Runs an action on demand and tracks its progress.
 *
 * Concurrent invocations are suppressed, which is what stops a double-clicked
 * "Generate" button from paying for the same analysis twice.
 */
export function useAction(action) {
  const [state, setState] = useState({ loading: false, error: null });
  const inFlight = useRef(false);

  const run = useCallback(
    async (...args) => {
      if (inFlight.current) return undefined;
      inFlight.current = true;
      setState({ loading: true, error: null });
      try {
        const result = await action(...args);
        setState({ loading: false, error: null });
        return result;
      } catch (error) {
        setState({ loading: false, error });
        return undefined;
      } finally {
        inFlight.current = false;
      }
    },
    [action],
  );

  const reset = useCallback(() => setState({ loading: false, error: null }), []);
  return { ...state, run, reset };
}
