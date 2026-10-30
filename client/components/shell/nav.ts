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
  ...(FEATURE_SIGNIN ? [{ href: "/reports", label: "My reports" }] : []),
];

/** Word for word, on every page that shows results (root CLAUDE.md). */
export const DISCLAIMER = "Medi-Scan explains what your report says and is not medical advice.";

export const WORDMARK = "MEDI·SCAN";
