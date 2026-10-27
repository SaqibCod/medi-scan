import type { BrowserContext, Page, Route } from "@playwright/test";

/**
 * Shared helpers for the e2e suite.
 *
 * The backend is never run. Every test states what the API should answer, which makes the
 * failure modes — a sleeping server, a daily cap, a dead session — reachable on demand rather
 * than only by luck.
 */

export const API = "http://localhost:8080";

/** Seeded before any script runs, so the pre-paint theme script sees it. */
export async function seedTheme(context: BrowserContext, theme: "dark" | "light") {
  await context.addInitScript(
    ([key, value]) => {
      try {
        window.localStorage.setItem(key, value);
      } catch {
        // Storage blocked; the test will just get the device default.
      }
    },
    ["ms-theme", theme],
  );
}

/** `GET /actuator/health` answers UP. */
export async function backendUp(page: Page) {
  await page.route(`${API}/actuator/health`, (route: Route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: '{"status":"UP"}' }),
  );
}

/** Health never answers — what a sleeping free-tier server looks like. */
export async function backendAsleep(page: Page) {
  await page.route(`${API}/actuator/health`, async (route: Route) => {
    // Hang past the client's 4s health timeout rather than refusing, which is the real
    // behaviour of a container that is still starting.
    await new Promise((resolve) => setTimeout(resolve, 10_000));
    await route.abort("timedout");
  });
}

/** Counts health checks so a test can watch it recover. */
export function trackHealth(page: Page) {
  const state = { calls: 0, up: false };

  void page.route(`${API}/actuator/health`, (route: Route) => {
    state.calls += 1;
    return state.up
      ? route.fulfill({ status: 200, contentType: "application/json", body: '{"status":"UP"}' })
      : route.fulfill({ status: 503, contentType: "application/json", body: '{"status":"DOWN"}' });
  });

  return state;
}

/** A Problem Details response, in the shape the backend actually sends (contract 1.5). */
export function problem(
  status: number,
  code: string,
  extra: Record<string, unknown> = {},
): { status: number; contentType: string; body: string; headers?: Record<string, string> } {
  return {
    status,
    contentType: "application/problem+json",
    body: JSON.stringify({
      type: `https://medi-scan.dev/errors/${code.toLowerCase()}`,
      title: code,
      status,
      detail: "Something for a human to read.",
      code,
      requestId: "req-e2e-1",
      ...extra,
    }),
  };
}

/** Collects CSP violations and page errors for the whole test. */
export function watchForProblems(page: Page) {
  const problems: string[] = [];

  page.on("console", (message) => {
    const text = message.text();
    if (/Content Security Policy|Refused to|violat/i.test(text)) problems.push(`CSP: ${text}`);
  });
  page.on("pageerror", (error) => problems.push(`pageerror: ${error.message}`));

  return problems;
}

/** Every host the page talked to. Used to prove nothing reaches Google. */
export function watchRequests(page: Page) {
  const hosts = new Set<string>();
  page.on("request", (request) => hosts.add(new URL(request.url()).host));
  return hosts;
}
