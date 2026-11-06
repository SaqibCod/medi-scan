import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError } from "./api-error";
import { request } from "./api";
import {
  resetCredentialProvider,
  setCredentialProvider,
  type CredentialProvider,
} from "./credentials";
import { GUEST_STORAGE_KEY, resetGuestSessionForTests } from "./guest-session";

const API = "http://localhost:8080";

/** A Problem Details response, as the backend actually sends them (contract 1.5). */
function problem(
  status: number,
  code: string,
  extra: Record<string, unknown> = {},
  headers: Record<string, string> = {},
) {
  return new Response(
    JSON.stringify({
      type: `https://medi-scan.dev/errors/${code.toLowerCase()}`,
      title: code,
      status,
      detail: "Something for a human to read.",
      code,
      requestId: "req-1",
      ...extra,
    }),
    { status, headers: { "Content-Type": "application/problem+json", ...headers } },
  );
}

function json(status: number, body: unknown, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...headers },
  });
}

function aliveSession() {
  return {
    token: "guest-token-abc",
    expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
  };
}

let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  resetGuestSessionForTests();
  resetCredentialProvider();
  fetchMock = vi.fn();
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe("sending a request", () => {
  it("never sends cookies", async () => {
    fetchMock.mockResolvedValue(json(200, { ok: true }));

    await request("GET", "/api/samples");

    expect(fetchMock.mock.calls[0][1]).toMatchObject({ credentials: "omit" });
  });

  it("sends JSON bodies with a Content-Type", async () => {
    fetchMock.mockResolvedValue(json(200, {}));

    await request("POST", "/api/reports", { body: { sampleId: "lipid" } });

    const init = fetchMock.mock.calls[0][1];
    expect(init.headers["Content-Type"]).toBe("application/json");
    expect(init.body).toBe('{"sampleId":"lipid"}');
  });

  it("leaves Content-Type off FormData, so the browser can set the boundary", async () => {
    fetchMock.mockResolvedValue(json(202, {}));

    const form = new FormData();
    form.append("file", new File(["%PDF"], "r.pdf", { type: "application/pdf" }));
    await request("POST", "/api/reports", { body: form });

    const init = fetchMock.mock.calls[0][1];
    expect(init.headers["Content-Type"]).toBeUndefined();
    expect(init.body).toBeInstanceOf(FormData);
  });

  it("returns undefined for 204, rather than trying to parse nothing", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));

    await expect(request("DELETE", "/api/reports/x")).resolves.toBeUndefined();
  });
});

describe("parsing an error", () => {
  it("reads code, requestId and the field errors out of Problem Details", async () => {
    fetchMock.mockResolvedValue(
      problem(400, "VALIDATION_ERROR", {
        errors: [{ field: "question", message: "must be between 1 and 500 characters" }],
      }),
    );

    const error = await request("POST", "/api/x").catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    const api = error as ApiError;
    expect(api.status).toBe(400);
    expect(api.code).toBe("VALIDATION_ERROR");
    expect(api.requestId).toBe("req-1");
    expect(api.errors).toEqual([
      { field: "question", message: "must be between 1 and 500 characters" },
    ]);
  });

  it("reads Retry-After into retryAfterSec", async () => {
    fetchMock.mockResolvedValue(problem(429, "CAPACITY", {}, { "Retry-After": "3600" }));

    const error = (await request("POST", "/api/x").catch((e: unknown) => e)) as ApiError;

    expect(error.retryAfterSec).toBe(3600);
  });

  it("falls back to INTERNAL_ERROR for a non-JSON 5xx", async () => {
    // A proxy timeout page, or a crash before the handler ran.
    fetchMock.mockResolvedValue(
      new Response("<html>502 Bad Gateway</html>", {
        status: 502,
        headers: { "Content-Type": "text/html", "X-Request-Id": "req-9" },
      }),
    );

    const error = (await request("GET", "/api/x").catch((e: unknown) => e)) as ApiError;

    expect(error.code).toBe("INTERNAL_ERROR");
    expect(error.status).toBe(502);
    expect(error.requestId).toBe("req-9");
  });

  it("falls back to UNKNOWN_ERROR for a non-JSON 4xx", async () => {
    fetchMock.mockResolvedValue(new Response("nope", { status: 418 }));

    const error = (await request("GET", "/api/x").catch((e: unknown) => e)) as ApiError;

    expect(error.code).toBe("UNKNOWN_ERROR");
    expect(error.status).toBe(418);
  });

  it("passes an unrecognised code through untouched", async () => {
    // Contract 9.2: a new code is a compatible change, so the client must not choke on one.
    fetchMock.mockResolvedValue(problem(400, "SOMETHING_ADDED_LATER"));

    const error = (await request("GET", "/api/x").catch((e: unknown) => e)) as ApiError;

    expect(error.code).toBe("SOMETHING_ADDED_LATER");
  });

  it("keeps the response body out of the error message", async () => {
    fetchMock.mockResolvedValue(
      problem(500, "INTERNAL_ERROR", { detail: "Patient Jane Roe, MRN 12345, LDL 162" }),
    );

    const error = (await request("GET", "/api/x").catch((e: unknown) => e)) as ApiError;

    // `message` is what ends up in any log or error reporter. Report content must not.
    expect(error.message).toBe("500 INTERNAL_ERROR (requestId req-1)");
    expect(error.message).not.toContain("Jane Roe");
    expect(error.toString()).not.toContain("Jane Roe");
  });

  it("turns a dropped connection into NETWORK_ERROR", async () => {
    fetchMock.mockRejectedValue(new TypeError("Failed to fetch"));

    const error = (await request("GET", "/api/x").catch((e: unknown) => e)) as ApiError;

    expect(error.code).toBe("NETWORK_ERROR");
    expect(error.isRetryable).toBe(true);
  });

  it("turns a slow response into TIMEOUT", async () => {
    fetchMock.mockImplementation(
      (_url: string, init: RequestInit) =>
        new Promise((_resolve, reject) => {
          init.signal?.addEventListener("abort", () =>
            reject(new DOMException("Aborted", "AbortError")),
          );
        }),
    );

    const error = (await request("GET", "/api/x", { timeoutMs: 10 }).catch(
      (e: unknown) => e,
    )) as ApiError;

    expect(error.code).toBe("TIMEOUT");
  });

  it("lets a caller's own abort through as an abort, not a timeout", async () => {
    const controller = new AbortController();
    fetchMock.mockImplementation(
      (_url: string, init: RequestInit) =>
        new Promise((_resolve, reject) => {
          init.signal?.addEventListener("abort", () =>
            reject(new DOMException("Aborted", "AbortError")),
          );
        }),
    );

    const pending = request("GET", "/api/x", { signal: controller.signal }).catch(
      (e: unknown) => e,
    );
    controller.abort();

    const error = await pending;
    expect(error).not.toBeInstanceOf(ApiError);
  });
});

