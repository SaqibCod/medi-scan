"use client";

import { geistMono, geistSans } from "./fonts";
import "./styles/globals.css";

/**
 * The last resort: a failure in the root layout itself.
 *
 * This replaces the whole document, so it renders its own `<html>` and `<body>` and can rely
 * on none of the providers — no theme, no query client, no notices. It therefore uses plain
 * markup and the design tokens directly, and it must keep working even if the thing that
 * broke was the provider tree.
 *
 * No `data-theme` either, which is fine: tokens.css defines the dark palette on bare `:root`,
 * so the page still looks like Medi-Scan rather than unstyled HTML.
 */
export default function GlobalError({ reset }: { error: Error; reset: () => void }) {
  return (
    <html lang="en" className={`${geistSans.variable} ${geistMono.variable}`}>
      <body className="ms">
        <main
          style={{
            minHeight: "100dvh",
            display: "flex",
            flexDirection: "column",
            justifyContent: "center",
            gap: "var(--space-5)",
            padding: "var(--space-8) var(--space-4)",
            maxWidth: 640,
            margin: "0 auto",
          }}
        >
          <p className="t-eyebrow" style={{ color: "var(--flag-high-fg)" }}>
            Something went wrong
          </p>

          <h1 className="t-display-m" style={{ color: "var(--ink)" }}>
            Something broke on our side.{" "}
            <span style={{ color: "var(--faint)" }}>It&rsquo;s not you.</span>
          </h1>

          <p className="t-body" style={{ color: "var(--ink-2)" }}>
            Please try again in a moment. If it keeps happening, reload the page.
          </p>

          <div style={{ display: "flex", gap: "var(--space-3)", flexWrap: "wrap" }}>
            <button type="button" className="ms-btn ms-btn--solid" onClick={reset}>
              <span>Try again</span>
            </button>
            {/*
              A plain anchor on purpose, not next/link. This boundary replaces the whole
              document, and whatever broke may well be the router itself — a client-side
              navigation could fail the same way and leave someone stuck here. A full page
              load is the one thing guaranteed to work.
            */}
            {/* eslint-disable-next-line @next/next/no-html-link-for-pages */}
            <a className="ms-btn ms-btn--outline" href="/">
              <span>Back to home</span>
            </a>
          </div>
        </main>
      </body>
    </html>
  );
}
