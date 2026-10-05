import type { ProblemDetail, SessionResponse } from "./api-types";

/**
 * The single API client. Every call to the backend goes through here.
 *
 * Centralised so that credential handling, the guest-session lifecycle and Problem Details
 * parsing exist in exactly one place. A second fetch call elsewhere in the app would be a bug:
 * it would miss the session header, the retry, or both.
 */

const BASE_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

/** `sessionStorage`, so the token is per tab and gone when the tab closes. */
const GUEST_TOKEN_KEY = "medi-scan.guestToken";

/**
 * A failed request, carrying the contract's stable `code`.
 *
 * Always switch on `code`, never on `title` or `detail` — those are for humans and may be
 * reworded at any time.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly requestId: string;
  readonly fieldErrors: { field: string; message: string }[];
  readonly retryAfterSeconds: number | null;

  constructor(
    status: number,
    code: string,
    detail: string,
    requestId: string,
    fieldErrors: { field: string; message: string }[] = [],
    retryAfterSeconds: number | null = null,
  ) {
    super(detail);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.requestId = requestId;
    this.fieldErrors = fieldErrors;
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

// ---------------------------------------------------------------------------
// Guest session
// ---------------------------------------------------------------------------

function readGuestToken(): string | null {
  // Guarded because this module is imported by server components too, where there is no
  // `sessionStorage`.
  if (typeof window === "undefined") return null;
  return window.sessionStorage.getItem(GUEST_TOKEN_KEY);
}

function writeGuestToken(token: string): void {
  if (typeof window === "undefined") return;
  window.sessionStorage.setItem(GUEST_TOKEN_KEY, token);
}

function clearGuestToken(): void {
  if (typeof window === "undefined") return;
  window.sessionStorage.removeItem(GUEST_TOKEN_KEY);
}

/**
 * Shared in-flight session creation.
 *
 * Without this, two components needing a session at once would each POST `/api/sessions`,
 * burning two of the 20-per-hour allowance and leaving one orphaned session owning nothing.
 */
let sessionInFlight: Promise<string> | null = null;

/**
 * Returns the guest token, creating a session only if there isn't one.
 *
 * Lazy by design (contract section 2.2): page loads and browsing the biomarker pages must not
 * create sessions, so this is called from the request path, never from a component mount.
 */
async function ensureGuestToken(): Promise<string> {
  const existing = readGuestToken();
  if (existing) return existing;

  if (!sessionInFlight) {
    sessionInFlight = createSession().finally(() => {
      sessionInFlight = null;
    });
  }
  return sessionInFlight;
}

async function createSession(): Promise<string> {
  const response = await fetch(`${BASE_URL}/api/sessions`, {
    method: "POST",
    headers: { Accept: "application/json" },
  });

  if (!response.ok) {
    throw await toApiError(response);
  }

  const session = (await response.json()) as SessionResponse;
  writeGuestToken(session.token);
  return session.token;
}

// ---------------------------------------------------------------------------
// PHASE 6: signed-in callers
//
// This is where bearer-token support goes. Three pieces, all of them already
// specified:
//
//   1. The access token lives in a module-scoped variable — in memory only, never
//      `sessionStorage` and never a cookie, so a reload loses it (plan 4.9).
//   2. `authState` tracks "RESOLVING" | "SIGNED_IN" | "GUEST". Report pages and
//      history await the resolution (about 2s at most) before fetching, so a
//      report opened in a new tab is not fetched as a guest and wrongly 404s.
//   3. On 401 TOKEN_EXPIRED or TOKEN_INVALID: refresh once, retry once, never
//      loop. Concurrent failures in a tab must share ONE in-flight refresh —
//      two parallel refreshes look like token reuse and revoke the family.
//      Use the same single-flight shape as `sessionInFlight` above.
//
// `request()` below already picks `Authorization` over `X-Session-Token` when an
// access token exists, which is the precedence the contract requires.
// ---------------------------------------------------------------------------

