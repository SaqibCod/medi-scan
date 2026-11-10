import { ApiError } from "../api-error";
import type { User } from "../api-types";
import { refreshTokens } from "./api";
import { clearTokens, readRefreshToken, storeTokens } from "./tokens";

/**
 * Swapping a refresh token for a new access token, once at a time.
 *
 * <strong>Single flight is not an optimisation here, it is the whole correctness story.</strong>
 * Refresh tokens rotate: two requests sent with the same token mean the second one presents a
 * token the server has already revoked, which is indistinguishable from a stolen copy and
 * revokes the entire family. A page that fires six queries at once and gets six 401s back
 * would, without this, sign itself out. So every caller shares one promise.
 */

export interface RefreshedSession {
  user: User;
  accessExpiresAt: number;
}

/** Why a tab stopped being signed in, for the UI to phrase it. */
export type SignedOutReason = "REFRESH_REJECTED";

type SignedOutListener = (reason: SignedOutReason) => void;

const signedOutListeners = new Set<SignedOutListener>();

/**
 * Fires when a refresh is rejected outright and the tab is now a guest.
 *
 * A listener rather than a return value because the tab can discover this at any moment —
 * during a page's query, or on a proactive timer with nobody waiting — and the UI has to react
 * either way.
 */
export function onSignedOut(listener: SignedOutListener): () => void {
  signedOutListeners.add(listener);
  return () => signedOutListeners.delete(listener);
}

let inFlight: Promise<RefreshedSession | null> | null = null;
let lastSuccessAt = 0;

/**
 * How long a just-completed refresh counts as having answered a 401.
 *
 * Five calls failing together share the in-flight promise, but a sixth whose 401 lands a tick
 * after the refresh resolved would otherwise start a second, pointless rotation. Inside this
 * window it simply retries with the token that is already in memory.
 */
const FRESH_ENOUGH_MS = 2_000;

export function refreshedRecently(now: number = Date.now()): boolean {
  return lastSuccessAt > 0 && now - lastSuccessAt < FRESH_ENOUGH_MS;
}

async function performRefresh(token: string): Promise<RefreshedSession | null> {
  try {
    const response = await refreshTokens(token);
    const accessExpiresAt = storeTokens(response);
    lastSuccessAt = Date.now();
    return { user: response.user, accessExpiresAt };
  } catch (error) {
    // The server has disowned this token — unknown, expired, revoked, or reused. There is
    // nothing to retry with, so the tab is a guest from here.
    if (error instanceof ApiError && error.code === "REFRESH_TOKEN_INVALID") {
      clearTokens();
      signedOutListeners.forEach((listener) => listener("REFRESH_REJECTED"));
      return null;
    }

    // Anything else — a dropped connection, a 5xx, a backend still waking — says nothing about
    // whether the token is good. It is kept, and the error goes to the caller to decide.
    // Treating a network blip as a sign-out is the one failure mode this must never have.
    throw error;
  } finally {
    inFlight = null;
  }
}

/**
 * Refreshes, or joins the refresh already running.
 *
 * Resolves to `null` when there was no refresh token to begin with, or when the server
 * rejected the one there was. Throws only for failures that might clear up on their own.
 */
export function refreshSession(): Promise<RefreshedSession | null> {
  if (inFlight) return inFlight;

  const token = readRefreshToken();
  if (!token) return Promise.resolve(null);

  inFlight = performRefresh(token);
  return inFlight;
}

export function isRefreshing(): boolean {
  return inFlight !== null;
}

/** Tests only. */
export function resetRefreshForTests(): void {
  inFlight = null;
  lastSuccessAt = 0;
  signedOutListeners.clear();
}
