import { ApiError } from "./api-error";
import type { SessionResponse } from "./api-types";

/**
 * The guest session.
 *
 * Stored in `sessionStorage`, never `localStorage` and never a cookie (root CLAUDE.md). That
 * makes it per tab, which is also what the contract describes: a guest's new tab gets its own
 * session the first time it needs one.
 *
 * The token never appears in a log, a URL, an error message or a query key. Every function
 * here either returns it to the caller or puts it in a header.
 */

export const GUEST_STORAGE_KEY = "ms.guest";

/** Fired when a guest session turns out to be dead, so pages holding report ids can react. */
export const GUEST_SESSION_ENDED = "ms:guest-session-ended";

/** Renew this long before the stated expiry, so a call cannot land just after it. */
const RENEW_BEFORE_MS = 60_000;

export interface GuestSession {
  token: string;
  expiresAt: string;
}

function readStored(): GuestSession | null {
  if (typeof window === "undefined") return null;

  try {
    const raw = window.sessionStorage.getItem(GUEST_STORAGE_KEY);
    if (!raw) return null;

    const parsed: unknown = JSON.parse(raw);
    if (
      parsed &&
      typeof parsed === "object" &&
      typeof (parsed as GuestSession).token === "string" &&
      typeof (parsed as GuestSession).expiresAt === "string"
    ) {
      return parsed as GuestSession;
    }
  } catch {
    // Storage disabled, or something else wrote nonsense to the key. Either way there is no
    // session; the caller will create one.
  }

  return null;
}

function store(session: GuestSession): void {
  if (typeof window === "undefined") return;
  try {
    window.sessionStorage.setItem(GUEST_STORAGE_KEY, JSON.stringify(session));
  } catch {
    // Private mode with storage blocked. The session still works for this page's lifetime.
  }
}

export function clearGuestSession(): void {
  if (typeof window === "undefined") return;
  try {
    window.sessionStorage.removeItem(GUEST_STORAGE_KEY);
  } catch {
    // Nothing to do — there is no way to clear it and no way it can be read either.
  }
}

/** Returns the stored session without creating one. Used by `useGuestSession()`. */
export function peekGuestSession(): GuestSession | null {
  const session = readStored();
  return session && isUsable(session) ? session : null;
}

function isUsable(session: GuestSession): boolean {
  const expiry = Date.parse(session.expiresAt);
  if (Number.isNaN(expiry)) return false;
  return expiry - Date.now() > RENEW_BEFORE_MS;
}

/**
 * Shared in-flight creation.
 *
 * Without this, two components needing a session at the same moment would each POST
 * /api/sessions, burning two of the 20-per-hour allowance and leaving one orphaned session
 * owning nothing.
 */
let inFlight: Promise<GuestSession> | null = null;

type CreateSession = () => Promise<SessionResponse>;

/**
 * Returns a usable guest token, creating a session only if there is not one already.
 *
 * Lazy by design (contract 2.2, LLD 7): this is called from the request path of a guest
 * action — an upload, picking a sample, opening history — never from a page load or a
 * component mount. Browsing the landing page or the biomarker pages creates nothing.
 */
export async function ensureGuestSession(createSession: CreateSession): Promise<GuestSession> {
  const existing = readStored();
  if (existing && isUsable(existing)) return existing;

  inFlight ??= createSession()
    .then((response) => {
      const session: GuestSession = { token: response.token, expiresAt: response.expiresAt };
      store(session);
      return session;
    })
    .finally(() => {
      inFlight = null;
    });

  return inFlight;
}

/**
 * Handles a `401 SESSION_INVALID` on a guest call.
 *
 * The session is gone on the server, so everything the tab was holding under it is gone too:
 * the token, and every cached report. Publishing the event rather than navigating leaves the
 * decision to the page — a report page shows "Guest session ended", an upload just starts a
 * new session and tries once more.
 */
export function handleSessionInvalid(error: ApiError): void {
  clearGuestSession();

  if (typeof window !== "undefined") {
    // The event carries no token and no report id. Anything listening already knows which
    // page it is on.
    window.dispatchEvent(new CustomEvent(GUEST_SESSION_ENDED, { detail: { code: error.code } }));
  }
}

/** Tests only: drops the shared in-flight promise between cases. */
export function resetGuestSessionForTests(): void {
  inFlight = null;
}
