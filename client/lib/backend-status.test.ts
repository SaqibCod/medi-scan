import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  BackendStatusChecker,
  DOWN_AFTER_MS,
  pillStatus,
  type BackendStatus,
} from "./backend-status";

/** Lets each test say, check by check, whether the backend answers. */
function healthStub(...answers: boolean[]) {
  const stub = vi.fn<() => Promise<boolean>>();
  for (const answer of answers) stub.mockResolvedValueOnce(answer);
  // Anything past the scripted answers keeps the last one.
  stub.mockResolvedValue(answers.at(-1) ?? true);
  return stub;
}

function track(checker: BackendStatusChecker) {
  const seen: BackendStatus[] = [];
  checker.subscribe(() => seen.push(checker.getSnapshot()));
  return seen;
}

/** Lets the pending health-check promise settle, then runs any timer it scheduled. */
async function settle() {
  await vi.advanceTimersByTimeAsync(0);
}

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe("the first check", () => {
  it("starts in CHECKING, so nothing flashes before we know", () => {
    const checker = new BackendStatusChecker(healthStub(true));
    expect(checker.getSnapshot().state).toBe("CHECKING");
    // The pill shows nothing at all until there is something true to say.
    expect(pillStatus("CHECKING")).toBeNull();
  });

  it("goes ONLINE when the server answers", async () => {
    const checker = new BackendStatusChecker(healthStub(true));
    checker.start();
    await settle();

    expect(checker.getSnapshot().state).toBe("ONLINE");
    expect(pillStatus("ONLINE")).toBe("online");
  });

  it("goes WAKING when it does not", async () => {
    const checker = new BackendStatusChecker(healthStub(false));
    checker.start();
    await settle();

    expect(checker.getSnapshot().state).toBe("WAKING");
    expect(pillStatus("WAKING")).toBe("waking");
  });

  it("goes ONLINE even if the answer was slow — it is awake now", async () => {
    const slow = vi.fn(
      () => new Promise<boolean>((resolve) => setTimeout(() => resolve(true), 3_000)),
    );
    const checker = new BackendStatusChecker(slow);
    checker.start();

    await vi.advanceTimersByTimeAsync(3_000);

    // The banner was up while it waited; its job is done, so it closes rather than lingering.
    expect(checker.getSnapshot().state).toBe("ONLINE");
  });

  it("treats a thrown health check as a failure, not a crash", async () => {
    const checker = new BackendStatusChecker(vi.fn().mockRejectedValue(new Error("boom")));
    checker.start();
    await settle();

    expect(checker.getSnapshot().state).toBe("WAKING");
  });
});

describe("retrying", () => {
  it("backs off 3, 6, 9, 12 seconds, then settles at 15", async () => {
    const health = healthStub(false);
    const checker = new BackendStatusChecker(health);
    checker.start();
    await settle();

    const delays: (number | null)[] = [];
    for (const expected of [3, 6, 9, 12, 15, 15]) {
      delays.push(checker.getSnapshot().nextRetryInSec);
      await vi.advanceTimersByTimeAsync(expected * 1000);
      await settle();
    }

    expect(delays).toEqual([3, 6, 9, 12, 15, 15]);
  });

  it("counts down every second, for the 503 screen", async () => {
    const checker = new BackendStatusChecker(healthStub(false));
    checker.start();
    await settle();

    expect(checker.getSnapshot().nextRetryInSec).toBe(3);

    await vi.advanceTimersByTimeAsync(1000);
    expect(checker.getSnapshot().nextRetryInSec).toBe(2);

    await vi.advanceTimersByTimeAsync(1000);
    expect(checker.getSnapshot().nextRetryInSec).toBe(1);
  });

  it("counts the attempts", async () => {
    const checker = new BackendStatusChecker(healthStub(false));
    checker.start();
    await settle();
    expect(checker.getSnapshot().attempt).toBe(1);

    await vi.advanceTimersByTimeAsync(3_000);
    await settle();
    expect(checker.getSnapshot().attempt).toBe(2);
  });

  it("recovers by itself once the server answers", async () => {
    const checker = new BackendStatusChecker(healthStub(false, false, true));
    checker.start();
    await settle();
    expect(checker.getSnapshot().state).toBe("WAKING");

    await vi.advanceTimersByTimeAsync(3_000);
    await settle();
    await vi.advanceTimersByTimeAsync(6_000);
    await settle();

    expect(checker.getSnapshot().state).toBe("ONLINE");
    expect(checker.getSnapshot().attempt).toBe(0);
  });

  it("'Retry now' checks at once and starts the backoff again", async () => {
    const health = healthStub(false);
    const checker = new BackendStatusChecker(health);
    checker.start();
    await settle();

    await vi.advanceTimersByTimeAsync(3_000);
    await settle();
    expect(checker.getSnapshot().attempt).toBe(2);

    checker.retryNow();
    await settle();

    // Back to the first attempt and the shortest delay, as if nothing had failed yet.
    expect(checker.getSnapshot().attempt).toBe(1);
    expect(checker.getSnapshot().nextRetryInSec).toBe(3);
  });
});

