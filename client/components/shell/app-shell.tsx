import type { ReactNode } from "react";

import { DailyLimitBanner, WakingBanner } from "@/components/notices/banners";
import { Footer } from "./footer";
import { Header } from "./header";

/**
 * Header, banners, content, footer — the frame every page sits in.
 *
 * The skip link is first in the tab order because the header has several links before the
 * content, and nobody navigating by keyboard should have to walk past them on every page.
 */
export function AppShell({ children }: { children: ReactNode }) {
  return (
    <div className="flex min-h-dvh flex-col">
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:absolute focus:left-space-4 focus:top-space-4 focus:z-50 focus:rounded-[var(--radius-pill)] focus:border focus:border-line-strong focus:bg-bg focus:px-space-5 focus:py-space-3 focus:text-ink"
      >
        Skip to content
      </a>

      <Header />

      <WakingBanner />
      <DailyLimitBanner />

      {/*
        `tabIndex={-1}` so the skip link can move focus here. It keeps a visible focus ring:
        suppressing it would land a keyboard user somewhere invisible, which defeats the point.
      */}
      <main id="main" tabIndex={-1} className="flex-1 focus-visible:outline-none">
        {children}
      </main>

      <Footer />
    </div>
  );
}
