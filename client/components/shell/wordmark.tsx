import Link from "next/link";

import { WORDMARK } from "./nav";

/**
 * The wordmark.
 *
 * There is no logo file — the brand is this text in Geist 600 with 0.14em tracking (design
 * system, "Iconography"). The middle dot is decorative, so it is hidden from screen readers
 * and the real name is read instead.
 */
export function Wordmark({ className }: { className?: string }) {
  return (
    <Link
      href="/"
      className={className}
      style={{ fontWeight: 600, letterSpacing: "0.14em", fontSize: 15 }}
    >
      <span aria-hidden="true">{WORDMARK}</span>
      <span className="sr-only">Medi-Scan, home</span>
    </Link>
  );
}
