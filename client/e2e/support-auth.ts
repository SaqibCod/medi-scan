import type { Page, Route } from "@playwright/test";

import { API } from "./support";

/**
 * Everything the 7c specs need: a stand-in for Google, and the five auth endpoints.
 *
 * <strong>Google is replaced, not called.</strong> `accounts.google.com/gsi/client` is
 * intercepted and answered with a small script that defines the same four functions the app
 * uses. Nothing in this suite reaches Google, which is what makes it runnable offline, in CI,
 * and without anyone's account.
 */

export const GSI_SRC = "https://accounts.google.com/gsi/client";
export const ID_TOKEN = "e2e-google-id-token";

export const REFRESH_KEY = "ms.refresh";
export const TAB_KEY = "ms.tab";

/** The display name every signed-in fixture uses. */
export const USER_NAME = "Saq";

/**
 * The stub that stands in for Google Identity Services.
 *
 * Deliberately the same shape as the real thing — `initialize`, `prompt`, `renderButton`,
 * `disableAutoSelect` — so the app is exercised exactly as it would be, and the only thing
 * that changes is where the credential comes from. `window.__gis` is the handle the tests
 * use to see what was asked of it.
 */
const GSI_STUB = `
(() => {
  const state = {
    prompted: 0,
    rendered: 0,
    disabled: false,
    initialised: 0,
    callback: null,
    clientId: null,
  };
  window.__gis = state;

  function deliver() {
    if (state.callback && !state.disabled) state.callback({ credential: ${JSON.stringify(ID_TOKEN)} });
  }
  window.__gisDeliver = deliver;

  window.google = {
    accounts: {
      id: {
        initialize(config) {
          state.initialised += 1;
          state.callback = config.callback;
          state.clientId = config.client_id;
          state.autoSelect = config.auto_select;
        },
        prompt() {
          state.prompted += 1;
          // Stands in for One Tap signing a returning visitor in without a click. Off unless
          // the test asks for it, because that is also how Google behaves most of the time.
          if (window.__gisAutoSignIn && !state.disabled) setTimeout(deliver, 20);
        },
        renderButton(parent) {
          state.rendered += 1;
          const button = document.createElement("button");
          button.type = "button";
          button.textContent = "Sign in with Google";
          button.addEventListener("click", deliver);
          parent.replaceChildren(button);
        },
        disableAutoSelect() {
          state.disabled = true;
        },
      },
    },
  };
})();
`;

/**
 * Serves the stub in place of Google's library.
 *
 * `autoSignIn` makes One Tap hand over a credential on its own, which is the only way to
 * exercise the returning-visitor path without a real Google session.
 */
export async function googleStub(page: Page, options: { autoSignIn?: boolean } = {}) {
  if (options.autoSignIn) {
    await page.addInitScript(() => {
      (window as unknown as { __gisAutoSignIn: boolean }).__gisAutoSignIn = true;
    });
  }

  await page.route(`${GSI_SRC}*`, (route: Route) =>
    route.fulfill({
      status: 200,
      contentType: "application/javascript",
      body: GSI_STUB,
    }),
  );
}

export function tokens(over: Record<string, unknown> = {}) {
  return {
    accessToken: "e2e-access-token",
    accessTokenExpiresAt: new Date(Date.now() + 900_000).toISOString(),
    refreshToken: "e2e-refresh-token",
    refreshTokenExpiresAt: new Date(Date.now() + 7 * 86_400_000).toISOString(),
    user: {
      id: "11111111-2222-3333-4444-555555555555",
      displayName: USER_NAME,
      role: "USER",
      createdAt: "2026-10-02T09:30:00Z",
    },
    ...over,
  };
}

export interface AuthOptions {
  claimedReportCount?: number;
  newUser?: boolean;
  reportCount?: number;
  /** Count the calls each endpoint received, so a test can prove "exactly once". */
  calls?: Record<string, number>;
}

