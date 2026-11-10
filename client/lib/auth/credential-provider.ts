import { setCredentialProvider, type CredentialProvider } from "../credentials";
import { refreshedRecently, refreshSession } from "./refresh";
import { getAccessToken, hasAccessTokenExpired } from "./tokens";

/**
 * The real `CredentialProvider` the 7a API client has been calling into all along.
 *
 * Deliberately not a React hook and not inside the provider component. Child effects run
 * before parent effects, so a page's first query would fire before a provider registered in
 * `useEffect` had been installed, and that one request would go out as a guest. This is plain
 * module code over plain module state, installed on import.
 */

const authCredentials: CredentialProvider = {
  async authorization() {
    const token = getAccessToken();
    if (!token) return null;

    // Already past its expiry: spending a round trip on a guaranteed 401 helps nobody. The
    // refresh is the same single-flight one the failure path uses, so a burst of calls here
    // still produces one rotation.
    if (hasAccessTokenExpired()) {
      try {
        await refreshSession();
      } catch {
        // Network trouble. Fall through and send what we have — the server is the judge of
        // whether it is still good, and being wrong costs one 401, not a sign-out.
      }
      const refreshed = getAccessToken();
      return refreshed ? `Bearer ${refreshed}` : null;
    }

    return `Bearer ${token}`;
  },

  /**
   * Called once per failed call by `lib/api.ts`, which then retries at most once. Never loops:
   * the decision to retry is made here, the retry is made there, and neither asks twice.
   */
  async onAuthFailure() {
    // A refresh that finished moments ago already answered this 401 — it just landed a tick
    // too late to join the in-flight promise. Rotating again would be pointless and would
    // spend another token.
    if (refreshedRecently() && getAccessToken()) return "retry";

    try {
      const session = await refreshSession();
      return session ? "retry" : "fail";
    } catch {
      // The refresh itself could not be delivered. The original error stands.
      return "fail";
    }
  },
};

export function installAuthCredentials(): void {
  setCredentialProvider(authCredentials);
}

/** Tests only, for asserting the two decisions directly. */
export { authCredentials };
