import { API_URL } from "./env";

/**
 * Whether the backend is answering, and whether it is still waking up.
 *
 * Medi-Scan's backend runs on free hosting that sleeps when idle, so a cold first request can
 * take the better part of a minute. That is a normal state for this app, not an error, and it
 * needs saying out loud — otherwise the first visitor of the day watches a spinner and leaves.
 *
 * A plain store rather than React state: the checker is one per tab, it has to keep running
 * across route changes, and `reportNetworkFailure` is called from the API client, which knows
 * nothing about React.
 */

export type BackendState = "CHECKING" | "ONLINE" | "WAKING" | "DOWN";

export interface BackendStatus {
  state: BackendState;
  /** Which health check we are on. Shown on the 503 screen. */
  attempt: number;
  /** Seconds until the next check, counted down every second for the 503 screen. */
  nextRetryInSec: number | null;
  /**
   * Counts each separate spell of being unreachable.
   *
   * Lets the banner remember "they dismissed *this* one" without having to reset a flag when
   * the spell ends — the dismissal is compared against the number rather than cleared. A
   * second outage is new information and says so again.
   */
  episode: number;
}

/** A response slower than this means it was asleep, even though it eventually answered. */
export const SLOW_THRESHOLD_MS = 1_500;
export const HEALTH_TIMEOUT_MS = 4_000;

/** Back off, then settle into a steady poll. */
const BACKOFF_SEC = [3, 6, 9, 12];
const STEADY_SEC = 15;

/** How long a backend may stay unreachable before we stop calling it "waking". */
export const DOWN_AFTER_MS = 90_000;

const INITIAL: BackendStatus = {
  state: "CHECKING",
  attempt: 0,
  nextRetryInSec: null,
  episode: 0,
};

type Listener = () => void;

function retryDelaySec(attempt: number): number {
  return BACKOFF_SEC[attempt - 1] ?? STEADY_SEC;
}

export class BackendStatusChecker {
  private status: BackendStatus = INITIAL;
  private listeners = new Set<Listener>();
  private retryTimer: ReturnType<typeof setTimeout> | null = null;
  private tickTimer: ReturnType<typeof setInterval> | null = null;
  private firstFailureAt: number | null = null;
  private running = false;
  private paused = false;

  constructor(private readonly checkHealth: () => Promise<boolean> = defaultHealthCheck) {}

  subscribe = (listener: Listener): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  getSnapshot = (): BackendStatus => this.status;

  /** The server never runs a checker, so it always renders the neutral initial state. */
  getServerSnapshot = (): BackendStatus => INITIAL;

  private set(next: Partial<BackendStatus>) {
    const merged = { ...this.status, ...next };
    if (
      merged.state === this.status.state &&
      merged.attempt === this.status.attempt &&
      merged.nextRetryInSec === this.status.nextRetryInSec &&
      merged.episode === this.status.episode
    ) {
      return;
    }
    this.status = merged;
    for (const listener of this.listeners) listener();
  }

  start(): void {
    if (this.running) return;
    this.running = true;
    void this.check();
  }

  stop(): void {
    this.running = false;
    this.clearTimers();
  }

  private clearTimers() {
    if (this.retryTimer) clearTimeout(this.retryTimer);
    if (this.tickTimer) clearInterval(this.tickTimer);
    this.retryTimer = null;
    this.tickTimer = null;
  }

  /** Checks immediately and starts the backoff again from the top. */
  retryNow(): void {
    this.clearTimers();
    this.firstFailureAt = null;
    this.set({ attempt: 0, nextRetryInSec: null });
    void this.check();
  }

  /**
   * Called by the API client when any request fails to reach the server.
   *
   * A health check that passed a minute ago says nothing about now. One dropped call is
   * enough to put the banner back up and restart the checks.
   */
  reportNetworkFailure(): void {
    if (this.status.state === "ONLINE") {
      this.firstFailureAt = Date.now();
      this.set({ state: "WAKING", attempt: 0, episode: this.status.episode + 1 });
      this.scheduleRetry();
    }
  }

