import { Suspense } from "react";
import type { Metadata } from "next";

import { PageContainer } from "@/components/shell/page-container";
import { TrendsView } from "@/components/trends/trends-view";

/**
 * Trends.
 *
 * `noindex`, like every page that belongs to one person. The Suspense boundary is required:
 * `TrendsView` reads the chosen marker from `useSearchParams`, and a client component that
 * does so must sit inside one or the production build fails.
 */
export const metadata: Metadata = {
  title: "Your trends",
  robots: { index: false, follow: false },
};

export default function TrendsPage() {
  return (
    <PageContainer>
      <Suspense fallback={null}>
        <TrendsView />
      </Suspense>
    </PageContainer>
  );
}
