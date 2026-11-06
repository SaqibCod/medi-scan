/**
 * The daily-limit banner's state.
 *
 * The app shares one LLM budget for the whole day (contract 1.6), so when it runs out, it is
 * out for everyone until midnight UTC. That is a fact that blocks uploads, not a warning, so
 * the banner cannot be dismissed — hiding it would only mean the next upload fails with no
 * explanation.
 *
 * In memory, per tab. Nothing about it belongs in storage.
 */

export interface DailyLimit {
  /** When the cap lifts. Always the next midnight UTC. */
  resetsAt: Date;
}

/**
 * Published by the API client when any call comes back `CAPACITY`.
 *
 * An event rather than a direct call, for the same reason the guest session uses one: the API
 * client has no React context to reach into, and the banner has no business being coupled to
 * whichever request happened to be the one that ran out of budget.
 */
export const DAILY_LIMIT_REACHED = "ms:daily-limit-reached";

export function publishDailyLimit(retryAfterSec: number | null): void {
  if (typeof window === "undefined") return;
  window.dispatchEvent(new CustomEvent(DAILY_LIMIT_REACHED, { detail: { retryAfterSec } }));
}

/** Midnight UTC after `from`. */
export function nextMidnightUtc(from: Date = new Date()): Date {
  const next = new Date(from);
  next.setUTCHours(24, 0, 0, 0);
  return next;
}

/**
 * Works out when the cap lifts.
 *
 * `Retry-After` is authoritative when the server sends it — the server knows its own clock,
 * and a visitor's machine may be minutes out. Without it, midnight UTC is the rule.
 */
export function resolveResetsAt(retryAfterSec: number | null, now: Date = new Date()): Date {
  if (retryAfterSec !== null && retryAfterSec > 0) {
    return new Date(now.getTime() + retryAfterSec * 1000);
  }
  return nextMidnightUtc(now);
}

/**
 * "6 h 12 min", as the mockup writes it. Hours and minutes only: the banner updates once a
 * minute, so a seconds figure would be wrong almost all the time.
 */
export function formatCountdown(resetsAt: Date, now: Date = new Date()): string {
  const totalMinutes = Math.max(0, Math.ceil((resetsAt.getTime() - now.getTime()) / 60_000));
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;

  if (hours === 0) return `${minutes} min`;
  return `${hours} h ${minutes} min`;
}

export function hasExpired(resetsAt: Date, now: Date = new Date()): boolean {
  return resetsAt.getTime() <= now.getTime();
}
