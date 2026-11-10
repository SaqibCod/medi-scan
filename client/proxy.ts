import { NextResponse, type NextRequest } from "next/server";

/**
 * Per-request security headers, chiefly the Content Security Policy.
 *
 * This file is `proxy.ts`, not `middleware.ts`: Next.js 16 renamed the convention, and the
 * old name is deprecated.
 *
 * The CSP is the main thing standing between a script-injection bug and the tokens this app
 * keeps in browser storage, which is the accepted cost of not using cookies (plan 4.9). It
 * has to be set here rather than in `next.config.ts` because a nonce must be fresh per
 * request, and a static header cannot be.
 *
 * Next reads the nonce back out of the CSP header it finds on the request and attaches it to
 * its own framework and chunk scripts automatically. `x-nonce` is the copy the app reads in
 * the root layout, for the libraries that inject their own inline tags (the pre-hydration
 * theme script, and Base UI's scroll-lock styles).
 *
 * See SECURITY.md for the policy in full and the reasoning behind `style-src`.
 */

/** Must be in `connect-src` or every API call is blocked. Differs per environment. */
function apiOrigin(): string {
  return process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
}

/**
 * The parent URL of Google Identity Services, as Google's own CSP guidance names it.
 *
 * A path, not a bare origin, and deliberately the parent rather than the individual endpoints:
 * Google asks for this precisely so a change on their side does not break sign-in here.
 */
const GSI = "https://accounts.google.com/gsi/";
const GSI_STYLE = "https://accounts.google.com/gsi/style";

export function proxy(request: NextRequest) {
  // Read per request rather than once at module load: the cost is nothing, and a value frozen
  // at import time is both harder to test and wrong the moment the environment changes.
  const API_ORIGIN = apiOrigin();
  const isDev = process.env.NODE_ENV === "development";
  const nonce = Buffer.from(crypto.randomUUID()).toString("base64");

  const policy = [
    "default-src 'self'",

    // Google Identity Services is deliberately NOT named here, though its own documentation
    // lists `script-src https://accounts.google.com/gsi/client`. `'strict-dynamic'` makes every
    // host expression in this directive inert, so adding it would change nothing and only
    // suggest it was doing something. The GIS loader carries the nonce instead, and
    // `'strict-dynamic'` extends that trust to whatever it goes on to load.
    //
    // `'unsafe-eval'` is development only: React uses eval to rebuild server stack traces in
    // the browser. A production response never carries it.
    `script-src 'self' 'nonce-${nonce}' 'strict-dynamic'${isDev ? " 'unsafe-eval'" : ""}`,

    // Styles only, and deliberately without a nonce — adding one would make 'unsafe-inline'
    // inert, and inline style *attributes* can never carry a nonce anyway. React, next-themes
    // (color-scheme) and our own components' `style` props all emit them. An injected
    // stylesheet cannot read sessionStorage or call an API, and CSS-based exfiltration is
    // already closed off by img-src below. Reasoning in full in SECURITY.md.
    // The GIS entry is for the stylesheet the library injects for its button. `'unsafe-inline'`
    // covers inline attributes, not an external sheet, so it has to be named.
    `style-src 'self' 'unsafe-inline' ${GSI_STYLE}`,

    // `blob:` is for previewing a chosen file before upload, from 7b.
    "img-src 'self' data: blob:",
    "font-src 'self'",

    // The dev server talks to itself over a websocket for hot reload. The GIS entry lets
    // Google's iframe reach its own endpoints; nothing about a report ever goes there.
    `connect-src 'self' ${API_ORIGIN} ${GSI}${isDev ? " ws: wss:" : ""}`,

    // Was `'none'` until sign-in shipped. GIS renders both its button and the One Tap prompt
    // in an iframe from this path, and there is no version of the ID-token flow without one.
    `frame-src ${GSI}`,
    "object-src 'none'",
    // Nothing here uses <base>, and allowing one lets an injection re-point every relative URL.
    "base-uri 'none'",
    "form-action 'self'",
    "frame-ancestors 'none'",
    ...(isDev ? [] : ["upgrade-insecure-requests"]),
  ].join("; ");

  const requestHeaders = new Headers(request.headers);
  requestHeaders.set("x-nonce", nonce);
  requestHeaders.set("Content-Security-Policy", policy);

  const response = NextResponse.next({ request: { headers: requestHeaders } });
  response.headers.set("Content-Security-Policy", policy);

  return response;
}

export const config = {
  matcher: [
    {
      // Static assets carry no scripts of their own and need no per-request nonce; serving
      // them without it keeps them cacheable. Prefetches are skipped for the same reason.
      source: "/((?!_next/static|_next/image|favicon.ico).*)",
      missing: [
        { type: "header", key: "next-router-prefetch" },
        { type: "header", key: "purpose", value: "prefetch" },
      ],
    },
  ],
};
