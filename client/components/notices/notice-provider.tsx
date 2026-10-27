"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  useSyncExternalStore,
  type ReactNode,
} from "react";

import { getBackendStatusChecker, type BackendStatus } from "@/lib/backend-status";
import {
  DAILY_LIMIT_REACHED,
  hasExpired,
  resolveResetsAt,
  type DailyLimit,
} from "@/lib/notices";

interface NoticeContextValue {
  backend: BackendStatus;
  retryBackendNow: () => void;

  dailyLimit: DailyLimit | null;
  reportDailyLimit: (retryAfterSec: number | null) => void;

  wakingDismissed: boolean;
  dismissWaking: () => void;
}

const NoticeContext = createContext<NoticeContextValue | null>(null);

/**
 * Owns the two page-wide banners.
 *
 * Both are driven by things that happen outside any one page — the backend waking up, the
 * shared daily LLM budget running out — so they live above the routes rather than inside
 * whichever page happened to notice.
 */
export function NoticeProvider({ children }: { children: ReactNode }) {
  const checker = getBackendStatusChecker();

  const backend = useSyncExternalStore(
    checker.subscribe,
    checker.getSnapshot,
    checker.getServerSnapshot,
  );

  const [dailyLimit, setDailyLimit] = useState<DailyLimit | null>(null);

  // Which outage the visitor waved away. Compared against rather than cleared, so the next
  // outage speaks up again without an effect having to reset anything.
  const [dismissedEpisode, setDismissedEpisode] = useState<number | null>(null);
  const wakingDismissed = dismissedEpisode === backend.episode;

  // One checker per tab, started after mount so it never runs during server rendering.
  useEffect(() => {
    checker.start();

    const onVisibilityChange = () => checker.setPaused(document.visibilityState === "hidden");
    document.addEventListener("visibilitychange", onVisibilityChange);
    onVisibilityChange();

    return () => {
      document.removeEventListener("visibilitychange", onVisibilityChange);
      // Deliberately not stopped: the checker is per tab, not per mount, and React's
      // development remount would otherwise restart the backoff from scratch.
    };
  }, [checker]);

  // The cap lifts on its own at midnight UTC; nothing tells us, so the banner has to notice.
  useEffect(() => {
    if (!dailyLimit) return;

    const timer = setInterval(() => {
      if (hasExpired(dailyLimit.resetsAt)) setDailyLimit(null);
    }, 30_000);

    return () => clearInterval(timer);
  }, [dailyLimit]);

  const reportDailyLimit = useCallback((retryAfterSec: number | null) => {
    setDailyLimit({ resetsAt: resolveResetsAt(retryAfterSec) });
  }, []);

  // Any call that comes back CAPACITY raises the banner, wherever in the app it was made.
  useEffect(() => {
    const onLimit = (event: Event) => {
      const { retryAfterSec } = (event as CustomEvent<{ retryAfterSec: number | null }>).detail;
      reportDailyLimit(retryAfterSec);
    };

    window.addEventListener(DAILY_LIMIT_REACHED, onLimit);
    return () => window.removeEventListener(DAILY_LIMIT_REACHED, onLimit);
  }, [reportDailyLimit]);

  const value = useMemo<NoticeContextValue>(
    () => ({
      backend,
      retryBackendNow: () => checker.retryNow(),
      dailyLimit,
      reportDailyLimit,
      wakingDismissed,
      dismissWaking: () => setDismissedEpisode(backend.episode),
    }),
    [backend, checker, dailyLimit, reportDailyLimit, wakingDismissed],
  );

  return <NoticeContext.Provider value={value}>{children}</NoticeContext.Provider>;
}

export function useNotices(): NoticeContextValue {
  const value = useContext(NoticeContext);
  if (!value) throw new Error("useNotices must be used inside <NoticeProvider>");
  return value;
}
