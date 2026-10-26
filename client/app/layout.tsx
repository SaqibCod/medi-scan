import type { Metadata, Viewport } from "next";
import { headers } from "next/headers";

import "./styles/globals.css";
import { AppShell } from "@/components/shell/app-shell";
import { geistMono, geistSans } from "./fonts";
import { Providers } from "./providers";
import { themeColors } from "./styles/theme-colors";

export const metadata: Metadata = {
  title: {
    default: "Medi-Scan — your lab report, in plain language",
    template: "%s · Medi-Scan",
  },
  description:
    "Medi-Scan reads a lab report and explains what it says in plain language, checking every value against the reference range printed on your report. No account needed.",
  applicationName: "Medi-Scan",
  // A demo built for synthetic data has no business ranking for medical queries.
  robots: { index: false, follow: false },
};

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  // No maximumScale and no userScalable: pinch-zoom has to keep working, and small print on a
  // lab report is exactly when someone needs it.
  themeColor: [
    { media: "(prefers-color-scheme: dark)", color: themeColors.dark },
    { media: "(prefers-color-scheme: light)", color: themeColors.light },
  ],
};

export default async function RootLayout({ children }: LayoutProps<"/">) {
  // Set per request by proxy.ts, which also puts it in the CSP header. Next attaches it to its
  // own scripts automatically; this copy is for the libraries that inject their own (the
  // pre-hydration theme script, and Base UI's scroll-lock styles).
  const nonce = (await headers()).get("x-nonce") ?? undefined;

  return (
    // The theme script sets `data-theme` before React hydrates, so the attribute on the client
    // legitimately differs from what the server rendered. Suppressed for that attribute only.
    <html lang="en" suppressHydrationWarning className={`${geistSans.variable} ${geistMono.variable}`}>
      {/* `.ms` is the design system's own scope: every selector in ds.css is written under it. */}
      <body className="ms">
        <Providers nonce={nonce}>
          <AppShell>{children}</AppShell>
        </Providers>
      </body>
    </html>
  );
}
