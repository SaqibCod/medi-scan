"use client";

import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { CloudOff, Loader2 } from "lucide-react";

import { api } from "@/lib/api";

/** How long the backend may take before we tell the user something is happening. */
const SLOW_AFTER_MS = 2000;

/**
 * Checks the backend on load, and explains a slow or failed start.
 *
 * Worth having rather than letting the first real request hang: the backend runs on a small
 * EC2 box and the database is Neon's free tier, which sleeps when idle. A cold start takes
 * seconds, and without this the app would look broken rather than waking up.
 *
 * Renders nothing in the normal case. A health check that passes quickly should be invisible.
 */
export function HealthNotice() {
  const [slow, setSlow] = useState(false);

  const { isPending, isError, data } = useQuery({
    queryKey: ["health"],
    queryFn: ({ signal }) => api.health(signal),
    // One retry: a cold start usually answers on the second attempt, and more than that just
    // delays telling the user something is wrong.
    retry: 1,
    retryDelay: 1500,
    staleTime: 60_000,
    refetchOnWindowFocus: false,
  });

  useEffect(() => {
    if (!isPending) return;
    const timer = window.setTimeout(() => setSlow(true), SLOW_AFTER_MS);
    return () => window.clearTimeout(timer);
  }, [isPending]);

  const unreachable = isError || data === false;

  // Quiet until there is something to say.
  if (!unreachable && !(isPending && slow)) {
    return null;
  }

  return (
    // `aria-live="polite"` because this appears after load: without it a screen-reader user
    // would never hear that the backend is waking up or unreachable.
    <div aria-live="polite" className="mx-auto w-full max-w-5xl px-4 pt-4 sm:px-6">
      {unreachable ? (
        <p className="flex items-start gap-2.5 rounded-lg border border-destructive/30 bg-destructive/10 px-3 py-2.5 text-sm text-destructive">
          <CloudOff aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
          <span className="min-w-0 text-pretty">
            Can&rsquo;t reach the Medi&#8209;Scan backend. It may be starting up — reload in a
            moment, or check that the API is running.
          </span>
        </p>
      ) : (
        <p className="flex items-start gap-2.5 rounded-lg border border-border bg-muted/50 px-3 py-2.5 text-sm text-muted-foreground">
          <Loader2
            aria-hidden="true"
            className="mt-0.5 size-4 shrink-0 animate-spin motion-reduce:animate-none"
          />
          <span className="min-w-0 text-pretty">
            Waking up the backend… the first request after a quiet spell takes a few seconds.
          </span>
        </p>
      )}
    </div>
  );
}
