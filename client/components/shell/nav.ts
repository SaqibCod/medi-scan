import { FEATURE_SIGNIN } from "@/lib/env";

/**
 * The header and footer links, in one place so the desktop bar and the phone menu can never
 * drift apart.
 *
 * The anchor targets are real from 7b on. "My reports" is not: `/reports` is the history
 * list, and it arrives with accounts in 7c. 7a left the link in place on the grounds that a
 * 404 is honest, but a nav link that always 404s is a dead end rather than an answer, so it
 * now waits behind the same flag as every other signed-in action (7b LLD B10).
 */
export interface NavLink {
  href: string;
  label: string;
}

export const NAV_LINKS: NavLink[] = [
  { href: "/#how", label: "How it works" },
  { href: "/#samples", label: "Samples" },
  { href: "/privacy", label: "Privacy" },
  // Not gated on being signed in: a guest has reports too, and they are listed on the same
  // page (7c LLD A6). Only the flag decides, because before 7c there was nowhere to go.
  ...(FEATURE_SIGNIN ? [{ href: "/reports", label: "My reports" }] : []),
];

/**
 * The header and phone menu, which get one more link than the footer.
 *
 * Trends needs a bearer token — the endpoint answers `401 AUTH_REQUIRED` to a guest — so the
 * link only appears once there is one. `/trends` still answers a direct URL from a guest, with
 * the sign-in card; what it must not do is advertise itself to someone it would turn away.
 *
 * The 7c mockups drop Privacy from the header to make room, but the landing page keeps it and
 * one header serves both. Adding rather than swapping leaves the shipped page untouched, and
 * a privacy link one click away in a health app is worth the extra slot.
 */
export function headerLinks(signedIn: boolean): NavLink[] {
  return signedIn ? [...NAV_LINKS, { href: "/trends", label: "Trends" }] : NAV_LINKS;
}

/** Word for word, on every page that shows results (root CLAUDE.md). */
export const DISCLAIMER = "Medi-Scan explains what your report says and is not medical advice.";

export const WORDMARK = "MEDI·SCAN";
