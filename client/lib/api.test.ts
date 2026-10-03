import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError, api, BASE_URL, GUEST_TOKEN_KEY } from "./api";

/**
 * Tests for the API client's three load-bearing behaviours: lazy session creation, the
 * single retry on `SESSION_INVALID`, and Problem Details parsing.
 *
 * These are worth testing because each one is invisible when it breaks. A session created
 * eagerly still works, it just burns the rate limit; a retry loop still works, it just
 * hammers the server; an unparsed Problem Details still shows an error, just the wrong one.
 */

type FetchMock = ReturnType<typeof vi.fn>;

function jsonResponse(body: unknown, init: ResponseInit = {}): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
    ...init,
  });
}

function problemResponse(
  status: number,
  code: string,
  extra: Record<string, unknown> = {},
  headers: Record<string, string> = {},
): Response {
  return new Response(
    JSON.stringify({
      type: `https://medi-scan.dev/errors/${code.toLowerCase().replace(/_/g, "-")}`,
      title: code,
      status,
      detail: "Something went wrong.",
      code,
      requestId: "req-1234",
      ...extra,
    }),
    {
      status,
      headers: { "Content-Type": "application/problem+json", "X-Request-Id": "req-1234", ...headers },
    },
  );
}

let fetchMock: FetchMock;

beforeEach(() => {
  window.sessionStorage.clear();
  fetchMock = vi.fn();
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("guest sessions", () => {
  it("does not create a session until a call needs one", async () => {
    // Importing the module and reading the token must not touch the network. A session
    // created on page load would be a session for someone who only read a biomarker page.
    expect(api.guestToken()).toBeNull();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("creates a session on the first authenticated call and stores the token", async () => {
    fetchMock
      .mockResolvedValueOnce(
        jsonResponse({ token: "t".repeat(43), expiresAt: "2026-10-04T09:30:00Z" }, { status: 201 }),
      )
      .mockResolvedValueOnce(jsonResponse({ ok: true }));

    await api.request("/api/reports");

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(fetchMock.mock.calls[0][0]).toBe(`${BASE_URL}/api/sessions`);
    expect(fetchMock.mock.calls[0][1]).toMatchObject({ method: "POST" });

    expect(window.sessionStorage.getItem(GUEST_TOKEN_KEY)).toBe("t".repeat(43));
  });

  it("sends the token as X-Session-Token", async () => {
    window.sessionStorage.setItem(GUEST_TOKEN_KEY, "existing-token");
    fetchMock.mockResolvedValueOnce(jsonResponse({ ok: true }));

    await api.request("/api/reports");

    const headers = fetchMock.mock.calls[0][1].headers as Headers;
    expect(headers.get("X-Session-Token")).toBe("existing-token");
    expect(headers.get("Authorization")).toBeNull();
  });

  it("reuses an existing token instead of creating another session", async () => {
    window.sessionStorage.setItem(GUEST_TOKEN_KEY, "existing-token");
    fetchMock.mockResolvedValueOnce(jsonResponse({ ok: true }));

    await api.request("/api/reports");

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0][0]).toBe(`${BASE_URL}/api/reports`);
  });

  it("shares one session creation between concurrent calls", async () => {
    fetchMock.mockImplementation((url: string) => {
      if (url.endsWith("/api/sessions")) {
        return Promise.resolve(
          jsonResponse({ token: "shared-token", expiresAt: "2026-10-04T09:30:00Z" }, { status: 201 }),
        );
      }
      return Promise.resolve(jsonResponse({ ok: true }));
    });

    await Promise.all([
      api.request("/api/reports"),
      api.request("/api/reports"),
      api.request("/api/reports"),
    ]);

    // Three parallel calls, one session. Three sessions would burn the 20/hour allowance and
    // leave two of them owning nothing.
    const sessionCalls = fetchMock.mock.calls.filter((call) => String(call[0]).endsWith("/api/sessions"));
    expect(sessionCalls).toHaveLength(1);
  });

  it("does not attach a credential to anonymous calls", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse([]));

    await api.request("/api/samples", { anonymous: true });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const headers = fetchMock.mock.calls[0][1].headers as Headers;
    expect(headers.get("X-Session-Token")).toBeNull();
  });

  it("health never creates a session", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({ status: "UP" }));

    await expect(api.health()).resolves.toBe(true);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0][0]).toBe(`${BASE_URL}/actuator/health`);
  });

  it("health reports false instead of throwing when the backend is unreachable", async () => {
    fetchMock.mockRejectedValueOnce(new TypeError("Failed to fetch"));

    // A cold EC2 box is the expected case, not an exception: the UI shows a notice.
    await expect(api.health()).resolves.toBe(false);
  });
});

