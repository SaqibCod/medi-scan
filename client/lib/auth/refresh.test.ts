import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError } from "../api-error";
import { resetGuestSessionForTests } from "../guest-session";
import { authCredentials } from "./credential-provider";
import { onSignedOut, refreshSession, resetRefreshForTests } from "./refresh";
import {
  getAccessToken,
  REFRESH_STORAGE_KEY,
  resetTokensForTests,
  storeTokens,
  writeRefreshToken,
} from "./tokens";

/**
 * Refresh, single flight, and the 401 path (LLD 10, unit test 3).
 *
 * The thing under test is not "does it refresh" but "does it refresh exactly once". Refresh
 * tokens rotate, so a second concurrent rotation presents an already-used token, which the
 * server correctly reads as theft and answers by revoking the family. A page that fires six
 * queries at once has to come out of that with one rotation and six retries.
 */

function tokenResponse(suffix: string, minutesValid = 15) {
  return {
    accessToken: `access-${suffix}`,
    accessTokenExpiresAt: new Date(Date.now() + minutesValid * 60_000).toISOString(),
    refreshToken: `refresh-${suffix}`,
    refreshTokenExpiresAt: new Date(Date.now() + 7 * 86_400_000).toISOString(),
    user: { id: "u1", displayName: "Saq", role: "USER" as const, createdAt: "2026-10-02T09:30:00Z" },
  };
}

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function problem(status: number, code: string) {
  return new Response(
    JSON.stringify({ type: "about:blank", title: code, status, detail: "", code, requestId: "r1" }),
    { status, headers: { "Content-Type": "application/problem+json" } },
  );
}

/** The 401 the API client hands to `onAuthFailure` (contract 8.1). */
function tokenExpired(): ApiError {
  return new ApiError({
    status: 401,
    code: "TOKEN_EXPIRED",
    title: "TOKEN_EXPIRED",
    detail: "",
    requestId: "r1",
  });
}

let fetchMock: ReturnType<typeof vi.fn>;

/**
 * Lets pending microtasks run.
 *
 * `refreshSession()` reaches `fetch` through the API client, which awaits its credential
 * headers first. So "the request has gone out" is one turn of the microtask queue away from
 * "the call was made", and asserting without this measures the wrong moment.
 */
function flush(): Promise<void> {
  return new Promise((done) => setTimeout(done, 0));
}

function refreshCalls(): number {
  return fetchMock.mock.calls.filter((call) => String(call[0]).endsWith("/api/auth/refresh")).length;
}

