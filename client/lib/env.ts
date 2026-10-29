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

  // Plain http to a real host in production would send the session token in the clear, and
  // the CSP's `upgrade-insecure-requests` would rewrite the request anyway — failing loudly
  // beats silently depending on that.
  //
  // Loopback is exempt: a production build run locally is how the e2e suite and any
  // pre-deploy smoke check work, and nothing leaves the machine.
  const isLoopback = ["localhost", "127.0.0.1", "[::1]", "::1"].includes(url.hostname);

  if (process.env.NODE_ENV === "production" && url.protocol !== "https:" && !isLoopback) {
    throw new Error(
      `NEXT_PUBLIC_API_URL must use https in production, got: ${url.protocol}//${url.hostname}`,
    );
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
 * Where this build is served from, for the sitemap and robots.
 *
 * Optional, and absent in development. Without it the sitemap is empty rather than full of
 * guessed URLs — a sitemap of relative paths is invalid, and a wrong host is worse than no
 * sitemap at all. Stored without a trailing slash so paths join cleanly.
 */
export const SITE_URL = process.env.NEXT_PUBLIC_SITE_URL
  ? process.env.NEXT_PUBLIC_SITE_URL.replace(/\/$/, "")
  : null;

/**
 * Sign-in ships in 7c. Until then the header keeps the slot but shows no button, because a
 * button that does nothing is worse than no button (LLD F8).
 *
 * It also hides "My reports", which has nowhere to go until the history list exists (7b LLD
 * B10).
 */
export const FEATURE_SIGNIN = process.env.NEXT_PUBLIC_FEATURE_SIGNIN === "true";

/**
 * OCR for photos and scanned PDFs ships in Phase 4.
 *
 * Until then the backend answers `415 UNSUPPORTED_FILE_TYPE` for PNG and JPEG, so no copy
 * anywhere may offer photos. Every string that would is in `lib/copy.ts` with both versions,
 * switched by this one flag (7b LLD B4).
 */
export const FEATURE_OCR = process.env.NEXT_PUBLIC_FEATURE_OCR === "true";
