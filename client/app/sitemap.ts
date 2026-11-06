import type { MetadataRoute } from "next";

import { getAllBiomarkerPages } from "@/lib/biomarker-content";
import { SITE_URL } from "@/lib/env";

/**
 * The pages worth crawling.
 *
 * Report pages are not here and never will be: a report URL means nothing to anyone but its
 * owner, and a crawler following one would get a 404. They are excluded in `robots.ts` and
 * carry `noindex` of their own.
 *
 * With no site URL configured there is nothing honest to emit — a sitemap needs absolute
 * URLs, and guessing the host would publish a file pointing at somewhere this build is not.
 * So it returns empty and says so once.
 */

let warned = false;

export default function sitemap(): MetadataRoute.Sitemap {
  if (!SITE_URL) {
    // Once per server instance, not once per request: this is a note for whoever is
    // building, and repeating it would just be noise in the deploy log.
    if (!warned) {
      warned = true;
      console.warn(
        "[sitemap] NEXT_PUBLIC_SITE_URL is not set, so sitemap.xml is empty. " +
          "Set it before deploying if the site should be indexed.",
      );
    }
    return [];
  }

  const now = new Date();

  return [
    { url: `${SITE_URL}/`, lastModified: now, changeFrequency: "monthly", priority: 1 },
    { url: `${SITE_URL}/privacy`, lastModified: now, changeFrequency: "yearly", priority: 0.5 },
    { url: `${SITE_URL}/biomarkers`, lastModified: now, changeFrequency: "monthly", priority: 0.8 },
    ...getAllBiomarkerPages().map((page) => ({
      url: `${SITE_URL}/biomarkers/${page.slug}`,
      lastModified: now,
      changeFrequency: "yearly" as const,
      priority: 0.6,
    })),
  ];
}
