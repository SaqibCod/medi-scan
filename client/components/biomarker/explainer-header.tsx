import Link from "next/link";

import { Eyebrow, Icon, Tag } from "@/components/ds";
import type { BiomarkerPage } from "@/lib/biomarker-content";

/** Breadcrumb, headline and the names a report might print instead. */
export function ExplainerHeader({ page }: { page: BiomarkerPage }) {
  return (
    <header>
      <nav aria-label="Breadcrumb">
        <ol className="t-body-s flex flex-wrap items-center gap-space-2 text-ink-3">
          <li>
            <Link href="/biomarkers" className="text-accent-ink underline underline-offset-4">
              Biomarkers
            </Link>
          </li>
          <li aria-hidden="true">/</li>
          <li aria-current="page">{page.title}</li>
        </ol>
      </nav>

      <div className="mt-space-6">
        <Eyebrow>{`Biomarker · ${page.panel}`}</Eyebrow>

        <h1 className="t-display-l mt-space-4 max-w-[16ch] text-ink">
          {page.title}
          <br />
          <span className="text-faint">in plain language.</span>
        </h1>

        {page.aliases.length > 0 && (
          <p className="mt-space-5 flex flex-wrap items-center gap-space-2">
            <span className="t-body-s mr-space-2 text-ink-3">Also written as</span>
            {page.aliases.map((alias) => (
              <Tag key={alias}>{alias}</Tag>
            ))}
          </p>
        )}
      </div>
    </header>
  );
}

/** Measures, usual units, and what it is usually ordered with. */
export function FactsGrid({ page }: { page: BiomarkerPage }) {
  const facts = [
    { label: "Measures", value: page.measures },
    { label: "Usual units", value: page.usualUnits },
    { label: "Usually part of", value: page.usuallyPartOf },
  ];

  return (
    <dl className="grid gap-space-4 md:grid-cols-3">
      {facts.map((fact) => (
        <div key={fact.label} className="rounded-md border border-line px-space-5 py-space-5">
          <dt className="t-meta text-ink-3">{fact.label.toUpperCase()}</dt>
          <dd className="t-body mt-space-3 text-ink">{fact.value}</dd>
        </div>
      ))}
    </dl>
  );
}

/** Generated from `related`, never written in the body, so it cannot drift from the data. */
export function RelatedTests({
  related,
  titles,
}: {
  related: string[];
  titles: Record<string, string>;
}) {
  if (related.length === 0) return null;

  return (
    <section aria-labelledby="related-tests">
      <h2 id="related-tests" className="t-title scroll-mt-24 text-ink">
        Related tests
      </h2>

      <ul className="mt-space-5 flex flex-wrap gap-space-3">
        {related.map((slug) => (
          <li key={slug}>
            <Link
              href={`/biomarkers/${slug}`}
              className="t-body-s inline-flex items-center gap-space-3 rounded-full border border-line px-space-5 py-space-3 text-ink hover:border-accent focus-visible:outline-2 focus-visible:outline-offset-[3px] focus-visible:outline-accent"
            >
              {titles[slug] ?? slug}
              {/* From the design-system set, not a literal arrow character. */}
              <Icon name="arrow-right" size={14} />
            </Link>
          </li>
        ))}
      </ul>
    </section>
  );
}
