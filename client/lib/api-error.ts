import type { ProblemDetail } from "./api-types";

/**
 * Error codes the client invents for failures that never reached the server.
 *
 * They are deliberately NOT in docs/api-contract.md — nothing sends them, and adding them
 * there would imply the backend can return them. The UI switches on them the same way it
 * switches on the contract's codes.
 */
export const CLIENT_ERROR_CODES = ["NETWORK_ERROR", "TIMEOUT", "UNKNOWN_ERROR"] as const;

export type ClientErrorCode = (typeof CLIENT_ERROR_CODES)[number];

export interface FieldError {
  field: string;
  message: string;
}

/**
 * A failed request, carrying the contract's stable `code`.
 *
 * Always switch on `code`, never on `title` or `detail` — those are written for humans and
 * may be reworded at any time (root CLAUDE.md).
 */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly title: string;
  readonly detail: string;
  readonly requestId: string | null;
  readonly errors: FieldError[];
  readonly retryAfterSec: number | null;

  constructor(init: {
    status: number;
    code: string;
    title?: string;
    detail?: string;
    requestId?: string | null;
    errors?: FieldError[];
    retryAfterSec?: number | null;
  }) {
    // The message is for a developer reading a stack trace. It must never carry the response
    // body: a report's contents, or a session token echoed back in a detail string, would end
    // up in any log or error reporter that records `error.message`.
    super(
      `${init.status} ${init.code}${init.requestId ? ` (requestId ${init.requestId})` : ""}`,
    );

    this.name = "ApiError";
    this.status = init.status;
    this.code = init.code;
    this.title = init.title ?? "";
    this.detail = init.detail ?? "";
    this.requestId = init.requestId ?? null;
    this.errors = init.errors ?? [];
    this.retryAfterSec = init.retryAfterSec ?? null;
  }

  /** Same rule as `message`: status, code and request id only. */
  override toString(): string {
    return `ApiError: ${this.message}`;
  }

  get isClientSide(): boolean {
    return (CLIENT_ERROR_CODES as readonly string[]).includes(this.code);
  }

  /** 5xx, a timeout or a dropped connection — the failures worth trying again. */
  get isRetryable(): boolean {
    return this.status >= 500 || this.code === "NETWORK_ERROR" || this.code === "TIMEOUT";
  }
}

function parseRetryAfter(header: string | null): number | null {
  if (!header) return null;

  // RFC 9110 allows seconds or an HTTP date. The API always sends seconds, but a proxy in
  // front of it may not.
  const seconds = Number.parseInt(header, 10);
  if (!Number.isNaN(seconds)) return Math.max(0, seconds);

  const date = Date.parse(header);
  if (!Number.isNaN(date)) return Math.max(0, Math.round((date - Date.now()) / 1000));

  return null;
}

/**
 * Turns a non-2xx response into an `ApiError`.
 *
 * Every error the backend produces is RFC 9457 Problem Details with `code` and `requestId`
 * (contract 1.5). A body that is not that — a proxy timeout page, a crash before the handler
 * ran — still has a usable status, so the status decides the code.
 */
export async function toApiError(response: Response): Promise<ApiError> {
  const requestId = response.headers.get("X-Request-Id");
  const retryAfterSec = parseRetryAfter(response.headers.get("Retry-After"));

  let problem: Partial<ProblemDetail> | null = null;
  try {
    const body: unknown = await response.json();
    if (body && typeof body === "object") problem = body as Partial<ProblemDetail>;
  } catch {
    // Not JSON. Nothing to recover, and nothing worth logging — the body could be anything.
  }

  const fallbackCode = response.status >= 500 ? "INTERNAL_ERROR" : "UNKNOWN_ERROR";

  return new ApiError({
    status: response.status,
    // An unrecognised code is passed through untouched; the UI falls back by status. That is
    // what the contract's compatibility rules ask of clients (9.2).
    code: typeof problem?.code === "string" && problem.code ? problem.code : fallbackCode,
    title: typeof problem?.title === "string" ? problem.title : undefined,
    detail: typeof problem?.detail === "string" ? problem.detail : undefined,
    requestId: typeof problem?.requestId === "string" ? problem.requestId : requestId,
    errors: Array.isArray(problem?.errors) ? problem.errors : [],
    retryAfterSec,
  });
}

export function networkError(): ApiError {
  return new ApiError({ status: 0, code: "NETWORK_ERROR" });
}

export function timeoutError(timeoutMs: number): ApiError {
  return new ApiError({ status: 0, code: "TIMEOUT", detail: `Timed out after ${timeoutMs}ms` });
}