/** Replaced in phase 6 by the real in-memory access token. */
function readAccessToken(): string | null {
  return null;
}

// ---------------------------------------------------------------------------
// Requests
// ---------------------------------------------------------------------------

async function toApiError(response: Response): Promise<ApiError> {
  const requestIdHeader = response.headers.get("X-Request-Id") ?? "";
  const retryAfterHeader = response.headers.get("Retry-After");
  const retryAfterSeconds = retryAfterHeader ? Number.parseInt(retryAfterHeader, 10) : null;

  let problem: Partial<ProblemDetail> = {};
  try {
    problem = (await response.json()) as Partial<ProblemDetail>;
  } catch {
    // A proxy timeout or a crash can produce a non-JSON body. The request still
    // failed, and the status is the useful part.
  }

  return new ApiError(
    response.status,
    problem.code ?? "INTERNAL_ERROR",
    problem.detail ?? `Request failed with status ${response.status}.`,
    problem.requestId ?? requestIdHeader,
    problem.errors ?? [],
    Number.isNaN(retryAfterSeconds) ? null : retryAfterSeconds,
  );
}

interface RequestOptions extends Omit<RequestInit, "body"> {
  /** Serialised as JSON unless it is already `FormData`. */
  body?: unknown;
  /** Set for endpoints that work without any credential, such as `/api/samples`. */
  anonymous?: boolean;
}

async function send(path: string, options: RequestOptions): Promise<Response> {
  const { body, anonymous, headers, ...rest } = options;

  const requestHeaders = new Headers(headers);
  requestHeaders.set("Accept", requestHeaders.get("Accept") ?? "application/json");

  if (!anonymous) {
    const accessToken = readAccessToken();
    if (accessToken) {
      // Contract section 2.1: when both credentials exist the bearer token wins.
      requestHeaders.set("Authorization", `Bearer ${accessToken}`);
    } else {
      requestHeaders.set("X-Session-Token", await ensureGuestToken());
    }
  }

  let requestBody: BodyInit | undefined;
  if (body instanceof FormData) {
    // Deliberately no Content-Type: the browser has to set the multipart boundary.
    requestBody = body;
  } else if (body !== undefined) {
    requestHeaders.set("Content-Type", "application/json");
    requestBody = JSON.stringify(body);
  }

  return fetch(`${BASE_URL}${path}`, { ...rest, headers: requestHeaders, body: requestBody });
}

/**
 * Makes a request and parses the response.
 *
 * On `401 SESSION_INVALID` the stored token is dropped, a new session is created and the call
 * is retried exactly once. One retry, never a loop: if the second attempt also fails the
 * problem is not a stale token, and retrying would hammer the rate limit.
 *
 * Callers that were holding report ids from the old session must drop them — those reports
 * belonged to the session that just went away.
 */
async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  let response = await send(path, options);

  if (response.status === 401 && !options.anonymous) {
    const error = await toApiError(response);
    if (error.code !== "SESSION_INVALID") {
      throw error;
    }

    clearGuestToken();
    response = await send(path, options);
  }

  if (!response.ok) {
    throw await toApiError(response);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  return (await response.json()) as T;
}

// ---------------------------------------------------------------------------
// Public surface
// ---------------------------------------------------------------------------

export const api = {
  /**
   * Wakes the backend and reports whether it is up.
   *
   * Public and credential-free, so it never creates a session. EC2 can be slow on a cold
   * start, which is what the "starting up" notice is for.
   */
  async health(signal?: AbortSignal): Promise<boolean> {
    try {
      const response = await fetch(`${BASE_URL}/actuator/health`, {
        headers: { Accept: "application/json" },
        signal,
      });
      return response.ok;
    } catch {
      return false;
    }
  },

  /** Exposed for tests and for the phase 6 sign-in flow, which sends it to be claimed. */
  guestToken: readGuestToken,

  /** Used on sign-in, once the guest session's reports have been claimed. */
  clearGuestToken,

  request,
};

export { BASE_URL, GUEST_TOKEN_KEY };
