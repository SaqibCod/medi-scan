import { api } from "../api";
import type { GoogleSignInResponse, Me, TokenResponse } from "../api-types";

/**
 * The five auth endpoints, as plain functions (contract 2.3 to 2.7).
 *
 * All of them go through `lib/api.ts` like everything else, so they inherit the timeout, the
 * Problem Details parsing and the request-id handling. The three token endpoints use
 * `auth: "none"`: the credential they carry is in the body, and attaching a bearer token or a
 * guest header on top would mean a dead access token could make a refresh fail.
 */

export function exchangeGoogleToken(
  idToken: string,
  guestSessionToken: string | null,
): Promise<GoogleSignInResponse> {
  return api.post<GoogleSignInResponse>(
    "/api/auth/google",
    // Omitted rather than sent as null when there is no guest session: the contract has it
    // optional, and `claimedReportCount` comes back 0 either way.
    guestSessionToken ? { idToken, guestSessionToken } : { idToken },
    { auth: "none" },
  );
}

export function refreshTokens(refreshToken: string): Promise<TokenResponse> {
  return api.post<TokenResponse>("/api/auth/refresh", { refreshToken }, { auth: "none" });
}

/**
 * Revokes a refresh token's family.
 *
 * The contract answers 204 even for a token it has never seen, so this cannot fail in a way
 * the user needs to hear about — which is why sign-out never waits on the result.
 */
export function revokeRefreshToken(refreshToken: string): Promise<void> {
  return api.post<void>("/api/auth/logout", { refreshToken }, { auth: "none" });
}

export function fetchMe(signal?: AbortSignal): Promise<Me> {
  return api.get<Me>("/api/me", { auth: "auto", signal });
}

export function deleteMe(): Promise<void> {
  return api.delete<void>("/api/me", { auth: "auto" });
}
