import type { NextConfig } from "next";

/**
 * The API origin has to be in `connect-src` or every call is blocked. It is read here rather
 * than hardcoded because it differs per environment (localhost, then the EC2 domain).
 */
const apiOrigin = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

/**
 * Content Security Policy, from `docs/plan.md` section 6.
 *
 * This is the main thing standing between a script-injection bug and the tokens in
 * `sessionStorage`, which is the accepted trade-off of not using cookies (plan 4.9). Keep it
 * strict, and do not add a third-party script host: Google Identity Services is the only one
 * allowed, and analytics and tag managers are explicitly out.
 *
 * `'unsafe-inline'` appears in `style-src` only. Tailwind and the Next.js framework both emit
 * inline styles, and a style injection cannot execute code. It is deliberately absent from
 * `script-src`, where it would defeat the whole policy.
 */
const contentSecurityPolicy = [
  "default-src 'self'",

  // PHASE 6: add `https://accounts.google.com/gsi/client` for Google Identity Services.
  //   "script-src 'self' https://accounts.google.com/gsi/client",
  // `'unsafe-eval'` is needed in development only, for React Fast Refresh.
  process.env.NODE_ENV === "development"
    ? "script-src 'self' 'unsafe-eval' 'unsafe-inline'"
    : "script-src 'self'",

  // PHASE 6: add `https://accounts.google.com/gsi/style`.
  //   "style-src 'self' 'unsafe-inline' https://accounts.google.com/gsi/style",
  "style-src 'self' 'unsafe-inline'",

  // PHASE 6: add `https://accounts.google.com` so the sign-in iframe can render.
  //   "frame-src https://accounts.google.com",
  "frame-src 'none'",

  // PHASE 6: add `https://accounts.google.com` for token exchange.
  `connect-src 'self' ${apiOrigin}`,

  "img-src 'self' data:",
  "font-src 'self'",
  "object-src 'none'",
  "base-uri 'self'",

  // Not in plan section 6, but it closes the gap `object-src 'none'` leaves: without it a
  // `<base>`-less injection could still submit a form to an attacker's host.
  "form-action 'self'",
  "frame-ancestors 'none'",
].join("; ");

const securityHeaders = [
  { key: "Content-Security-Policy", value: contentSecurityPolicy },
  // Belt and braces with `frame-ancestors` above, for older browsers.
  { key: "X-Frame-Options", value: "DENY" },
  { key: "X-Content-Type-Options", value: "nosniff" },
  // Report URLs are not secret, but there is no reason to leak the path to another origin.
  { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
  // None of these are used, and an injected script should not be able to start using them.
  { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=(), payment=()" },
];

const nextConfig: NextConfig = {
  // The header says which server is running and helps nobody but an attacker.
  poweredByHeader: false,

  async headers() {
    return [{ source: "/:path*", headers: securityHeaders }];
  },
};

export default nextConfig;
