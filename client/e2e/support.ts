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

/** The three bundled samples, as `GET /api/samples` returns them (contract 3). */
export const SAMPLES = [
  {
    id: "lipid-panel",
    title: "Lipid panel",
    description: "Cholesterol and triglycerides, with high LDL.",
    markerCount: 5,
  },
  {
    id: "cbc",
    title: "Complete blood count",
    description: "Red and white blood cells, all in range.",
    markerCount: 12,
  },
  {
    id: "thyroid-panel",
    title: "Thyroid panel",
    description: "TSH and free T4, with low TSH.",
    markerCount: 3,
  },
];

/** `GET /api/samples` answers with the bundled three. */
export async function samplesLoad(page: Page) {
  await page.route(`${API}/api/samples`, (route: Route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(SAMPLES) }),
  );
}

/** `POST /api/sessions` hands out a guest token. */
export async function guestSession(page: Page) {
  await page.route(`${API}/api/sessions`, (route: Route) =>
    route.fulfill({
      status: 201,
      contentType: "application/json",
      body: JSON.stringify({
        token: "e2e-guest-token",
        expiresAt: new Date(Date.now() + 24 * 3_600_000).toISOString(),
      }),
    }),
  );
}

/** The landing page, ready to use: health up, samples listed, a session available. */
export async function landingReady(page: Page) {
  await backendUp(page);
  await samplesLoad(page);
  await guestSession(page);
}

export const REPORT_ID = "6f1c2a9e-3b4d-4e8f-9a1b-2c3d4e5f6a7b";

/** A done report with the lipid panel from the mockups. */
export function doneReport() {
  return {
    id: REPORT_ID,
    status: "DONE",
    sourceType: "PDF",
    createdAt: "2026-10-02T09:31:00Z",
    expiresAt: new Date(Date.now() + 23 * 3_600_000 + 12 * 60_000 + 30_000).toISOString(),
    error: null,
    result: {
      collectedOn: "2026-09-28",
      biomarkers: [
        row("b1", "Total Cholesterol", "238", "<200", "HIGH", "total-cholesterol"),
        row("b2", "LDL Cholesterol", "162", "<100", "HIGH", "ldl-cholesterol"),
        row("b3", "HDL Cholesterol", "52", ">40", "NORMAL", "hdl-cholesterol"),
        row("b4", "Triglycerides", "140", "<150", "NORMAL", "triglycerides"),
        row("b5", "VLDL Cholesterol", "28", "5-40", "NORMAL", null),
      ],
      summary:
        "Most of your results are in the normal range. Your total and LDL cholesterol are higher than the reference ranges printed on this report.",
      highlights: [
        "Total Cholesterol is 238 mg/dL, above the reference range of <200.",
        "LDL Cholesterol is 162 mg/dL, above the reference range of <100.",
      ],
      counts: { total: 5, low: 0, normal: 3, high: 2, unknown: 0 },
    },
  };
}

function row(
  id: string,
  testName: string,
  rawValue: string,
  referenceRangeText: string,
  flag: string,
  biomarkerSlug: string | null,
) {
  return {
    id,
    testName,
    rawValue,
    numericValue: Number(rawValue),
    unit: "mg/dL",
    referenceRangeText,
    refLow: null,
    refHigh: null,
    flag,
    biomarkerSlug,
  };
}

/** The same report, still being read. */
export function processingReport(status: "PENDING" | "PROCESSING" = "PROCESSING") {
  return { ...doneReport(), status, result: null };
}

/** The same report, failed with the given code. */
export function failedReport(code: string, message = "Server-side wording.") {
  return { ...doneReport(), status: "FAILED", result: null, error: { code, message } };
}

/** `GET /api/reports/{id}` answers with whatever the test says. */
export async function reportIs(page: Page, body: unknown) {
  await page.route(`${API}/api/reports/${REPORT_ID}`, (route: Route) => {
    if (route.request().method() === "DELETE") {
      return route.fulfill({ status: 204, body: "" });
    }
    return route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(body),
    });
  });
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
