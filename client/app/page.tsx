import type { Metadata } from "next";

import { Marquee } from "@/components/ds";
import { FactsRow } from "@/components/landing/facts-row";
import { Hero } from "@/components/landing/hero";
import { HowItWorks } from "@/components/landing/how-it-works";
import { PrivacySection } from "@/components/landing/privacy-section";
import { ResultsPreview } from "@/components/landing/results-preview";
import { SamplesSection } from "@/components/landing/samples-section";
import { StickyAnalyzeBar } from "@/components/landing/sticky-analyze-bar";
import { UploadPanel } from "@/components/landing/upload-panel";
import { PageContainer } from "@/components/shell/page-container";
import { UploadSourceProvider } from "@/components/upload/upload-source-context";

/**
 * The landing page.
 *
 * Text-only Open Graph tags: an image would be another asset to host and another thing to
 * keep true, and the page has no screenshot worth sharing that is not of invented data.
 */
export const metadata: Metadata = {
  // The layout's template appends "· Medi-Scan"; the home page states it in full instead.
  title: { absolute: "Medi-Scan · Your lab report in plain language" },
  description:
    "Upload a blood test or lab PDF and read it in plain language. Every value is checked against the reference range printed on your report. No account needed.",
  openGraph: {
    type: "website",
    siteName: "Medi-Scan",
    title: "Medi-Scan · Your lab report in plain language",
    description:
      "Upload a blood test or lab PDF and read it in plain language. No account needed, and guest reports are deleted after 24 hours.",
  },
};

/**
 * The marquee's four phrases (decorative, `aria-hidden`).
 *
 * Each one is also real text elsewhere on the page: "plain language" in the hero headline,
 * "private by default" and "every value flagged" in the privacy section's lead, "no account
 * needed" in the upload panel.
 */
const MARQUEE = [
  "PLAIN LANGUAGE",
  "PRIVATE BY DEFAULT",
  "EVERY VALUE FLAGGED",
  "NO ACCOUNT NEEDED",
];

/**
 * No `<main>` here: the shell already provides the one the skip link targets, and a second
 * would give the page two main landmarks.
 */
export default function HomePage() {
  return (
    // One provider for the whole page, because the sample cards, the upload panel and the
    // phone bar are all three about the same single choice.
    <UploadSourceProvider>
      <PageContainer>
        <Hero />
        <UploadPanel />
        <FactsRow />
        <HowItWorks />
        <SamplesSection />
        <ResultsPreview />
      </PageContainer>

      {/* Full-bleed, so it runs off both edges the way the mockup shows. */}
      <div className="py-space-8">
        <Marquee items={MARQUEE} />
      </div>

      <PageContainer>
        <PrivacySection />
      </PageContainer>

      <StickyAnalyzeBar />
    </UploadSourceProvider>
  );
}
