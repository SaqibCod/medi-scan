import Link from "next/link";

const RETENTION_NOTE = "Guest reports are deleted after 24 hours.";

export function SiteFooter() {
  return (
    <footer className="mt-auto border-t border-border bg-muted/30">
      <div className="mx-auto flex max-w-5xl flex-col gap-4 px-4 py-8 sm:flex-row sm:items-start sm:justify-between sm:px-6">
        <div className="min-w-0 space-y-1.5">
          <p className="text-sm font-medium" translate="no">
            Medi&#8209;Scan
          </p>
          <p className="max-w-prose text-pretty text-xs leading-relaxed text-muted-foreground">
            A portfolio demo built for synthetic data. Please don&rsquo;t upload a real medical
            report. {RETENTION_NOTE}
          </p>
        </div>

        <nav aria-label="Footer" className="shrink-0">
          <ul className="flex flex-wrap gap-x-5 gap-y-2 text-xs sm:justify-end">
            <li>
              <Link
                href="/biomarkers"
                className="rounded-sm text-muted-foreground underline-offset-4 transition-colors hover:text-foreground hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
              >
                Biomarkers
              </Link>
            </li>
            <li>
              <a
                href="https://github.com/SaqibCod/medi-scan/blob/main/SECURITY.md"
                className="rounded-sm text-muted-foreground underline-offset-4 transition-colors hover:text-foreground hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
              >
                Privacy &amp; Security
              </a>
            </li>
            <li>
              <a
                href="https://github.com/SaqibCod/medi-scan"
                className="rounded-sm text-muted-foreground underline-offset-4 transition-colors hover:text-foreground hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
              >
                Source
              </a>
            </li>
          </ul>
        </nav>
      </div>
    </footer>
  );
}
