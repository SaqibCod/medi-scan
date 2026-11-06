"use client";

import { ApiError } from "@/lib/api-error";
import { ServerErrorScreen } from "@/components/system/screens";

/**
 * Catches a render error anywhere below the root layout.
 *
 * A request id only exists when the thing that failed was a request, so the block is hidden
 * when a component simply threw — showing an empty "REQUEST ID" heading would invite someone
 * to quote nothing.
 */
export default function ErrorBoundary({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  const requestId = error instanceof ApiError ? error.requestId : null;

  return <ServerErrorScreen requestId={requestId} onRetry={reset} />;
}
