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

export function proxy(request: NextRequest) {
  // Read per request rather than once at module load: the cost is nothing, and a value frozen
  // at import time is both harder to test and wrong the moment the environment changes.
  const API_ORIGIN = apiOrigin();
  const isDev = process.env.NODE_ENV === "development";
  const nonce = Buffer.from(crypto.randomUUID()).toString("base64");

  const policy = [
    "default-src 'self'",

    // 7c adds Google Identity Services here, and only there. No analytics, no tag managers.
    // `'unsafe-eval'` is development only: React uses eval to rebuild server stack traces in
    // the browser. A production response never carries it.
    `script-src 'self' 'nonce-${nonce}' 'strict-dynamic'${isDev ? " 'unsafe-eval'" : ""}`,

    // Styles only, and deliberately without a nonce — adding one would make 'unsafe-inline'
    // inert, and inline style *attributes* can never carry a nonce anyway. React, next-themes
    // (color-scheme) and our own components' `style` props all emit them. An injected
    // stylesheet cannot read sessionStorage or call an API, and CSS-based exfiltration is
    // already closed off by img-src below. Reasoning in full in SECURITY.md.
    "style-src 'self' 'unsafe-inline'",

    // `blob:` is for previewing a chosen file before upload, from 7b.
    "img-src 'self' data: blob:",
    "font-src 'self'",

    // The dev server talks to itself over a websocket for hot reload.
    `connect-src 'self' ${API_ORIGIN}${isDev ? " ws: wss:" : ""}`,

    "frame-src 'none'",
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
