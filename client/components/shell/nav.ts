/**
 * The header and footer links, in one place so the desktop bar and the phone menu can never
 * drift apart.
 *
 * The anchor targets land with the landing page in 7b, and `/reports` with accounts in 7c.
 * Until then those links resolve to the 404 screen, which is honest — and better than a link
 * that silently does nothing (LLD F8).
 */
export interface NavLink {
  href: string;
  label: string;
}

export const NAV_LINKS: NavLink[] = [
  { href: "/#how", label: "How it works" },
  { href: "/#samples", label: "Samples" },
  { href: "/privacy", label: "Privacy" },
  { href: "/reports", label: "My reports" },
];

/** Word for word, on every page that shows results (root CLAUDE.md). */
export const DISCLAIMER = "Medi-Scan explains what your report says and is not medical advice.";

export const WORDMARK = "MEDI·SCAN";
