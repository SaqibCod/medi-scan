import type { MetadataRoute } from "next";

import { SITE_URL } from "@/lib/env";

/**
 * What crawlers may look at.
 *
 * `/reports/` is excluded because a report belongs to one person and is useless to anyone
 * else — a crawler would get a 404 and the URL would sit in an index for no reason. `/dev/`
 * is the component gallery, which only exists outside production.
 *
 * This is a courtesy, not a control. Ownership is enforced on the server.
 */
export default function robots(): MetadataRoute.Robots {
  return {
    rules: {
      userAgent: "*",
      allow: "/",
      disallow: ["/reports/", "/dev/"],
    },
    ...(SITE_URL ? { sitemap: `${SITE_URL}/sitemap.xml` } : {}),
  };
}