/** The five auth endpoints, answering as the contract says they do. */
export async function authEndpoints(page: Page, options: AuthOptions = {}) {
  const calls = options.calls ?? {};
  const bump = (key: string) => {
    calls[key] = (calls[key] ?? 0) + 1;
  };

  await page.route(`${API}/api/auth/google`, (route: Route) => {
    bump("google");
    return route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(
        tokens({
          claimedReportCount: options.claimedReportCount ?? 0,
          newUser: options.newUser ?? false,
        }),
      ),
    });
  });

  await page.route(`${API}/api/auth/refresh`, (route: Route) => {
    bump("refresh");
    return route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(tokens()),
    });
  });

  await page.route(`${API}/api/auth/logout`, (route: Route) => {
    bump("logout");
    return route.fulfill({ status: 204, body: "" });
  });

  await page.route(`${API}/api/me`, (route: Route) => {
    if (route.request().method() === "DELETE") {
      bump("deleteMe");
      return route.fulfill({ status: 204, body: "" });
    }
    bump("me");
    return route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        id: "11111111-2222-3333-4444-555555555555",
        displayName: USER_NAME,
        role: "USER",
        createdAt: "2026-10-02T09:30:00Z",
        reportCount: options.reportCount ?? 3,
      }),
    });
  });

  return calls;
}

/** Puts a refresh token in the tab before anything runs, so it starts signed in. */
export async function startSignedIn(page: Page) {
  await page.addInitScript(
    ([key, value]) => {
      try {
        window.sessionStorage.setItem(key, value);
      } catch {
        // Storage blocked; the test will see a guest and fail loudly, which is correct.
      }
    },
    [REFRESH_KEY, "e2e-refresh-token-from-before"],
  );
}

export function listItem(over: Record<string, unknown> = {}) {
  return {
    id: "6f1c2a9e-3b4d-4e8f-9a1b-2c3d4e5f6a7b",
    status: "DONE",
    sourceType: "PDF",
    createdAt: "2026-10-12T09:31:00Z",
    expiresAt: new Date(Date.now() + 30 * 86_400_000 + 60_000).toISOString(),
    collectedOn: "2026-09-28",
    counts: { total: 5, low: 0, normal: 3, high: 2, unknown: 0 },
    errorCode: null,
    ...over,
  };
}

/**
 * `GET /api/reports` for every status the page asks about.
 *
 * Keyed by status so a test can give the Processing tab two different lists and watch them
 * merge, which is the one piece of paging the client does itself.
 */
export async function reportList(
  page: Page,
  byStatus: Record<string, unknown[]>,
  pageSize = 6,
) {
  await page.route(`${API}/api/reports?**`, (route: Route) => {
    const url = new URL(route.request().url());
    const status = url.searchParams.get("status") ?? "all";
    const wanted = Number(url.searchParams.get("page") ?? "0");
    const size = Number(url.searchParams.get("size") ?? String(pageSize));

    const all = byStatus[status] ?? [];
    const start = wanted * size;

    return route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        items: all.slice(start, start + size),
        page: wanted,
        size,
        totalItems: all.length,
        totalPages: Math.max(1, Math.ceil(all.length / size)),
      }),
    });
  });
}

export function trendPoint(over: Record<string, unknown> = {}) {
  return {
    reportId: "6f1c2a9e-3b4d-4e8f-9a1b-2c3d4e5f6a7b",
    date: "2026-03-14",
    dateSource: "COLLECTED",
    value: 148,
    refLow: null,
    refHigh: 100,
    flag: "HIGH",
    ...over,
  };
}

export const LDL_TREND = {
  marker: "ldl-cholesterol",
  displayName: "LDL Cholesterol",
  series: [
    {
      unit: "mg/dL",
      points: [
        trendPoint({ date: "2026-03-14", value: 148 }),
        trendPoint({ date: "2026-06-20", value: 155 }),
        trendPoint({ date: "2026-09-28", value: 162 }),
      ],
    },
  ],
};

/** Both trend endpoints. `markers` empty is the "nothing to compare yet" case. */
export async function trendEndpoints(
  page: Page,
  options: { markers?: unknown[]; trends?: Record<string, unknown> } = {},
) {
  await page.route(`${API}/api/trends/markers`, (route: Route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(
        options.markers ?? [
          { marker: "ldl-cholesterol", displayName: "LDL Cholesterol", reportCount: 3 },
          { marker: "tsh", displayName: "TSH", reportCount: 2 },
        ],
      ),
    }),
  );

  await page.route(`${API}/api/trends?**`, (route: Route) => {
    const marker = new URL(route.request().url()).searchParams.get("marker") ?? "";
    const body = options.trends?.[marker] ?? (marker === "ldl-cholesterol" ? LDL_TREND : null);

    return route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(body ?? { marker, displayName: marker, series: [] }),
    });
  });
}

/** What the tab is holding, for the storage assertions. */
export async function storageKeys(page: Page) {
  return page.evaluate(() => ({
    local: Object.keys(window.localStorage).sort(),
    session: Object.keys(window.sessionStorage).sort(),
  }));
}
