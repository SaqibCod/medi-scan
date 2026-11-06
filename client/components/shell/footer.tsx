import Link from "next/link";

import { REPO_URL } from "@/lib/env";
import { NAV_LINKS } from "./nav";
import { PageContainer } from "./page-container";

/**
 * The footer, with the giant faded wordmark from the mockups.
 *
 * The wordmark is the second of the two gradients the design system allows (README,
 * "Colour"): the text is clipped to a `foot-top` → `bg` fade, so it reads as the page
 * dissolving rather than as a block of colour. `--foot-top` exists as a token for exactly
 * this and had no other user.
 *
 * It is `aria-hidden`: it is the brand set as scenery, and a screen reader announcing
 * "MEDI·SCAN" again at the end of every page is noise. The real name is in the paragraph
 * above it and in the header.
 */
export function Footer() {
  return (
    <footer className="mt-auto overflow-hidden border-t border-line">
      <PageContainer className="pt-space-7">
        <div className="flex flex-wrap items-start justify-between gap-x-space-9 gap-y-space-5">
          <p className="t-caption max-w-[62ch] text-ink-2">
            Medi-Scan is a portfolio project. It explains what your report says and is not
            medical advice. Always talk to a doctor about your results.
          </p>

          <nav aria-label="Footer">
            <ul className="flex flex-wrap items-center gap-space-5">
              {NAV_LINKS.map((link) => (
                <li key={link.href}>
                  <Link href={link.href} className="t-caption text-ink-2 hover:text-ink">
                    {link.label}
                  </Link>
                </li>
              ))}
              {/* Hidden rather than pointing nowhere when the repo URL is not configured. */}
              {REPO_URL && (
                <li>
                  <a
                    href={REPO_URL}
                    rel="noopener noreferrer"
                    className="t-caption text-ink-2 hover:text-ink"
                  >
                    GitHub
                  </a>
                </li>
              )}
            </ul>
          </nav>
        </div>
      </PageContainer>

      <div
        aria-hidden="true"
        className="select-none bg-clip-text pt-space-7 text-center font-medium text-transparent"
        style={{
          // Sized in vw so the wordmark spans the page at any width, as in the mockup. The
          // fade is the two tokens the design system names for it — no literal colour here.
          fontSize: "15.5vw",
          lineHeight: 0.8,
          letterSpacing: "-0.06em",
          marginBottom: "-2vw",
          backgroundImage: "linear-gradient(180deg, var(--foot-top) 0%, var(--bg) 95%)",
          WebkitBackgroundClip: "text",
        }}
      >
        MEDI·SCAN
      </div>
    </footer>
  );
}
