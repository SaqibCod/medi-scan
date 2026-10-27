import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError } from "./api-error";
import {
  clearGuestSession,
  ensureGuestSession,
  GUEST_SESSION_ENDED,
  GUEST_STORAGE_KEY,
  handleSessionInvalid,
  peekGuestSession,
  resetGuestSessionForTests,
} from "./guest-session";

function session(hoursFromNow = 24) {
  return {
    token: "guest-token-abc",
    expiresAt: new Date(Date.now() + hoursFromNow * 3_600_000).toISOString(),
  };
}

beforeEach(() => {
  resetGuestSessionForTests();
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("creating a session", () => {
  it("is lazy: importing and reading create nothing", () => {
    const create = vi.fn();

    // Nothing has asked for a session, so nothing should have been requested. Page loads and
    // browsing the biomarker pages must not burn one of the 20 per hour.
    expect(peekGuestSession()).toBeNull();
    expect(create).not.toHaveBeenCalled();
    expect(window.sessionStorage.getItem(GUEST_STORAGE_KEY)).toBeNull();
  });

  it("creates one on the first guest action, and stores it", async () => {
    const create = vi.fn().mockResolvedValue(session());

    const result = await ensureGuestSession(create);

    expect(create).toHaveBeenCalledOnce();
    expect(result.token).toBe("guest-token-abc");
    expect(JSON.parse(window.sessionStorage.getItem(GUEST_STORAGE_KEY)!)).toMatchObject({
      token: "guest-token-abc",
    });
  });

  it("shares one request between concurrent callers", async () => {
    let resolve!: (v: unknown) => void;
    const create = vi.fn().mockReturnValue(new Promise((r) => (resolve = r)));

    // Five components all needing a session on the same tick.
    const all = Promise.all(Array.from({ length: 5 }, () => ensureGuestSession(create)));
    resolve(session());
    const results = await all;

    expect(create).toHaveBeenCalledOnce();
    expect(new Set(results.map((r) => r.token)).size).toBe(1);
  });

  it("lets a later caller try again after a failed creation", async () => {
    const create = vi
      .fn()
      .mockRejectedValueOnce(new ApiError({ status: 429, code: "RATE_LIMITED" }))
      .mockResolvedValueOnce(session());

    await expect(ensureGuestSession(create)).rejects.toBeInstanceOf(ApiError);
    await expect(ensureGuestSession(create)).resolves.toMatchObject({ token: "guest-token-abc" });

    expect(create).toHaveBeenCalledTimes(2);
  });

  it("passes a rate limit straight to the caller, with the wait", async () => {
    const create = vi
      .fn()
      .mockRejectedValue(new ApiError({ status: 429, code: "RATE_LIMITED", retryAfterSec: 120 }));

    const error = (await ensureGuestSession(create).catch((e: unknown) => e)) as ApiError;

    expect(error.code).toBe("RATE_LIMITED");
    expect(error.retryAfterSec).toBe(120);
  });
});

describe("reusing a session", () => {
  it("reuses a stored token rather than making another", async () => {
    window.sessionStorage.setItem(GUEST_STORAGE_KEY, JSON.stringify(session()));
    const create = vi.fn();

    const result = await ensureGuestSession(create);

    expect(create).not.toHaveBeenCalled();
    expect(result.token).toBe("guest-token-abc");
  });

  it("replaces one that is within a minute of expiring", async () => {
    // Renewing early, so a call cannot leave with a token that dies in flight.
    const nearlyDead = { token: "old", expiresAt: new Date(Date.now() + 30_000).toISOString() };
    window.sessionStorage.setItem(GUEST_STORAGE_KEY, JSON.stringify(nearlyDead));
    const create = vi.fn().mockResolvedValue(session());

    const result = await ensureGuestSession(create);

    expect(create).toHaveBeenCalledOnce();
    expect(result.token).toBe("guest-token-abc");
  });

  it("keeps one that has more than a minute left", async () => {
    const fine = { token: "still-good", expiresAt: new Date(Date.now() + 120_000).toISOString() };
    window.sessionStorage.setItem(GUEST_STORAGE_KEY, JSON.stringify(fine));
    const create = vi.fn();

    await expect(ensureGuestSession(create)).resolves.toMatchObject({ token: "still-good" });
    expect(create).not.toHaveBeenCalled();
  });

  it("ignores junk in the storage key", async () => {
    window.sessionStorage.setItem(GUEST_STORAGE_KEY, "not json at all");
    const create = vi.fn().mockResolvedValue(session());

    await expect(ensureGuestSession(create)).resolves.toMatchObject({ token: "guest-token-abc" });
    expect(create).toHaveBeenCalledOnce();
  });
});

describe("storage rules", () => {
  it("uses sessionStorage and never touches localStorage", async () => {
    const create = vi.fn().mockResolvedValue(session());
    await ensureGuestSession(create);

    expect(window.sessionStorage.getItem(GUEST_STORAGE_KEY)).toBeTruthy();
    // The guest token is a credential. localStorage holds exactly one thing, and it is the
    // theme (root CLAUDE.md).
    expect(window.localStorage.length).toBe(0);
  });

  it("survives storage being unavailable", async () => {
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("storage disabled");
    });
    const create = vi.fn().mockResolvedValue(session());

    // Private mode: the token cannot be remembered, but the call it was fetched for must
    // still go through.
    await expect(ensureGuestSession(create)).resolves.toMatchObject({ token: "guest-token-abc" });
  });
});

describe("when the server says the session is gone", () => {
  it("clears the token and announces it", () => {
    window.sessionStorage.setItem(GUEST_STORAGE_KEY, JSON.stringify(session()));
    const listener = vi.fn();
    window.addEventListener(GUEST_SESSION_ENDED, listener);

    handleSessionInvalid(new ApiError({ status: 401, code: "SESSION_INVALID" }));

    expect(window.sessionStorage.getItem(GUEST_STORAGE_KEY)).toBeNull();
    expect(listener).toHaveBeenCalledOnce();

    window.removeEventListener(GUEST_SESSION_ENDED, listener);
  });

  it("puts no token in the event it publishes", () => {
    window.sessionStorage.setItem(GUEST_STORAGE_KEY, JSON.stringify(session()));
    let payload = "";
    const listener = (e: Event) => {
      payload = JSON.stringify((e as CustomEvent).detail);
    };
    window.addEventListener(GUEST_SESSION_ENDED, listener);

    handleSessionInvalid(new ApiError({ status: 401, code: "SESSION_INVALID" }));

    expect(payload).not.toContain("guest-token-abc");
    window.removeEventListener(GUEST_SESSION_ENDED, listener);
  });
});

describe("peeking", () => {
  it("returns nothing once the session is cleared", () => {
    window.sessionStorage.setItem(GUEST_STORAGE_KEY, JSON.stringify(session()));
    expect(peekGuestSession()).not.toBeNull();

    clearGuestSession();
    expect(peekGuestSession()).toBeNull();
  });

  it("treats an expired session as no session", () => {
    const expired = { token: "old", expiresAt: new Date(Date.now() - 1000).toISOString() };
    window.sessionStorage.setItem(GUEST_STORAGE_KEY, JSON.stringify(expired));

    expect(peekGuestSession()).toBeNull();
  });
});
