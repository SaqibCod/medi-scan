import type { Metadata } from "next";
import { notFound } from "next/navigation";

import { Gallery } from "@/components/dev/gallery";

export const metadata: Metadata = {
  title: "Components",
  robots: { index: false, follow: false },
};

/**
 * The design-system gallery (LLD F11).
 *
 * A 404 in production: it is a development tool, not a page, and shipping it would put every
 * component and a few hundred helix particles in the production bundle for no one. Checked at
 * request time rather than by a build-time export, so the route simply does not exist there.
 */
export default function ComponentsGalleryPage() {
  if (process.env.NODE_ENV === "production") notFound();

  return <Gallery />;
}
