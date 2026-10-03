import type { Metadata, Viewport } from "next";
import { Inter } from "next/font/google";

import "./globals.css";
import { HealthNotice } from "@/components/health-notice";
import { DisclaimerBanner } from "@/components/layout/disclaimer-banner";
import { SiteFooter } from "@/components/layout/site-footer";
import { SiteHeader } from "@/components/layout/site-header";
import QueryProvider from "@/components/providers/query-provider";
import { ThemeProvider } from "@/components/providers/theme-provider";
import { cn } from "@/lib/utils";

const inter = Inter({
  subsets: ["latin"],
  variable: "--font-sans",
  // The text is readable in a fallback face while Inter loads, rather than invisible.
  display: "swap",
});

export const metadata: Metadata = {
  title: {
    default: "Medi-Scan — Understand Your Lab Results",
    template: "%s — Medi-Scan",
  },
  description:
    "Upload a lab report and get your results explained in plain language, with each value checked against the reference range printed on your report.",
  applicationName: "Medi-Scan",
  openGraph: {
    title: "Medi-Scan — Understand Your Lab Results",
    description:
      "Lab reports explained in plain language. Built for synthetic data; no login required.",
    type: "website",
    siteName: "Medi-Scan",
  },
  // A demo on synthetic data has no business in search results for medical queries.
  robots: { index: false, follow: false },
};

export const viewport: Viewport = {
  // No maximumScale or userScalable: pinch-zoom must keep working, and small print on a
  // lab report is exactly when someone needs it.
  width: "device-width",
  initialScale: 1,
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "#ffffff" },
    { media: "(prefers-color-scheme: dark)", color: "#0a0a0a" },
  ],
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    // suppressHydrationWarning is required by next-themes, which sets the theme class on
    // <html> before React hydrates to avoid a flash of the wrong theme.
    <html lang="en" suppressHydrationWarning className={cn("h-full", inter.variable)}>
      <body className="flex min-h-full flex-col bg-background font-sans text-foreground antialiased">
        <QueryProvider>
          <ThemeProvider attribute="class" defaultTheme="system" enableSystem disableTransitionOnChange>
            {/*
              Skip link: the header has several links before the content, and a keyboard or
              screen-reader user should not have to walk past them on every page. Hidden
              until focused.
            */}
            <a
              href="#main"
              className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:z-50 focus:rounded-md focus:bg-background focus:px-3 focus:py-2 focus:text-sm focus:font-medium focus:ring-2 focus:ring-ring"
            >
              Skip to main content
            </a>

            <SiteHeader />
            <DisclaimerBanner />
            <HealthNotice />

            {/* scroll-margin-top keeps the sticky header off an anchored heading. */}
            <main id="main" tabIndex={-1} className="flex-1 scroll-mt-20 outline-none">
              {children}
            </main>

            <SiteFooter />
          </ThemeProvider>
        </QueryProvider>
      </body>
    </html>
  );
}
