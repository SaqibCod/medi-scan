import type { NextConfig } from "next";

/**
 * Security headers that are the same on every response.
 *
 * The Content Security Policy is NOT here — it carries a per-request nonce and so lives in
 * `proxy.ts`. These are set in the config instead so they also cover the static assets that
 * the proxy's matcher deliberately skips.
 */
const securityHeaders = [
  { key: "X-Content-Type-Options", value: "nosniff" },
  // Report URLs are not secret, but there is no reason to hand the path to another origin.
  { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
  // `camera=(self)` because the phone drop zone offers "Take a photo". Nothing else is used,
  // and an injected script should not be able to start using them.
  {
    key: "Permissions-Policy",
    value: "camera=(self), microphone=(), geolocation=(), payment=()",
  },
  // Belt and braces with `frame-ancestors 'none'` in the CSP, for older browsers.
  { key: "X-Frame-Options", value: "DENY" },
];

const productionHeaders = [
  // Two years, including subdomains. Only meaningful over HTTPS, so it is production-only.
  { key: "Strict-Transport-Security", value: "max-age=63072000; includeSubDomains" },
];

const nextConfig: NextConfig = {
  // The header says which server is running and helps nobody but an attacker.
  poweredByHeader: false,

  async headers() {
    return [
      {
        source: "/:path*",
        headers:
          process.env.NODE_ENV === "production"
            ? [...securityHeaders, ...productionHeaders]
            : securityHeaders,
      },
    ];
  },
};

export default nextConfig;
