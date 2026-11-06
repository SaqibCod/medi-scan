"use client";

import { useEffect, useState } from "react";

import { Icon } from "@/components/ds";
import { formatExpiryCountdown } from "@/lib/report-format";

/**
 * "Guest report: it's deleted automatically in 23 h 12 min."
 *
 * The countdown is the point. A guest's report really is going away today, and a flat
 * sentence saying "24 hours" lets someone close the tab believing they can come back to it
 * tomorrow. Updating every minute is enough: the number is in minutes, and a per-second
 * timer on a page that may sit open for hours is waste.
 *
 * "Sign in to keep it" is absent until 7c (B10) rather than shown and disabled.
 */
export function GuestExpiryNote({ expiresAt }: { expiresAt: string }) {
  const [countdown, setCountdown] = useState(() => formatExpiryCountdown(expiresAt));

  useEffect(() => {
    const timer = setInterval(() => setCountdown(formatExpiryCountdown(expiresAt)), 60_000);
    return () => clearInterval(timer);
  }, [expiresAt]);

  return (
    <div className="flex items-start gap-space-4 rounded-md bg-accent-soft px-space-5 py-space-4">
      <span className="mt-[2px] shrink-0 text-accent-ink" aria-hidden="true">
        <Icon name="clock" size={18} />
      </span>
      <p className="t-body-s text-ink-2">
        <span className="text-ink">Guest report:</span> it&apos;s deleted automatically in{" "}
        <span className="t-data text-ink">{countdown}</span>.
      </p>
    </div>
  );
}