  /** Called when a request succeeds, which is better evidence than any health check. */
  reportSuccess(): void {
    if (this.status.state !== "ONLINE") {
      this.clearTimers();
      this.firstFailureAt = null;
      this.set({ state: "ONLINE", attempt: 0, nextRetryInSec: null });
    }
  }

  /** Stop polling a server nobody is looking at. */
  setPaused(paused: boolean): void {
    if (this.paused === paused) return;
    this.paused = paused;

    if (paused) {
      this.clearTimers();
    } else if (this.running && this.status.state !== "ONLINE") {
      this.retryNow();
    }
  }

  private async check(): Promise<void> {
    if (!this.running || this.paused) return;

    const attempt = this.status.attempt + 1;
    this.set({ attempt, nextRetryInSec: null });

    const startedAt = Date.now();
    let healthy = false;
    try {
      healthy = await this.checkHealth();
    } catch {
      healthy = false;
    }

    if (!this.running) return;

    if (healthy) {
      // A slow success still means it had been asleep — but it is awake now, so the banner's
      // job is done and it should close rather than linger.
      this.firstFailureAt = null;
      this.set({ state: "ONLINE", attempt: 0, nextRetryInSec: null });
      return;
    }

    // Only a transition *into* being unreachable starts a new episode; each retry within the
    // same outage stays part of it, so a dismissal keeps holding.
    const wasReachable = this.status.state === "CHECKING" || this.status.state === "ONLINE";
    this.firstFailureAt ??= startedAt;

    const unreachableFor = Date.now() - this.firstFailureAt;
    this.set({
      state: unreachableFor >= DOWN_AFTER_MS ? "DOWN" : "WAKING",
      episode: wasReachable ? this.status.episode + 1 : this.status.episode,
    });
    this.scheduleRetry();
  }

  private scheduleRetry(): void {
    if (!this.running || this.paused) return;
    this.clearTimers();

    const delaySec = retryDelaySec(this.status.attempt);
    this.set({ nextRetryInSec: delaySec });

    // Ticks down every second so the 503 screen can show "trying again in 7 s".
    this.tickTimer = setInterval(() => {
      const remaining = this.status.nextRetryInSec;
      if (remaining === null) return;
      this.set({ nextRetryInSec: Math.max(0, remaining - 1) });
    }, 1000);

    this.retryTimer = setTimeout(() => {
      this.clearTimers();
      void this.check();
    }, delaySec * 1000);
  }
}

/**
 * `GET /actuator/health`. Deliberately not routed through the API client: it must never
 * create a guest session, and a failure here is a state to report rather than an error to
 * throw.
 */
async function defaultHealthCheck(): Promise<boolean> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), HEALTH_TIMEOUT_MS);

  try {
    const response = await fetch(`${API_URL}/actuator/health`, {
      headers: { Accept: "application/json" },
      credentials: "omit",
      signal: controller.signal,
      cache: "no-store",
    });

    if (!response.ok) return false;

    const body: unknown = await response.json().catch(() => null);
    return (body as { status?: string } | null)?.status === "UP";
  } catch {
    // Timed out, refused, or DNS failed. All the same thing from here: not answering yet.
    return false;
  } finally {
    clearTimeout(timer);
  }
}

/** What the header pill shows. `CHECKING` shows nothing at all, so there is no flash. */
export function pillStatus(state: BackendState): "online" | "waking" | null {
  if (state === "CHECKING") return null;
  if (state === "ONLINE") return "online";
  return "waking";
}

/** One checker per tab. */
let singleton: BackendStatusChecker | null = null;

export function getBackendStatusChecker(): BackendStatusChecker {
  singleton ??= new BackendStatusChecker();
  return singleton;
}

export function resetBackendStatusForTests(): void {
  singleton?.stop();
  singleton = null;
}
