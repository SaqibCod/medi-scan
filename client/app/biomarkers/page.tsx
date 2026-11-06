import Link from "next/link";
import type { Metadata } from "next";

import { Eyebrow } from "@/components/ds";
import { Disclaimer } from "@/components/shell/disclaimer";
import { PageContainer } from "@/components/shell/page-container";
import { getAllBiomarkerPages } from "@/lib/biomarker-content";

/**
 * The index of curated pages.
 *
 * No mockup for this one, so it stays plain: grouped by panel, in the order `index.json`
 * gives, with each page's "measures" line as the blurb. Nothing invented.
 */
export const metadata: Metadata = {
  title: "Biomarkers",
  description:
    "Plain-language explanations of the lab tests Medi-Scan recognises: what each one measures, what a high or low result can mean, and what to ask your doctor.",
};

export default function BiomarkersIndexPage() {
  const pages = getAllBiomarkerPages();

  // Grouped in first-appearance order, which keeps the panels in the order index.json lists
  // them rather than alphabetising them into a different shape every time one is added.
  const panels: Array<{ panel: string; pages: typeof pages }> = [];
  for (const page of pages) {
    const existing = panels.find((group) => group.panel === page.panel);
    if (existing) existing.pages.push(page);
    else panels.push({ panel: page.panel, pages: [page] });
  }

  return (
    <PageContainer className="py-space-9">
      <Eyebrow>Biomarkers</Eyebrow>

      <h1 className="t-display-l mt-space-4 max-w-[14ch] text-ink">
        Lab tests,
        <br />
        <span className="text-faint">in plain language.</span>
      </h1>

      <p className="t-body-l mt-space-5 max-w-[56ch] text-ink-2">
        What each test measures, what a high or low result is commonly linked to, and what can
        move the number. These pages never print a reference range — the one on your own
        report is the one that applies.
      </p>

      <div className="mt-space-9 flex flex-col gap-space-9">
        {panels.map((group) => (
          <section key={group.panel} aria-labelledby={`panel-${group.panel.replace(/\s+/g, "-")}`}>
            <h2
              id={`panel-${group.panel.replace(/\s+/g, "-")}`}
              className="t-title border-b border-line pb-space-4 text-ink"
            >
              {group.panel}
            </h2>

            <ul className="mt-space-2">
              {group.pages.map((page) => (
                <li key={page.slug} className="border-b border-line">
                  <Link
                    href={`/biomarkers/${page.slug}`}
                    className="grid gap-space-2 py-space-5 md:grid-cols-[minmax(0,1fr)_minmax(0,1.6fr)] md:gap-space-6"
                  >
                    <span className="t-body text-ink">{page.title}</span>
                    <span className="t-body-s text-ink-2">{page.measures}</span>
                  </Link>
                </li>
              ))}
            </ul>
          </section>
        ))}
      </div>

      <Disclaimer className="mt-space-9" />
    </PageContainer>
  );
}
