import Link from "next/link";

import { REPO_URL } from "@/lib/env";
import { PageContainer } from "./page-container";

/**
 * The footer.
 *
 * The left-hand line is the short form of the disclaimer, which belongs on every page rather
 * than only on the ones showing results.
 */
export function Footer() {
  return (
    <footer className="mt-auto border-t border-line py-space-6">
      <PageContainer>
        <div className="flex flex-col gap-space-3 sm:flex-row sm:items-center sm:justify-between">
          <p className="t-caption text-ink-3">
            <span aria-hidden="true">MEDI·SCAN</span>
            <span className="sr-only">Medi-Scan</span> — explains your report, not medical advice.
          </p>

          <nav aria-label="Footer">
            <ul className="flex items-center gap-space-5">
              <li>
                <Link href="/privacy" className="t-caption text-ink-2 underline underline-offset-4">
                  Privacy
                </Link>
              </li>
              {/* Hidden rather than pointing nowhere when the repo URL is not configured. */}
              {REPO_URL && (
                <li>
                  <a
                    href={REPO_URL}
                    rel="noopener noreferrer"
                    className="t-caption text-ink-2 underline underline-offset-4"
                  >
                    GitHub
                  </a>
                </li>
              )}
            </ul>
          </nav>
        </div>
      </PageContainer>
    </footer>
  );
}
