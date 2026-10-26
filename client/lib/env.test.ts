import { afterEach, describe, expect, it, vi } from "vitest";

/**
 * `env.ts` validates at import, so each case needs a fresh module registry rather than a
 * re-call of a function.
 */
async function loadEnv(vars: Record<string, string | undefined>) {
  vi.resetModules();
  for (const [key, value] of Object.entries(vars)) {
    if (value === undefined) vi.stubEnv(key, "");
    else vi.stubEnv(key, value);
  }
  return import("./env");
}

afterEach(() => {
  vi.unstubAllEnvs();
  vi.resetModules();
});

describe("NEXT_PUBLIC_API_URL", () => {
  it("defaults to the local backend in development", async () => {
    const { API_URL } = await loadEnv({ NODE_ENV: "development" });
    expect(API_URL).toBe("http://localhost:8080");
  });

  it("drops a trailing slash, so paths join cleanly", async () => {
    const { API_URL } = await loadEnv({
      NODE_ENV: "development",
      NEXT_PUBLIC_API_URL: "https://api.example.com/",
    });
    expect(API_URL).toBe("https://api.example.com");
  });

  it("refuses plain http to a real host in production", async () => {
    // The session token would travel in the clear.
    await expect(
      loadEnv({ NODE_ENV: "production", NEXT_PUBLIC_API_URL: "http://api.example.com" }),
    ).rejects.toThrow(/https/);
  });

  it("allows plain http to loopback in production", async () => {
    // A production build run locally is how the e2e suite works, and nothing leaves the box.
    const { API_URL } = await loadEnv({
      NODE_ENV: "production",
      NEXT_PUBLIC_API_URL: "http://localhost:8080",
    });
    expect(API_URL).toBe("http://localhost:8080");
  });

  it("rejects something that is not a URL at all", async () => {
    await expect(
      loadEnv({ NODE_ENV: "development", NEXT_PUBLIC_API_URL: "not a url" }),
    ).rejects.toThrow(/not a valid URL/);
  });
});

describe("optional configuration", () => {
  it("has no repo URL unless one is set, so the footer link can be hidden", async () => {
    const { REPO_URL } = await loadEnv({ NODE_ENV: "development" });
    expect(REPO_URL).toBeNull();
  });

  it("keeps sign-in off unless explicitly turned on", async () => {
    // A button that does nothing is worse than no button (LLD F8).
    const off = await loadEnv({ NODE_ENV: "development" });
    expect(off.FEATURE_SIGNIN).toBe(false);

    const on = await loadEnv({ NODE_ENV: "development", NEXT_PUBLIC_FEATURE_SIGNIN: "true" });
    expect(on.FEATURE_SIGNIN).toBe(true);
  });
});