describe("SESSION_INVALID retry", () => {
  it("creates a new session and retries once", async () => {
    window.sessionStorage.setItem(GUEST_TOKEN_KEY, "stale-token");

    fetchMock
      .mockResolvedValueOnce(problemResponse(401, "SESSION_INVALID"))
      .mockResolvedValueOnce(
        jsonResponse({ token: "fresh-token", expiresAt: "2026-10-04T09:30:00Z" }, { status: 201 }),
      )
      .mockResolvedValueOnce(jsonResponse({ ok: true }));

    await expect(api.request("/api/reports")).resolves.toEqual({ ok: true });

    expect(window.sessionStorage.getItem(GUEST_TOKEN_KEY)).toBe("fresh-token");

    const retryHeaders = fetchMock.mock.calls[2][1].headers as Headers;
    expect(retryHeaders.get("X-Session-Token")).toBe("fresh-token");
  });

  it("retries only once, then throws", async () => {
    window.sessionStorage.setItem(GUEST_TOKEN_KEY, "stale-token");

    fetchMock.mockImplementation((url: string) => {
      if (url.endsWith("/api/sessions")) {
        return Promise.resolve(
          jsonResponse({ token: "fresh-token", expiresAt: "2026-10-04T09:30:00Z" }, { status: 201 }),
        );
      }
      return Promise.resolve(problemResponse(401, "SESSION_INVALID"));
    });

    await expect(api.request("/api/reports")).rejects.toBeInstanceOf(ApiError);

    // The guard that matters: a loop here would spend the rate-limit allowance in seconds.
    const reportCalls = fetchMock.mock.calls.filter((call) => String(call[0]).endsWith("/api/reports"));
    expect(reportCalls).toHaveLength(2);
  });

  it("does not retry a 401 with a different code", async () => {
    window.sessionStorage.setItem(GUEST_TOKEN_KEY, "a-token");
    fetchMock.mockResolvedValueOnce(problemResponse(401, "AUTH_REQUIRED"));

    await expect(api.request("/api/me")).rejects.toMatchObject({ code: "AUTH_REQUIRED" });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    // The token was valid; it is just not a user credential. Dropping it would lose the
    // guest's reports for no reason.
    expect(window.sessionStorage.getItem(GUEST_TOKEN_KEY)).toBe("a-token");
  });

  it("does not retry other statuses", async () => {
    window.sessionStorage.setItem(GUEST_TOKEN_KEY, "a-token");
    fetchMock.mockResolvedValueOnce(problemResponse(404, "REPORT_NOT_FOUND"));

    await expect(api.request("/api/reports/abc")).rejects.toMatchObject({ code: "REPORT_NOT_FOUND" });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});

describe("Problem Details parsing", () => {
  it("maps the body onto a typed ApiError", async () => {
    window.sessionStorage.setItem(GUEST_TOKEN_KEY, "a-token");
    fetchMock.mockResolvedValueOnce(problemResponse(404, "REPORT_NOT_FOUND"));

    const error = await api.request("/api/reports/abc").catch((caught: unknown) => caught);

    expect(error).toBeInstanceOf(ApiError);
    const apiError = error as ApiError;
    expect(apiError.status).toBe(404);
    expect(apiError.code).toBe("REPORT_NOT_FOUND");
    expect(apiError.requestId).toBe("req-1234");
    expect(apiError.message).toBe("Something went wrong.");
  });

  it("exposes the errors array on a validation failure", async () => {
    window.sessionStorage.setItem(GUEST_TOKEN_KEY, "a-token");
    fetchMock.mockResolvedValueOnce(
      problemResponse(400, "VALIDATION_ERROR", {
        errors: [{ field: "question", message: "must be between 1 and 500 characters" }],
      }),
    );

    const error = (await api
      .request("/api/reports/abc/chat", { method: "POST", body: { question: "" } })
      .catch((caught: unknown) => caught)) as ApiError;

    expect(error.code).toBe("VALIDATION_ERROR");
    expect(error.fieldErrors).toEqual([
      { field: "question", message: "must be between 1 and 500 characters" },
    ]);
  });

  it("reads Retry-After on a 429", async () => {
    window.sessionStorage.setItem(GUEST_TOKEN_KEY, "a-token");
    fetchMock.mockResolvedValueOnce(
      problemResponse(429, "RATE_LIMITED", {}, { "Retry-After": "120" }),
    );

    const error = (await api
      .request("/api/reports", { method: "POST", body: {} })
      .catch((caught: unknown) => caught)) as ApiError;

    expect(error.code).toBe("RATE_LIMITED");
    expect(error.retryAfterSeconds).toBe(120);
  });

  it("falls back to INTERNAL_ERROR when the body is not Problem Details", async () => {
    window.sessionStorage.setItem(GUEST_TOKEN_KEY, "a-token");
    fetchMock.mockResolvedValueOnce(
      new Response("<html>502 Bad Gateway</html>", {
        status: 502,
        headers: { "Content-Type": "text/html", "X-Request-Id": "req-gateway" },
      }),
    );

    // A proxy returning HTML must still produce a usable typed error, not a JSON parse crash.
    const error = (await api.request("/api/reports").catch((caught: unknown) => caught)) as ApiError;

    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(502);
    expect(error.code).toBe("INTERNAL_ERROR");
    expect(error.requestId).toBe("req-gateway");
  });

  it("returns undefined for a 204", async () => {
    window.sessionStorage.setItem(GUEST_TOKEN_KEY, "a-token");
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }));

    await expect(api.request("/api/reports/abc", { method: "DELETE" })).resolves.toBeUndefined();
  });

  it("serialises a JSON body and sets Content-Type", async () => {
    window.sessionStorage.setItem(GUEST_TOKEN_KEY, "a-token");
    fetchMock.mockResolvedValueOnce(jsonResponse({ ok: true }));

    await api.request("/api/reports", { method: "POST", body: { consent: true } });

    const init = fetchMock.mock.calls[0][1];
    expect((init.headers as Headers).get("Content-Type")).toBe("application/json");
    expect(init.body).toBe(JSON.stringify({ consent: true }));
  });

  it("leaves Content-Type unset for FormData so the browser adds the boundary", async () => {
    window.sessionStorage.setItem(GUEST_TOKEN_KEY, "a-token");
    fetchMock.mockResolvedValueOnce(jsonResponse({ ok: true }));

    const form = new FormData();
    form.set("consent", "true");

    await api.request("/api/reports", { method: "POST", body: form });

    const init = fetchMock.mock.calls[0][1];
    expect((init.headers as Headers).get("Content-Type")).toBeNull();
    expect(init.body).toBeInstanceOf(FormData);
  });
});
