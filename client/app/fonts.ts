import { Geist, Geist_Mono } from "next/font/google";

/**
 * Geist and Geist Mono, self-hosted.
 *
 * `next/font/google` downloads the files at build time and serves them from our own origin, so
 * the browser never talks to Google. That is a privacy promise the /privacy page makes, and a
 * CSP rule (`font-src 'self'`) enforces it — the `@import url("fonts.googleapis.com")` at the
 * top of the design system's bundle.css is deliberately not ported (LLD F4).
 *
 * No `weight` is given on purpose. Both families ship a variable `wght` axis covering 100–900,
 * which is one file per family for every weight the design system uses (300–600 sans, 400–500
 * mono). Listing the weights individually would fetch a separate static file for each.
 */

export const geistSans = Geist({
  subsets: ["latin"],
  display: "swap",
  variable: "--font-geist-sans",
});

export const geistMono = Geist_Mono({
  subsets: ["latin"],
  display: "swap",
  variable: "--font-geist-mono",
});
