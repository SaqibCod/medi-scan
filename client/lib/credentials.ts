import type { ApiError } from "./api-error";

/**
 * How the API client gets a bearer token, and what to do when one is rejected.
 *
 * 7a ships the guest-only implementation below. 7c replaces it with one that holds the
 * in-memory access token and performs the single-flight refresh — the API client does not
 * change when that happens, which is the point of the seam.
 */
export interface CredentialProvider {
  /** `"Bearer …"` when signed in, otherwise `null`. */
  authorization(): Promise<string | null>;

  /**
   * Called once when a bearer call fails with `TOKEN_EXPIRED` or `TOKEN_INVALID`.
   * `"retry"` means a new token is now available and the call should be repeated exactly
   * once. `"fail"` means give up and surface the error.
   */
  onAuthFailure(error: ApiError): Promise<"retry" | "fail">;
}

/**
 * The 7a provider: nobody is signed in, so there is never a bearer token and never anything
 * to refresh.
 */
export const guestOnlyCredentials: CredentialProvider = {
  async authorization() {
    return null;
  },
  async onAuthFailure() {
    return "fail";
  },
};

let provider: CredentialProvider = guestOnlyCredentials;

export function getCredentialProvider(): CredentialProvider {
  return provider;
}

/** 7c calls this once, at startup, with the real provider. */
export function setCredentialProvider(next: CredentialProvider): void {
  provider = next;
}

/** Tests only: puts the module back to its 7a default. */
export function resetCredentialProvider(): void {
  provider = guestOnlyCredentials;
}
