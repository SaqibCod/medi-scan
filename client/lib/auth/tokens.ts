import type { TokenResponse } from "../api-types";

/**
 * Where the two tokens live, and the only module allowed to touch either.
 *
 * <strong>The access token is a module variable.</strong> Not React state, not storage, not a
 * ref on a component — a variable in this file's closure. Nothing renders it, nothing
 * serialises it, and it is gone when the tab closes or reloads. That is the whole point: a
 * token in storage is a token an injected script can read at its leisure, and the refresh
 * token below already pays that price once.
 *
 * <strong>The refresh token is in `sessionStorage`.</strong> It has to survive a reload —
 * otherwise every refresh of the page is a sign-out — and `sessionStorage` is per tab, which
 * is also how the server models families (dataflow 3.3). It never moves to `localStorage`,
 * never becomes a cookie, and never crosses a `BroadcastChannel`.
 *
 * Neither token is ever logged, put in a URL, attached to an error, or used as a query key.
 */

export const REFRESH_STORAGE_KEY = "ms.refresh";

let accessToken: string | null = null;
let accessExpiresAt = 0;

export function getAccessToken(): string | null {
  return accessToken;
}

/** Epoch milliseconds, or 0 when there is no token. Safe to render; it is not a credential. */
export function getAccessExpiresAt(): number {
  return accessExpiresAt;
}

export function hasAccessTokenExpired(now: number = Date.now()): boolean {
  return accessToken !== null && accessExpiresAt > 0 && accessExpiresAt <= now;
}

function parseExpiry(iso: string): number {
  const at = Date.parse(iso);
  // An unparseable expiry must not read as "expired long ago", which would refresh on every
  // single call. Treating it as unknown means the token is used until the server rejects it.
  return Number.isNaN(at) ? 0 : at;
}

/**
 * Stores both halves of a token response.
 *
 * One function so the two can never drift apart: every path that produces tokens — sign-in and
 * refresh — hands the whole response here, and the refresh token is replaced in the same
 * breath as the access token it was swapped for.
 */
export function storeTokens(response: TokenResponse): number {
  accessToken = response.accessToken;
  accessExpiresAt = parseExpiry(response.accessTokenExpiresAt);
  writeRefreshToken(response.refreshToken);
  return accessExpiresAt;
}

export function readRefreshToken(): string | null {
  if (typeof window === "undefined") return null;
  try {
    return window.sessionStorage.getItem(REFRESH_STORAGE_KEY);
  } catch {
    // Storage blocked. There is no refresh token, so the tab is a guest until someone signs in.
    return null;
  }
}

export function writeRefreshToken(token: string): void {
  if (typeof window === "undefined") return;
  try {
    window.sessionStorage.setItem(REFRESH_STORAGE_KEY, token);
  } catch {
    // Private mode with storage blocked: sign-in still works for this page's lifetime, and a
    // reload signs the visitor out. Better than refusing to sign them in at all.
  }
}

/**
 * Drops the refresh token and nothing else.
 *
 * Used by the duplicated-tab guard, which has to take the inherited token away without
 * disturbing anything else the tab holds — `ms.guest` in particular stays.
 */
export function clearRefreshToken(): void {
  if (typeof window === "undefined") return;
  try {
    window.sessionStorage.removeItem(REFRESH_STORAGE_KEY);
  } catch {
    // Nothing was stored in the first place.
  }
}

export function clearAccessToken(): void {
  accessToken = null;
  accessExpiresAt = 0;
}

/** Signing out, or a refresh token the server has disowned. */
export function clearTokens(): void {
  clearAccessToken();
  clearRefreshToken();
}

/** Tests only. */
export function resetTokensForTests(): void {
  clearAccessToken();
}
