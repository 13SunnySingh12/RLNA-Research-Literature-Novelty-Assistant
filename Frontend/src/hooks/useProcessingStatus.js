import { useCallback, useEffect, useRef, useState } from 'react';

import { papersApi } from '../services/api';

const ACTIVE = new Set(['queued', 'processing', 'running']);
const POLL_INTERVAL_MS = 2500;

/**
 * Tracks indexing progress for a set of papers.
 *
 * The server owns the work and the state; this only reads it. So the tab can be
 * closed, refreshed, or backgrounded and the answer on return is still correct
 * (Section 24.2).
 *
 * Polling pauses while the tab is hidden, because a background tab is throttled
 * anyway and there is nobody looking at the result. It resumes with an
 * immediate read on return, so the user never waits an interval to see progress
 * that has already happened.
 */
export function useProcessingStatus(paperIds, { onSettled } = {}) {
  const [statuses, setStatuses] = useState({});
  const timer = useRef(null);
  const settledRef = useRef(new Set());
  const onSettledRef = useRef(onSettled);

  // Written in an effect rather than during render: the callback is only ever
  // read from the polling loop, and a render-phase ref write is a pattern React
  // explicitly warns against.
  useEffect(() => {
    onSettledRef.current = onSettled;
  }, [onSettled]);

  // The set of ids is compared by value, so a caller re-creating the array on
  // every render does not restart polling.
  const key = paperIds.join(',');

  const poll = useCallback(async () => {
    const ids = key ? key.split(',') : [];
    if (!ids.length) return;

    const results = await Promise.allSettled(ids.map((id) => papersApi.status(id)));
    const next = {};
    results.forEach((result, index) => {
      if (result.status === 'fulfilled') {
        next[ids[index]] = result.value;
      }
    });

    setStatuses((current) => ({ ...current, ...next }));

    Object.entries(next).forEach(([id, status]) => {
      if (!ACTIVE.has(status.status) && !settledRef.current.has(id)) {
        settledRef.current.add(id);
        onSettledRef.current?.(id, status);
      }
    });
  }, [key]);

  useEffect(() => {
    settledRef.current = new Set();
    if (!key) {
      setStatuses({});
      return undefined;
    }

    let cancelled = false;

    const tick = async () => {
      if (cancelled) return;
      if (document.visibilityState === 'visible') {
        await poll();
      }
      if (!cancelled) {
        timer.current = setTimeout(tick, POLL_INTERVAL_MS);
      }
    };

    const onVisible = () => {
      if (document.visibilityState === 'visible') poll();
    };

    tick();
    document.addEventListener('visibilitychange', onVisible);

    return () => {
      cancelled = true;
      clearTimeout(timer.current);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [key, poll]);

  return statuses;
}

export const isActiveStatus = (status) => ACTIVE.has(status);
