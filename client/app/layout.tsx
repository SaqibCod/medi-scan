import type { Metadata, Viewport } from "next";

import "./styles/globals.css";
import { geistMono, geistSans } from "./fonts";
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

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="en" className={`${geistSans.variable} ${geistMono.variable}`}>
      {/* `.ms` is the design system's own scope: every selector in ds.css is written under it. */}
      <body className="ms">{children}</body>
    </html>
  );
}