describe("giving up on 'waking'", () => {
  it("calls it DOWN after 90 seconds of failures", async () => {
    const checker = new BackendStatusChecker(healthStub(false));
    checker.start();
    await settle();

    expect(checker.getSnapshot().state).toBe("WAKING");

    // Walk past the threshold through the real retry schedule.
    for (let elapsed = 0; elapsed < DOWN_AFTER_MS + 20_000; elapsed += 15_000) {
      await vi.advanceTimersByTimeAsync(15_000);
      await settle();
    }

    expect(checker.getSnapshot().state).toBe("DOWN");
    // The pill still just says "waking": the distinction matters to the 503 screen, not the
    // header, and "DOWN" in a top bar reads like the whole site is broken.
    expect(pillStatus("DOWN")).toBe("waking");
  });
});

describe("evidence from real requests", () => {
  it("a failed call puts an ONLINE backend back to WAKING", async () => {
    const checker = new BackendStatusChecker(healthStub(true));
    checker.start();
    await settle();
    expect(checker.getSnapshot().state).toBe("ONLINE");

    checker.reportNetworkFailure();

    expect(checker.getSnapshot().state).toBe("WAKING");
  });

  it("a failed call during the very first check still raises the banner", async () => {
    // The window that matters: the first health check takes up to four seconds, and someone
    // pressing "Analyze report" inside it on a cold server would otherwise see a failed
    // upload with nothing explaining why.
    const checker = new BackendStatusChecker(healthStub(false));
    checker.start();
    expect(checker.getSnapshot().state).toBe("CHECKING");

    checker.reportNetworkFailure();

    expect(checker.getSnapshot().state).toBe("WAKING");
  });

  it("starts checking again at once rather than waiting out the steady interval", async () => {
    const health = healthStub(true);
    const checker = new BackendStatusChecker(health);
    checker.start();
    await settle();
    expect(checker.getSnapshot().state).toBe("ONLINE");

    const before = health.mock.calls.length;
    checker.reportNetworkFailure();
    await settle();

    // A banner that says the server is waking has to have something actually asking it.
    expect(health.mock.calls.length).toBe(before + 1);
  });

  it("does not run two health checks at once when a call fails mid-check", async () => {
    const health = healthStub(false);
    const checker = new BackendStatusChecker(health);
    checker.start();

    // start() left a check in flight. The failure report must not fire a second one.
    checker.reportNetworkFailure();
    await settle();

    expect(health.mock.calls.length).toBe(1);
  });

  it("a successful call is enough to call it ONLINE again", async () => {
    const checker = new BackendStatusChecker(healthStub(false));
    checker.start();
    await settle();
    expect(checker.getSnapshot().state).toBe("WAKING");

    checker.reportSuccess();

    expect(checker.getSnapshot().state).toBe("ONLINE");
  });

  it("ignores a failure report when it already knows it is waking", async () => {
    const checker = new BackendStatusChecker(healthStub(false));
    checker.start();
    await settle();

    const seen = track(checker);
    checker.reportNetworkFailure();

    // Nothing changed, so nothing should have been published — a burst of failed calls must
    // not restart the backoff over and over.
    expect(seen).toEqual([]);
  });
});

