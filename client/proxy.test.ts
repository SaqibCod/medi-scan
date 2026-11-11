// @vitest-environment node

import { NextRequest } from "next/server";
import { afterEach, describe, expect, it, vi } from "vitest";

import { proxy } from "./proxy";

afterEach(() => {
  vi.unstubAllEnvs();
});

function policyFor(nodeEnv: "development" | "production"): string {
  vi.stubEnv("NODE_ENV", nodeEnv);
  const response = proxy(new NextRequest("http://localhost:3000/"));
  return response.headers.get("content-security-policy") ?? "";
}

describe("the Content Security Policy", () => {
  it("is set on the response", () => {
    expect(policyFor("production")).toContain("default-src 'self'");
  });

  it("carries a nonce, and a different one each request", () => {
    vi.stubEnv("NODE_ENV", "production");

    const first = proxy(new NextRequest("http://localhost:3000/"));
    const second = proxy(new NextRequest("http://localhost:3000/"));

    const nonceOf = (r: Response) => r.headers.get("content-security-policy")?.match(/'nonce-([^']+)'/)?.[1];

    expect(nonceOf(first)).toBeTruthy();
    // A predictable nonce is the same as no nonce at all.
    expect(nonceOf(first)).not.toBe(nonceOf(second));
  });

  it("uses a nonce the browser will accept", () => {
    vi.stubEnv("NODE_ENV", "production");

    const response = proxy(new NextRequest("http://localhost:3000/"));
    const nonce = response.headers.get("content-security-policy")?.match(/'nonce-([^']+)'/)?.[1];

    // base64, and long enough not to be guessable within a page's lifetime.
    expect(nonce).toMatch(/^[A-Za-z0-9+/]+=*$/);
    expect(nonce!.length).toBeGreaterThanOrEqual(32);
  });

  // The other half of the nonce contract — that this same value reaches the app through the
  // `x-nonce` request header and ends up on every script tag — is asserted end-to-end. It
  // cannot be seen from here: `NextResponse.next({ request: { headers } })` forwards those
  // headers to the render through Next's internals, not on the response this returns.

  it("never allows unsafe-eval in production", () => {
    // React needs it in development to rebuild server stack traces. Shipping it would let an
    // injected string become code, which is most of what the policy exists to stop.
    expect(policyFor("production")).not.toContain("unsafe-eval");
    expect(policyFor("development")).toContain("unsafe-eval");
  });

  it("never allows inline script, in either environment", () => {
    for (const env of ["development", "production"] as const) {
      const policy = policyFor(env);
      const scriptSrc = policy.split("; ").find((d) => d.startsWith("script-src"));
      expect(scriptSrc, env).not.toContain("'unsafe-inline'");
    }
  });

  it("allows inline styles, and only styles", () => {
    const policy = policyFor("production");
    expect(policy).toContain("style-src 'self' 'unsafe-inline'");

    // A nonce in style-src would make 'unsafe-inline' inert, and inline style *attributes*
    // can never carry one. See SECURITY.md.
    const styleSrc = policy.split("; ").find((d) => d.startsWith("style-src"));
    expect(styleSrc).not.toContain("nonce-");
  });

  it("names the API origin in connect-src, so calls are not blocked", () => {
    vi.stubEnv("NEXT_PUBLIC_API_URL", "https://api.example.test");
    expect(policyFor("production")).toContain("connect-src 'self' https://api.example.test");
  });

  it("locks down the directives an injection would reach for", () => {
    const policy = policyFor("production");

    expect(policy).toContain("object-src 'none'");
    expect(policy).toContain("base-uri 'none'");
    expect(policy).toContain("frame-ancestors 'none'");
    expect(policy).toContain("form-action 'self'");
    expect(policy).toContain("font-src 'self'");
  });

  it("upgrades insecure requests in production only", () => {
    expect(policyFor("production")).toContain("upgrade-insecure-requests");
    expect(policyFor("development")).not.toContain("upgrade-insecure-requests");
  });

  it("allows Google Identity Services, and no other third party", () => {
    const policy = policyFor("production");

    // Exactly the three directives Google documents, each a path under one origin rather than
    // a wildcard, and the parent path rather than individual endpoints so that a change on
    // Google's side does not break sign-in here.
    expect(policy).toContain("frame-src https://accounts.google.com/gsi/");
    expect(policy).toContain("style-src 'self' 'unsafe-inline' https://accounts.google.com/gsi/style");
    expect(policy).toContain(
      "connect-src 'self' http://localhost:8080 https://accounts.google.com/gsi/",
    );
  });

  it("does not name Google in script-src, where 'strict-dynamic' would ignore it anyway", () => {
    const scriptSrc = policyFor("production")
      .split("; ")
      .find((d) => d.startsWith("script-src"));

    expect(scriptSrc).toContain("'strict-dynamic'");
    expect(scriptSrc).not.toContain("accounts.google.com");
  });

  it("allows exactly one third-party origin, and it is Google's", () => {
    const policy = policyFor("production");

    const origins = [...new Set((policy.match(/https?:\/\/[^\s;]+/g) ?? []).map((u) => new URL(u).origin))];

    expect(origins.filter((o) => !o.includes("localhost"))).toEqual([
      "https://accounts.google.com",
    ]);
    expect(policy).not.toContain("fonts.googleapis.com");
  });
});
