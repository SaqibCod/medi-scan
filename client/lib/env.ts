/**
 * Public environment variables, validated once at import.
 *
 * `NEXT_PUBLIC_*` values are inlined at build time, so a missing or malformed one is a broken
 * build rather than a runtime surprise — provided something actually looks at it, which is
 * what this file is for.
 */

function readApiUrl(): string {
  const raw = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    throw new Error(`NEXT_PUBLIC_API_URL is not a valid URL: ${raw}`);
  }

  // Plain http in production would send the session token in the clear, and the CSP's
  // `upgrade-insecure-requests` would rewrite the request anyway — failing loudly beats
  // silently depending on that.
  if (process.env.NODE_ENV === "production" && url.protocol !== "https:") {
    throw new Error(`NEXT_PUBLIC_API_URL must use https in production, got: ${url.protocol}//`);
  }

  // Stored without a trailing slash so paths can be joined as `${API_URL}${path}`.
  return url.origin + url.pathname.replace(/\/$/, "");
}

export const API_URL = readApiUrl();

/**
 * The GitHub link in the footer. Optional — the link is hidden when it is unset, rather than
 * rendering a link to nowhere (LLD 9.1).
 */
export const REPO_URL = process.env.NEXT_PUBLIC_REPO_URL ?? null;

/**
 * Sign-in ships in 7c. Until then the header keeps the slot but shows no button, because a
 * button that does nothing is worse than no button (LLD F8).
 */
export const FEATURE_SIGNIN = process.env.NEXT_PUBLIC_FEATURE_SIGNIN === "true";