describe("outage episodes", () => {
  it("numbers each separate outage, so a dismissal only covers that one", async () => {
    const checker = new BackendStatusChecker(healthStub(false, true, false));
    checker.start();
    await settle();

    const firstOutage = checker.getSnapshot().episode;
    expect(firstOutage).toBe(1);

    // Recovers...
    await vi.advanceTimersByTimeAsync(3_000);
    await settle();
    expect(checker.getSnapshot().state).toBe("ONLINE");

    // ...then goes again. The banner must speak up a second time even if the first was
    // waved away, which is what comparing against this number buys.
    checker.reportNetworkFailure();
    expect(checker.getSnapshot().episode).toBe(2);
  });

  it("keeps the same number across retries within one outage", async () => {
    const checker = new BackendStatusChecker(healthStub(false));
    checker.start();
    await settle();

    const episode = checker.getSnapshot().episode;

    await vi.advanceTimersByTimeAsync(3_000);
    await settle();
    await vi.advanceTimersByTimeAsync(6_000);
    await settle();

    // Three failed checks, still one outage — a dismissal must keep holding.
    expect(checker.getSnapshot().attempt).toBe(3);
    expect(checker.getSnapshot().episode).toBe(episode);
  });
});

describe("a hidden tab", () => {
  it("stops polling a server nobody is looking at", async () => {
    const health = healthStub(false);
    const checker = new BackendStatusChecker(health);
    checker.start();
    await settle();

    const before = health.mock.calls.length;
    checker.setPaused(true);

    await vi.advanceTimersByTimeAsync(60_000);
    expect(health.mock.calls.length).toBe(before);
  });

  it("checks straight away when the tab comes back", async () => {
    const health = healthStub(false);
    const checker = new BackendStatusChecker(health);
    checker.start();
    await settle();

    checker.setPaused(true);
    await vi.advanceTimersByTimeAsync(60_000);

    const before = health.mock.calls.length;
    checker.setPaused(false);
    await settle();

    expect(health.mock.calls.length).toBe(before + 1);
  });

  it("checks on return even when it last saw the backend up", async () => {
    const health = healthStub(true);
    const checker = new BackendStatusChecker(health);
    checker.start();
    await settle();

    checker.setPaused(true);
    const before = health.mock.calls.length;
    checker.setPaused(false);
    await settle();

    // Coming back to a tab is a good moment to find out the truth either way — the backend
    // may have gone away while nobody was looking.
    expect(health.mock.calls.length).toBe(before + 1);
  });
});

describe("while it is up", () => {
  it("keeps checking, so ONLINE stays a fact rather than a memory", async () => {
    const health = healthStub(true);
    const checker = new BackendStatusChecker(health);
    checker.start();
    await settle();

    expect(checker.getSnapshot().state).toBe("ONLINE");
    const afterFirst = health.mock.calls.length;

    await vi.advanceTimersByTimeAsync(15_000);
    await settle();

    expect(health.mock.calls.length).toBeGreaterThan(afterFirst);
  });

  it("notices when the backend goes away, with no API call to tell it", async () => {
    // The bug this exists for: the checker reached ONLINE, stopped, and went on saying
    // ONLINE after the server had been killed. Nothing else would have noticed, because 7a
    // makes no API calls that could fail.
    const health = healthStub(true, false, false);
    const checker = new BackendStatusChecker(health);
    checker.start();
    await settle();
    expect(checker.getSnapshot().state).toBe("ONLINE");

    await vi.advanceTimersByTimeAsync(15_000);
    await settle();

    expect(checker.getSnapshot().state).toBe("WAKING");
  });

  it("shows no countdown while it is simply up", async () => {
    const checker = new BackendStatusChecker(healthStub(true));
    checker.start();
    await settle();

    // Nothing is being waited for, so there is nothing to count down.
    expect(checker.getSnapshot().nextRetryInSec).toBeNull();
    expect(checker.getSnapshot().attempt).toBe(0);
  });

  it("stops the heartbeat when the tab is hidden", async () => {
    const health = healthStub(true);
    const checker = new BackendStatusChecker(health);
    checker.start();
    await settle();

    checker.setPaused(true);
    const before = health.mock.calls.length;

    await vi.advanceTimersByTimeAsync(90_000);
    expect(health.mock.calls.length).toBe(before);
  });
});

describe("server rendering", () => {
  it("renders the neutral state, because no checker runs there", () => {
    const checker = new BackendStatusChecker(healthStub(true));
    expect(checker.getServerSnapshot().state).toBe("CHECKING");
  });
});