beforeEach(() => {
  resetRefreshForTests();
  resetTokensForTests();
  resetGuestSessionForTests();
  fetchMock = vi.fn();
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("single flight", () => {
  it("five concurrent callers produce exactly one request", async () => {
    writeRefreshToken("refresh-0");

    let release: (value: Response) => void = () => {};
    fetchMock.mockImplementation(
      () => new Promise<Response>((resolve) => (release = resolve)),
    );

    const all = Promise.all([
      refreshSession(),
      refreshSession(),
      refreshSession(),
      refreshSession(),
      refreshSession(),
    ]);

    await flush();

    // All five joined before any of them could finish.
    expect(refreshCalls()).toBe(1);

    release(json(200, tokenResponse("1")));
    const results = await all;

    expect(refreshCalls()).toBe(1);
    // And every one of them got the same answer, not four nulls and a session.
    results.forEach((result) => expect(result?.user.displayName).toBe("Saq"));
    expect(getAccessToken()).toBe("access-1");
  });

  it("five concurrent 401s cause one refresh and five retries", async () => {
    writeRefreshToken("refresh-0");
    storeTokens(tokenResponse("stale"));

    let release: (value: Response) => void = () => {};
    fetchMock.mockImplementation(
      () => new Promise<Response>((resolve) => (release = resolve)),
    );

    const decisions = Promise.all(
      Array.from({ length: 5 }, () => authCredentials.onAuthFailure(tokenExpired())),
    );

    await flush();
    expect(refreshCalls()).toBe(1);

    release(json(200, tokenResponse("fresh")));

    expect(await decisions).toEqual(["retry", "retry", "retry", "retry", "retry"]);
    expect(refreshCalls()).toBe(1);
  });

  it("a 401 that lands just after a refresh retries without rotating again", async () => {
    writeRefreshToken("refresh-0");
    fetchMock.mockResolvedValue(json(200, tokenResponse("1")));

    await refreshSession();
    expect(refreshCalls()).toBe(1);

    // The call that failed while that refresh was in the air, reporting a moment too late.
    await expect(authCredentials.onAuthFailure(tokenExpired())).resolves.toBe("retry");
    expect(refreshCalls()).toBe(1);
  });
});

describe("rotation", () => {
  it("replaces both tokens together", async () => {
    writeRefreshToken("refresh-0");
    fetchMock.mockResolvedValue(json(200, tokenResponse("1")));

    await refreshSession();

    expect(getAccessToken()).toBe("access-1");
    expect(window.sessionStorage.getItem(REFRESH_STORAGE_KEY)).toBe("refresh-1");
  });

  it("sends the stored refresh token and no credential header", async () => {
    writeRefreshToken("refresh-0");
    fetchMock.mockResolvedValue(json(200, tokenResponse("1")));

    await refreshSession();

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(JSON.parse(String(init.body))).toEqual({ refreshToken: "refresh-0" });

    const headers = init.headers as Record<string, string>;
    expect(headers.Authorization).toBeUndefined();
    expect(headers["X-Session-Token"]).toBeUndefined();
  });
});

describe("when there is nothing to refresh", () => {
  it("resolves to null without calling the server", async () => {
    await expect(refreshSession()).resolves.toBeNull();
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe("a rejected refresh token", () => {
  it("clears both tokens, reports a sign-out, and resolves null", async () => {
    writeRefreshToken("refresh-0");
    storeTokens(tokenResponse("old"));

    const reasons: string[] = [];
    onSignedOut((reason) => reasons.push(reason));

    fetchMock.mockResolvedValue(problem(401, "REFRESH_TOKEN_INVALID"));

    await expect(refreshSession()).resolves.toBeNull();

    expect(reasons).toEqual(["REFRESH_REJECTED"]);
    expect(getAccessToken()).toBeNull();
    expect(window.sessionStorage.getItem(REFRESH_STORAGE_KEY)).toBeNull();
  });

  it("makes the 401 path give up rather than retry", async () => {
    writeRefreshToken("refresh-0");
    storeTokens(tokenResponse("old"));
    fetchMock.mockResolvedValue(problem(401, "REFRESH_TOKEN_INVALID"));

    await expect(authCredentials.onAuthFailure(tokenExpired())).resolves.toBe("fail");
  });
});

describe("a refresh that could not be delivered", () => {
  it("throws and keeps the token, because a dropped connection is not a sign-out", async () => {
    writeRefreshToken("refresh-0");

    const reasons: string[] = [];
    onSignedOut((reason) => reasons.push(reason));

    fetchMock.mockRejectedValue(new TypeError("Failed to fetch"));

    await expect(refreshSession()).rejects.toMatchObject({ code: "NETWORK_ERROR" });

    expect(window.sessionStorage.getItem(REFRESH_STORAGE_KEY)).toBe("refresh-0");
    expect(reasons).toEqual([]);
  });

  it("is reported as a failure to the 401 path without signing anyone out", async () => {
    writeRefreshToken("refresh-0");
    storeTokens(tokenResponse("old"));
    fetchMock.mockRejectedValue(new TypeError("Failed to fetch"));

    await expect(authCredentials.onAuthFailure(tokenExpired())).resolves.toBe("fail");
    expect(window.sessionStorage.getItem(REFRESH_STORAGE_KEY)).toBe("refresh-old");
  });

  it("can be retried after the failure, rather than staying stuck", async () => {
    writeRefreshToken("refresh-0");
    fetchMock.mockRejectedValueOnce(new TypeError("Failed to fetch"));

    await expect(refreshSession()).rejects.toBeTruthy();

    fetchMock.mockResolvedValue(json(200, tokenResponse("2")));
    await expect(refreshSession()).resolves.toMatchObject({ user: { displayName: "Saq" } });
  });
});

describe("the bearer header", () => {
  it("is absent when nobody is signed in, so the call falls back to a guest", async () => {
    await expect(authCredentials.authorization()).resolves.toBeNull();
  });

  it("carries the in-memory access token", async () => {
    storeTokens(tokenResponse("live"));
    await expect(authCredentials.authorization()).resolves.toBe("Bearer access-live");
  });

  it("refreshes first when the token it holds has already expired", async () => {
    storeTokens(tokenResponse("expired", -1));
    fetchMock.mockResolvedValue(json(200, tokenResponse("renewed")));

    await expect(authCredentials.authorization()).resolves.toBe("Bearer access-renewed");
    expect(refreshCalls()).toBe(1);
  });
});
