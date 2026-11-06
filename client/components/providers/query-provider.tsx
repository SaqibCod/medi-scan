"use client";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState, type ReactNode } from "react";

/**
 * Creates the client lazily inside `useState` so each browser tab gets exactly one, created
 * on first render and never replaced. A module-level client would be shared between requests
 * on the server and leak one user's cached data into another's response.
 */
const QueryProvider = ({ children }: { children: ReactNode }) => {
  const [queryClient] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            // Report status is polled with an explicit refetchInterval, so background
            // refetching on every window focus would only add requests against the rate limit.
            refetchOnWindowFocus: false,
            retry: 1,
          },
        },
      }),
  );

  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
};

export default QueryProvider;
