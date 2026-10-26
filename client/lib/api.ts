import { ApiError, networkError, timeoutError, toApiError } from "./api-error";
import { getBackendStatusChecker } from "./backend-status";
import { getCredentialProvider } from "./credentials";
import { API_URL } from "./env";
import { publishDailyLimit } from "./notices";
import {
  ensureGuestSession,
  handleSessionInvalid,
  type GuestSession,
} from "./guest-session";
import type { SessionResponse } from "./api-types";

/**
 * The one API client. Every call to the backend goes through here.
 *
 * Centralised because credential handling, the guest-session lifecycle, timeouts and Problem
 * Details parsing have to exist in exactly one place. A second `fetch` elsewhere in the app
 * would be a bug: it would miss the session header, or the retry, or both.
 */

/** `auto` = bearer when signed in, otherwise the guest token. 7c makes that branch real. */
export type Auth = "none" | "guest" | "auto";

export interface RequestOptions {
  body?: unknown;
  auth?: Auth;
  signal?: AbortSignal;
  timeoutMs?: number;
  headers?: Record<string, string>;
}

const DEFAULT_TIMEOUT_MS = 20_000;

/**
 * Logs one line per request in development, and nothing in production.
 *
 * Method, path, status, code and request id only. Never a body, never a header — the headers
 * carry the session token, and the bodies carry report content.
 */
function logRequest(method: string, path: string, status: number, code?: string, requestId?: string | null) {
  if (process.env.NODE_ENV !== "development") return;
  const parts = [method, path, String(status)];
  if (code) parts.push(code);
  if (requestId) parts.push(requestId);
  console.debug(`[api] ${parts.join(" ")}`);
}

async function createSession(): Promise<SessionResponse> {
  return request<SessionResponse>("POST", "/api/sessions", { auth: "none" });
}

/** Resolves the credential headers for one attempt. Never sends both kinds at once. */
async function credentialHeaders(auth: Auth): Promise<Record<string, string>> {
  if (auth === "none") return {};

  if (auth === "auto") {
    const authorization = await getCredentialProvider().authorization();
    // The contract says bearer wins when both are sent, but sending both would leak the guest
    // token to no purpose. One credential per request.
    if (authorization) return { Authorization: authorization };
  }

  const session: GuestSession = await ensureGuestSession(createSession);
  return { "X-Session-Token": session.token };
}

async function attempt<T>(
  method: string,
  path: string,
  options: RequestOptions,
  headers: Record<string, string>,
): Promise<T> {
  const timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS;

  // The caller may have aborted between asking for the call and this running — a component
  // that unmounted while the credential headers were being resolved. `addEventListener` does
  // not fire for an already-aborted signal, so without this the request would go out and then
  // hang on a listener that can never be called.
  options.signal?.throwIfAborted();

  // One controller per attempt, aborting either on timeout or because the caller said so.
  const controller = new AbortController();
  const onCallerAbort = () => controller.abort();
  options.signal?.addEventListener("abort", onCallerAbort, { once: true });
  const timer = setTimeout(() => controller.abort(), timeoutMs);

  const requestHeaders: Record<string, string> = {
    Accept: "application/json",
    ...headers,
    ...options.headers,
  };

  let body: BodyInit | undefined;
  if (options.body instanceof FormData) {
    // Deliberately no Content-Type: the browser must set the multipart boundary itself.
    body = options.body;
  } else if (options.body !== undefined) {
    requestHeaders["Content-Type"] = "application/json";
    body = JSON.stringify(options.body);
  }

  let response: Response;
  try {
    response = await fetch(`${API_URL}${path}`, {
      method,
      headers: requestHeaders,
      body,
      // No cookie ever carries a credential here, and sending them would invite CSRF.
      credentials: "omit",
      signal: controller.signal,
    });
  } catch (cause) {
    // A caller-initiated abort is not a failure to report as one.
    if (options.signal?.aborted) throw cause;

    const error = controller.signal.aborted ? timeoutError(timeoutMs) : networkError();
    logRequest(method, path, 0, error.code);

    // A health check that passed a minute ago says nothing about now. One call that could not
    // reach the server puts the waking banner back up and restarts the checks (LLD 8.1).
    getBackendStatusChecker().reportNetworkFailure();

    throw error;
  } finally {
    clearTimeout(timer);
    options.signal?.removeEventListener("abort", onCallerAbort);
  }

  if (!response.ok) {
    const error = await toApiError(response);
    logRequest(method, path, response.status, error.code, error.requestId);

    // The whole app shares one daily LLM budget, so this is never about the caller — it is a
    // page-wide fact that blocks every upload until midnight UTC.
    if (error.code === "CAPACITY") publishDailyLimit(error.retryAfterSec);

    throw error;
  }

  // An answer from the server is better evidence than any health check.
  getBackendStatusChecker().reportSuccess();
  logRequest(method, path, response.status, undefined, response.headers.get("X-Request-Id"));

  if (response.status === 204) return undefined as T;

  // 201 from POST /api/sessions and 202 from POST /api/reports both carry a body.
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

/**
 * Makes one request, with at most one retry.
 *
 * Exactly one, never a loop: a second failure is not a stale credential, and retrying would
 * only spend the rate limit.
 */
export async function request<T>(
  method: string,
  path: string,
  options: RequestOptions = {},
): Promise<T> {
  const auth = options.auth ?? "none";
  const headers = await credentialHeaders(auth);

  try {
    return await attempt<T>(method, path, options, headers);
  } catch (error) {
    if (!(error instanceof ApiError)) throw error;

    // A bearer token the server rejected. 7c's provider refreshes once and says "retry".
    if (headers.Authorization && (error.code === "TOKEN_EXPIRED" || error.code === "TOKEN_INVALID")) {
      const decision = await getCredentialProvider().onAuthFailure(error);
      if (decision === "retry") {
        return attempt<T>(method, path, options, await credentialHeaders(auth));
      }
    }

    // The guest session is gone on the server. Drop it and tell the app; the caller decides
    // whether to start again or show the "Guest session ended" screen.
    if (error.code === "SESSION_INVALID" && headers["X-Session-Token"]) {
      handleSessionInvalid(error);
    }

    throw error;
  }
}

export const api = {
  request,

  get: <T>(path: string, options?: Omit<RequestOptions, "body">) =>
    request<T>("GET", path, options),

  post: <T>(path: string, body?: unknown, options?: RequestOptions) =>
    request<T>("POST", path, { ...options, body }),

  delete: <T>(path: string, options?: Omit<RequestOptions, "body">) =>
    request<T>("DELETE", path, options),
};
