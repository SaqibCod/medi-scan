"use client";

import { useNotices } from "@/components/notices/notice-provider";

/**
 * "Attempt 2 · trying again in 7 s" (mockup `--06`).
 *
 * A live region, so the fact that something is still happening reaches someone who cannot see
 * the countdown. `aria-live="polite"` rather than assertive: it updates every second, and
 * interrupting once a second would be unusable. Most screen readers coalesce polite updates,
 * which is exactly the behaviour wanted here.
 */
export function RetryCounter() {
  const { backend } = useNotices();

  if (backend.attempt === 0) return null;

  return (
    <p className="t-data flex items-center gap-space-3 text-accent-ink" aria-live="polite">
      <span
        className="inline-block size-[7px] shrink-0 rounded-full bg-accent"
        aria-hidden="true"
      />
      Attempt {backend.attempt}
      {backend.nextRetryInSec !== null && <> · trying again in {backend.nextRetryInSec} s</>}
    </p>
  );
}
