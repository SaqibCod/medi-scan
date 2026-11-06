"use client";

import { useEffect, useState } from "react";

import { Icon } from "@/components/ds";
import { IconButton } from "@/components/ds";
import { formatCountdown } from "@/lib/notices";
import { useNotices } from "./notice-provider";

/**
 * "Waking up the server."
 *
 * A violet bar, not an orange or red one: the server being asleep is how free hosting works,
 * not a fault, and the visitor can start uploading while it wakes.
 */
export function WakingBanner() {
  const { backend, wakingDismissed, dismissWaking } = useNotices();

  const waking = backend.state === "WAKING" || backend.state === "DOWN";
  if (!waking || wakingDismissed) return null;

  return (
    <div className="ms-banner ms-banner--waking" role="status" aria-live="polite">
      <span className="ms-banner__icon" aria-hidden="true">
        <Icon name="clock" size={16} />
      </span>

      <p className="ms-banner__text">
        <b>Waking up the server.</b>{" "}
        <span className="hidden sm:inline">
          Medi-Scan runs on free hosting that sleeps when idle, so the first load can be slow.
          You can start uploading now.
        </span>
        <span className="sm:hidden">Free hosting sleeps when idle, so this can be slow.</span>
      </p>

      <IconButton
        icon="close"
        label="Hide this message"
        onClick={dismissWaking}
        size={14}
        className="ms-banner__close"
      />

      {/* Decorative. It reports no real progress — there is nothing to measure — so it must
          not be announced as if it did. */}
      <span className="ms-banner__progress" aria-hidden="true" />
    </div>
  );
}

/**
 * "The demo has hit today's AI limit."
 *
 * Orange, the same hue as a HIGH flag and for the same reason: a fact that blocks an action,
 * never an alarm. Not dismissible — hiding it would only mean the next upload fails with no
 * explanation.
 */
export function DailyLimitBanner() {
  const { dailyLimit } = useNotices();
  const [, setTick] = useState(0);

  // Re-render once a minute so the countdown stays honest. A seconds-level timer would be
  // wasted: the text is only ever accurate to the minute.
  useEffect(() => {
    if (!dailyLimit) return;
    const timer = setInterval(() => setTick((t) => t + 1), 60_000);
    return () => clearInterval(timer);
  }, [dailyLimit]);

  if (!dailyLimit) return null;

  return (
    <div className="ms-banner ms-banner--limit" role="status" aria-live="polite">
      <span className="ms-banner__icon" aria-hidden="true">
        <Icon name="clock" size={16} />
      </span>

      <p className="ms-banner__text">
        <b>The demo has hit today&rsquo;s AI limit.</b> New reports and chat open again in{" "}
        <span className="ms-banner__count">{formatCountdown(dailyLimit.resetsAt)}</span> (midnight
        UTC). Your saved reports still open.
      </p>
    </div>
  );
}
