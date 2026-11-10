import type { User } from "../api-types";

/**
 * What the browser knows about who is asking.
 *
 * Four states, not three: the contract's `RESOLVING | GUEST | SIGNED_IN` describes what pages
 * wait on, but sign-in itself takes a round trip, and during it the tab is neither a guest any
 * more nor signed in yet. Chat uses that window to pause asking (7c LLD A10) rather than send
 * a question under a credential that is about to be replaced.
 */
export type AuthState =
  | { status: "RESOLVING" }
  | { status: "GUEST" }
  | { status: "SIGNING_IN" }
  | { status: "SIGNED_IN"; user: User; accessExpiresAt: number };

export type AuthStatus = AuthState["status"];

/**
 * How long a page waits before rendering as a guest (contract 2.1).
 *
 * Two seconds is the contract's budget, and it is spent on the refresh round trip or on
 * Google's auto sign-in. It is a ceiling, not a delay: resolving sooner renders sooner.
 */
export const RESOLVE_BUDGET_MS = 2_000;

/** Refresh this long before the access token expires, rather than waiting for a 401. */
export const REFRESH_LEAD_MS = 60_000;

export const RESOLVING: AuthState = { status: "RESOLVING" };
export const GUEST: AuthState = { status: "GUEST" };
export const SIGNING_IN: AuthState = { status: "SIGNING_IN" };

export function isSignedIn(state: AuthState): state is Extract<AuthState, { status: "SIGNED_IN" }> {
  return state.status === "SIGNED_IN";
}

/** Pages hold their queries while this is true, up to {@link RESOLVE_BUDGET_MS}. */
export function isResolving(state: AuthState): boolean {
  return state.status === "RESOLVING" || state.status === "SIGNING_IN";
}

/** The circle in the header pill. Google display names can start with anything, including an emoji. */
export function initialFor(displayName: string): string {
  return [...displayName.trim()][0]?.toUpperCase() ?? "?";
}
