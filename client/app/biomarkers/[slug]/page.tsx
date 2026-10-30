import { Suspense } from "react";
import { notFound } from "next/navigation";
import type { Metadata } from "next";

import { Disclaimer } from "@/components/shell/disclaimer";
import { PageContainer } from "@/components/shell/page-container";
import { SectionIndex } from "@/components/content/section-index";
import {
  ExplainerHeader,
  FactsGrid,
  RelatedTests,
} from "@/components/biomarker/explainer-header";
import { MarkdownBody } from "@/components/biomarker/markdown-body";
import { OnYourReportBox } from "@/components/biomarker/on-your-report-box";
import { getAllBiomarkerPages, getBiomarkerPage } from "@/lib/biomarker-content";

/**
 * A curated biomarker explainer.
 *
 * Server-rendered from markdown, read once per server instance (B1). No
 * `generateStaticParams`: the nonce CSP makes every page render per request anyway, so a
 * build-time render would be thrown away.
 */

export async function generateMetadata({
  params,
}: {
  params: Promise<{ slug: string }>;
}): Promise<Metadata> {
  const { slug } = await params;
  const page = getBiomarkerPage(slug);

  if (!page) return { title: "Not found" };

  return {
    title: page.title,
    description: `${page.measures}. What a high or low result can mean, what can affect it, and what to ask your doctor.`,
    openGraph: {
      type: "article",
      siteName: "Medi-Scan",
      title: `${page.title} · Medi-Scan`,
      description: page.measures,
    },
  };
}

export default async function BiomarkerPage({ params }: { params: Promise<{ slug: string }> }) {
  const { slug } = await params;
  const page = getBiomarkerPage(slug);

  // An unknown slug is the 404 screen, not an empty explainer.
  if (!page) notFound();

  const titles = Object.fromEntries(getAllBiomarkerPages().map((p) => [p.slug, p.title]));

  const sections = [
    ...page.headings,
    { id: "related-tests", title: "Related tests" },
  ];

  return (
    <PageContainer className="py-space-8">
      <div className="grid gap-space-8 lg:grid-cols-[minmax(0,1.5fr)_minmax(0,1fr)] lg:gap-space-9">
        <ExplainerHeader page={page} />

        {/*
          Client-only and entirely optional. `useSearchParams` needs a Suspense boundary, and
          the fallback is nothing at all — the page reads perfectly well without this box.
        */}
        <div className="lg:pt-space-9">
          <Suspense fallback={null}>
            <OnYourReportBox slug={page.slug} />
          </Suspense>
        </div>
      </div>

      <div className="mt-space-8">
        <FactsGrid page={page} />
      </div>

      <div className="mt-space-9 grid gap-space-8 lg:grid-cols-[minmax(0,220px)_minmax(0,1fr)] lg:gap-space-9">
        <SectionIndex sections={sections} />

        <div className="min-w-0">
          <MarkdownBody markdown={page.body} />

          <div className="mt-space-9">
            <RelatedTests related={page.related} titles={titles} />
          </div>

          <footer className="mt-space-9">
            <Disclaimer
              className="rounded-md bg-accent-soft px-space-5 py-space-4"
              extra="It can't tell you what your result means for you."
            />

            <p className="t-meta mt-space-5 text-ink-3">
              Sources:{" "}
              {page.sources.map((source, i) => (
                <span key={source.url}>
                  {i > 0 && " · "}
                  <a
                    href={source.url}
                    target="_blank"
                    rel="noreferrer noopener"
                    className="text-accent-ink underline underline-offset-4"
                  >
                    {source.title}
                  </a>
                </span>
              ))}
            </p>

            <p className="t-meta mt-space-2 text-ink-3">
              {page.reviewedOn && page.reviewer
                ? `Last reviewed ${page.reviewedOn} by ${page.reviewer}`
                : "Not yet reviewed by a person."}
            </p>
          </footer>
        </div>
      </div>
    </PageContainer>
  );
}
