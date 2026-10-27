import { CLIENT_ERROR_CODES, type ApiError } from "./api-error";
import { HTTP_ERROR_CODES, REPORT_ERROR_CODES } from "./api-types";

/**
 * What the user is told, for every error code.
 *
 * Keyed on `code` and only on `code`. The server's `title` and `detail` are written for a
 * developer reading a log and may be reworded at any time, so they are never shown as the
 * main message (root CLAUDE.md). `detail` can still be offered as supporting text where the
 * server knows something we do not, like the actual file size.
 *
 * Wording comes from the mockups (`system-screens--07`, `--10`) where they have it, and from
 * the contract's "suggested UI message" column where they do not.
 */

/** Where the message belongs. Decides which component renders it, not how it is worded. */
export type Surface = "inline" | "banner" | "screen" | "toast";

export interface ErrorMessage {
  /** One short sentence, weight 500. Reads as a statement, not an apology. */
  title: string;
  /** What happened and what to do next. */
  body: string;
  surface: Surface;
  /** Offer the request id so a person can quote it. Only useful for server-side faults. */
  showRequestId?: boolean;
}

const HTTP_MESSAGES: Record<string, ErrorMessage> = {
  VALIDATION_ERROR: {
    title: "That didn't look right.",
    body: "Check the highlighted fields and try again.",
    surface: "inline",
  },
  FILE_SIGNATURE_MISMATCH: {
    title: "This file isn't what it says it is.",
    body: "The contents don't match the file extension. Try exporting the report again.",
    surface: "inline",
  },
  SESSION_INVALID: {
    title: "Your guest session has ended.",
    body: "Guest sessions last 24 hours, and their reports are deleted with them. Start again now, or sign in so your next reports are kept for 30 days.",
    surface: "screen",
  },
  AUTH_REQUIRED: {
    title: "You need to be signed in for this.",
    body: "Uploading a report, reading the results and asking questions all work without an account. This page doesn't.",
    surface: "screen",
  },
  TOKEN_EXPIRED: {
    title: "You were signed out.",
    body: "Your sign-in ended. Sign in again to see your saved reports.",
    surface: "toast",
  },
  TOKEN_INVALID: {
    title: "You were signed out.",
    body: "Your sign-in ended. Sign in again to see your saved reports.",
    surface: "toast",
  },
  GOOGLE_TOKEN_INVALID: {
    title: "Google sign-in didn't go through.",
    body: "We couldn't verify the response from Google. Please try signing in again.",
    surface: "inline",
  },
  REFRESH_TOKEN_INVALID: {
    title: "You were signed out.",
    body: "Your sign-in ended. Sign in again to see your saved reports.",
    surface: "toast",
  },
  FORBIDDEN: {
    title: "This page is for admins only.",
    body: "You're signed in, but your account doesn't have access to this page. If you think that's wrong, the role is set on the server.",
    surface: "screen",
  },
  REPORT_NOT_FOUND: {
    title: "We can't find this report.",
    body: "It was deleted, or it reached its time limit, or it belongs to a different account or browser tab.",
    surface: "screen",
  },
  SAMPLE_NOT_FOUND: {
    title: "That sample isn't available.",
    body: "Pick another sample report, or upload one of your own.",
    surface: "inline",
  },
  REPORT_NOT_READY: {
    title: "This report is still being read.",
    body: "Questions open as soon as the results are ready.",
    surface: "inline",
  },
  FILE_TOO_LARGE: {
    title: "This file is too large.",
    body: "The limit is 10 MB. Try exporting only the pages with your results.",
    surface: "inline",
  },
  UNSUPPORTED_FILE_TYPE: {
    title: "We can't read this kind of file.",
    body: "Upload a PDF, PNG or JPEG.",
    surface: "inline",
  },
  RATE_LIMITED: {
    title: "Slow down a little.",
    body: "You've made a lot of requests in a short time. Try again shortly.",
    surface: "inline",
  },
  CAPACITY: {
    title: "The demo has hit today's AI limit.",
    body: "New reports and chat open again at midnight UTC. Your saved reports still open.",
    surface: "banner",
  },
  BUSY: {
    title: "The queue is full right now.",
    body: "A few reports are being read ahead of yours. Try again in a moment.",
    surface: "inline",
  },
  INTERNAL_ERROR: {
    title: "Something broke on our side.",
    body: "Please try again in a moment. If it keeps happening, quote this request ID when you report it.",
    surface: "screen",
    showRequestId: true,
  },
};