describe("credentials", () => {
  it("sends nothing when auth is 'none'", async () => {
    fetchMock.mockResolvedValue(json(200, {}));

    await request("GET", "/api/samples", { auth: "none" });

    const headers = fetchMock.mock.calls[0][1].headers;
    expect(headers["X-Session-Token"]).toBeUndefined();
    expect(headers.Authorization).toBeUndefined();
  });

  it("sends only X-Session-Token for a guest call", async () => {
    window.sessionStorage.setItem(GUEST_STORAGE_KEY, JSON.stringify(aliveSession()));
    fetchMock.mockResolvedValue(json(200, {}));

    await request("GET", "/api/reports", { auth: "guest" });

    const headers = fetchMock.mock.calls[0][1].headers;
    expect(headers["X-Session-Token"]).toBe("guest-token-abc");
    expect(headers.Authorization).toBeUndefined();
  });

  it("sends only Authorization when signed in, never both", async () => {
    window.sessionStorage.setItem(GUEST_STORAGE_KEY, JSON.stringify(aliveSession()));
    setCredentialProvider({
      authorization: async () => "Bearer access-token",
      onAuthFailure: async () => "fail",
    });
    fetchMock.mockResolvedValue(json(200, {}));

    await request("GET", "/api/reports", { auth: "auto" });

    const headers = fetchMock.mock.calls[0][1].headers;
    expect(headers.Authorization).toBe("Bearer access-token");
    // Sending both would leak the guest token for no reason.
    expect(headers["X-Session-Token"]).toBeUndefined();
  });

  it("falls back to the guest token when 'auto' finds nobody signed in", async () => {
    window.sessionStorage.setItem(GUEST_STORAGE_KEY, JSON.stringify(aliveSession()));
    fetchMock.mockResolvedValue(json(200, {}));

    await request("GET", "/api/reports", { auth: "auto" });

    expect(fetchMock.mock.calls[0][1].headers["X-Session-Token"]).toBe("guest-token-abc");
  });
});

describe("retrying a rejected bearer token", () => {
  function provider(decision: "retry" | "fail"): CredentialProvider & { calls: number } {
    const p = {
      calls: 0,
      authorization: async () => "Bearer access-token",
      onAuthFailure: async () => {
        p.calls += 1;
        return decision;
      },
    };
    return p;
  }

  it("retries exactly once when the provider says to", async () => {
    const p = provider("retry");
    setCredentialProvider(p);

    fetchMock
      .mockResolvedValueOnce(problem(401, "TOKEN_EXPIRED"))
      .mockResolvedValueOnce(json(200, { ok: true }));

    await expect(request("GET", "/api/me", { auth: "auto" })).resolves.toEqual({ ok: true });

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(p.calls).toBe(1);
  });

  it("does not retry when the provider says to fail", async () => {
    const p = provider("fail");
    setCredentialProvider(p);
    fetchMock.mockResolvedValue(problem(401, "TOKEN_EXPIRED"));

    await expect(request("GET", "/api/me", { auth: "auto" })).rejects.toBeInstanceOf(ApiError);

    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("never retries a second time", async () => {
    const p = provider("retry");
    setCredentialProvider(p);
    // The refresh "worked" but the new token is rejected too.
    fetchMock.mockResolvedValue(problem(401, "TOKEN_INVALID"));

    await expect(request("GET", "/api/me", { auth: "auto" })).rejects.toBeInstanceOf(ApiError);

    // One original plus one retry. A loop here would hammer the refresh rate limit and look
    // like token reuse, which revokes the whole family.
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(p.calls).toBe(1);
  });

  it("does not try to refresh a guest call", async () => {
    const p = provider("retry");
    setCredentialProvider(p);
    window.sessionStorage.setItem(GUEST_STORAGE_KEY, JSON.stringify(aliveSession()));
    fetchMock.mockResolvedValue(problem(401, "TOKEN_EXPIRED"));

    await expect(request("GET", "/api/reports", { auth: "guest" })).rejects.toBeInstanceOf(
      ApiError,
    );

    expect(p.calls).toBe(0);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});

describe("the base URL", () => {
  it("prefixes the configured API origin", async () => {
    fetchMock.mockResolvedValue(json(200, {}));

    await request("GET", "/api/samples");

    expect(fetchMock.mock.calls[0][0]).toBe(`${API}/api/samples`);
  });
});
