import { QueryClient } from "@tanstack/react-query";

import { ApiError } from "./api-error";

/**
 * One `QueryClient` per browser tab.
 *
 * Created inside a `useState` initialiser in the provider, never at module scope: a
 * module-level client is shared across requests on the server, which would leak one visitor's
 * cached reports into another's render.
 */
export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        /**
         * Retry only what a retry can fix.
         *
         * A 4xx is a decision the server has already made — a missing report, a rejected
         * token, a limit reached — and asking again only spends the rate limit. 5xx, a
         * timeout and a dropped connection are the ones worth trying again, which on free
         * hosting usually means the backend is waking up.
         */
        retry: (failureCount, error) => {
          if (failureCount >= 2) return false;
          if (error instanceof ApiError) return error.isRetryable;
          return false;
        },
        retryDelay: (attempt) => Math.min(1000 * 2 ** attempt, 8000),

        // Off by default; later phases turn it on per query where it actually helps. Report
        // status is polled with `refetchInterval` instead, which does not depend on focus.
        refetchOnWindowFocus: false,

        staleTime: 30_000,
      },
    },
  });
}

/**
 * Query keys, in one place.
 *
 * Everything a guest owns hangs off `["reports"]`, so a dead session can drop all of it in
 * one call (LLD 7). Keys never contain a token — they end up in devtools labels.
 */
export const queryKeys = {
  reports: ["reports"] as const,
  report: (id: string) => ["reports", id] as const,
  samples: ["samples"] as const,
  health: ["health"] as const,
};