/** `error.code` on a FAILED report. Wording from the contract's suggested messages. */
const REPORT_MESSAGES: Record<string, ErrorMessage> = {
  UNREADABLE: {
    title: "We couldn't read text from this file.",
    body: "If it's a photo, try a clearer one with the whole page in frame.",
    surface: "inline",
  },
  DOCUMENT_TOO_LONG: {
    title: "This document is too long to process.",
    body: "Try uploading only the pages with your results.",
    surface: "inline",
  },
  NO_RESULTS_FOUND: {
    title: "We didn't find lab results in this document.",
    body: "Medi-Scan reads lab reports with test names, values and reference ranges.",
    surface: "inline",
  },
  EXTRACTION_FAILED: {
    title: "Something went wrong reading the results.",
    body: "Please try again. If it keeps happening, try a different export of the report.",
    surface: "inline",
  },
  CAPACITY: {
    title: "The demo has hit today's AI limit.",
    body: "New reports and chat open again at midnight UTC. Your saved reports still open.",
    surface: "inline",
  },
  LLM_UNAVAILABLE: {
    title: "The AI service is unavailable right now.",
    body: "This is on the provider's side, not your report. Please try again later.",
    surface: "inline",
  },
  INTERRUPTED: {
    title: "Processing was interrupted.",
    body: "The server restarted while your report was being read. Please upload it again.",
    surface: "inline",
  },
};

/** Failures that never reached the server. */
const CLIENT_MESSAGES: Record<string, ErrorMessage> = {
  NETWORK_ERROR: {
    title: "We can't reach the server.",
    body: "Medi-Scan runs on a free server that sleeps when nobody is using it and can be slow to wake. Your reports are safe.",
    surface: "banner",
  },
  TIMEOUT: {
    title: "That took too long.",
    body: "The server didn't answer in time. It may still be waking up — try again in a moment.",
    surface: "banner",
  },
  UNKNOWN_ERROR: {
    title: "Something went wrong.",
    body: "Please try again. If it keeps happening, reload the page.",
    surface: "inline",
  },
};

/** Last resort, by status, for a `code` nothing in the app recognises (contract 9.2). */
function byStatus(status: number): ErrorMessage {
  if (status === 401) return HTTP_MESSAGES.SESSION_INVALID;
  if (status === 403) return HTTP_MESSAGES.FORBIDDEN;
  if (status === 404) return HTTP_MESSAGES.REPORT_NOT_FOUND;
  if (status === 429) return HTTP_MESSAGES.RATE_LIMITED;
  if (status >= 500) return HTTP_MESSAGES.INTERNAL_ERROR;
  if (status >= 400) return HTTP_MESSAGES.VALIDATION_ERROR;
  return CLIENT_MESSAGES.UNKNOWN_ERROR;
}

const ALL: Record<string, ErrorMessage> = {
  ...REPORT_MESSAGES,
  ...HTTP_MESSAGES,
  ...CLIENT_MESSAGES,
};

export function messageForCode(code: string): ErrorMessage | null {
  return ALL[code] ?? null;
}

/** The wording for an error, falling back by status when the code is one we do not know. */
export function messageForError(error: ApiError): ErrorMessage {
  return messageForCode(error.code) ?? byStatus(error.status);
}

/** Every code the UI has wording for. Used by the coverage test. */
export const COVERED_CODES: readonly string[] = Object.keys(ALL);

/**
 * Every code that must have wording: the contract's, plus the client's own.
 *
 * Widened to `string[]` deliberately — the coverage test compares it against the keys of a
 * hand-written table, and a literal union there would only make `includes` complain about
 * the very mismatch the test exists to find.
 */
export const REQUIRED_CODES: readonly string[] = [
  ...HTTP_ERROR_CODES,
  ...REPORT_ERROR_CODES,
  ...CLIENT_ERROR_CODES,
];
