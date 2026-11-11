import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { NoticeProvider } from "@/components/notices/notice-provider";
import { ToastProvider } from "@/components/notices/toaster";
import { TrendsView } from "@/components/trends/trends-view";
import type { TrendPoint } from "@/lib/api-types";
import { AuthProvider } from "@/lib/auth/provider";
import { resetAuthChannelForTests } from "@/lib/auth/channel";
import { resetGoogleForTests } from "@/lib/auth/google";
import { resetRefreshForTests } from "@/lib/auth/refresh";
import { REFRESH_STORAGE_KEY, resetTokensForTests } from "@/lib/auth/tokens";
import { resetBackendStatusForTests } from "@/lib/backend-status";
import { resetGuestSessionForTests } from "@/lib/guest-session";
import { createQueryClient } from "@/lib/query-client";

/**
 * Trends (LLD 10, unit test 9).
 *
 * jsdom has no layout, so Recharts' responsive container renders at zero size and draws no
 * SVG. That is fine and deliberate: what these assert is everything around the plot — the
 * series split, the delta, the notices, the legends and the table — which is where the
 * mistakes that would mislead a reader actually live. The plot itself is covered by the
 * pure-function tests and by the e2e screenshots.
 */

const push = vi.fn();
let search = new URLSearchParams();

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push, replace: push, prefetch: () => {} }),
  useSearchParams: () => search,
}));

vi.mock("@/lib/env", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/env")>()),
  CAN_SIGN_IN: true,
  FEATURE_SIGNIN: true,
  GOOGLE_CLIENT_ID: "test-client-id.apps.googleusercontent.com",
}));

function point(over: Partial<TrendPoint> & Pick<TrendPoint, "value" | "date">): TrendPoint {
  return {
    reportId: "r1",
    dateSource: "COLLECTED",
    refLow: null,
    refHigh: null,
    flag: "NORMAL",
    ...over,
  };
}

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function tokenResponse() {
  return {
    accessToken: "access-1",
    accessTokenExpiresAt: new Date(Date.now() + 900_000).toISOString(),
    refreshToken: "refresh-1",
    refreshTokenExpiresAt: new Date(Date.now() + 7 * 86_400_000).toISOString(),
    user: { id: "u1", displayName: "Saq", role: "USER", createdAt: "2026-10-02T09:30:00Z" },
  };
}

function installFakeGoogle() {
  window.google = {
    accounts: {
      id: {
        initialize() {},
        prompt() {},
        renderButton(parent: HTMLElement) {
          const button = document.createElement("button");
          button.textContent = "Sign in with Google";
          parent.appendChild(button);
        },
        disableAutoSelect() {},
      },
    },
  };
}

let fetchMock: ReturnType<typeof vi.fn>;

function serve(options: {
  markers?: unknown[];
  trend?: unknown;
  doneCount?: number;
} = {}) {
  fetchMock.mockImplementation((url: string) => {
    const target = String(url);

    if (target.includes("/actuator/health")) return Promise.resolve(json(200, { status: "UP" }));
    if (target.includes("/api/auth/refresh")) return Promise.resolve(json(200, tokenResponse()));
    if (target.includes("/api/trends/markers")) {
      return Promise.resolve(json(200, options.markers ?? []));
    }
    if (target.includes("/api/trends")) {
      return Promise.resolve(
        json(200, options.trend ?? { marker: "m", displayName: "M", series: [] }),
      );
    }
    if (target.includes("/api/reports")) {
      return Promise.resolve(
        json(200, { items: [], page: 0, size: 1, totalItems: options.doneCount ?? 0, totalPages: 0 }),
      );
    }

    return Promise.resolve(json(200, {}));
  });
}

function renderTrends() {
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <NoticeProvider>
        <ToastProvider>
          <AuthProvider clientId="test-client-id.apps.googleusercontent.com">
            <TrendsView />
          </AuthProvider>
        </ToastProvider>
      </NoticeProvider>
    </QueryClientProvider>,
  );
}

function asUser() {
  window.sessionStorage.setItem(REFRESH_STORAGE_KEY, "refresh-0");
}

beforeEach(() => {
  push.mockClear();
  search = new URLSearchParams();
  resetTokensForTests();
  resetRefreshForTests();
  resetGuestSessionForTests();
  resetGoogleForTests();
  resetAuthChannelForTests();
  resetBackendStatusForTests();
  installFakeGoogle();
  fetchMock = vi.fn();
  vi.stubGlobal("fetch", fetchMock);
  serve();
});

afterEach(() => {
  resetAuthChannelForTests();
  delete window.google;
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const LDL = {
  marker: "ldl-cholesterol",
  displayName: "LDL Cholesterol",
  series: [
    {
      unit: "mg/dL",
      points: [
        point({ value: 148, date: "2026-03-14", refHigh: 100, flag: "HIGH" }),
        point({ value: 155, date: "2026-06-20", refHigh: 100, flag: "HIGH" }),
        point({ value: 162, date: "2026-09-28", refHigh: 100, flag: "HIGH" }),
      ],
    },
  ],
};

describe("a guest", () => {
  it("is offered sign-in rather than an empty chart", async () => {
    renderTrends();

    expect(await screen.findByText("Sign in to see trends.")).toBeInTheDocument();
    expect(screen.getByText(/Guest reports disappear after 24 hours/)).toBeInTheDocument();
  });
});

describe("not enough to compare", () => {
  it("says how many finished reports there are so far", async () => {
    asUser();
    serve({ markers: [], doneCount: 1 });

    renderTrends();

    expect(await screen.findByText("Nothing to compare yet.")).toBeInTheDocument();
    expect(await screen.findByText(/You have 1 finished report so far/)).toBeInTheDocument();
  });
});

describe("a marker with one unit", () => {
  beforeEach(() => {
    asUser();
    serve({
      markers: [{ marker: "ldl-cholesterol", displayName: "LDL Cholesterol", reportCount: 3 }],
      trend: LDL,
    });
  });

  it("shows the latest value, its unit and its flag", async () => {
    renderTrends();

    const card = await screen.findByText("In MG/DL");
    // The card header only: the section also holds the visually hidden table, which
    // legitimately repeats every value.
    const head = card.closest(".ms-seriescard__head") as HTMLElement;

    expect(within(head).getByText("162")).toBeInTheDocument();
    expect(within(head).getByText("mg/dL")).toBeInTheDocument();
    expect(within(head).getByText("HIGH")).toBeInTheDocument();
  });

  it("states the change as a plain signed difference, with no judgement", async () => {
    renderTrends();

    const since = await screen.findByText("+14 mg/dL since 14 Mar 2026");

    // "3 reports" also labels the marker in the list beside it, so this asserts the one in
    // the card rather than either.
    const meta = since.closest(".ms-seriescard__meta") as HTMLElement;
    expect(within(meta).getByText("3 reports")).toBeInTheDocument();

    // Nothing anywhere says whether that is good or bad.
    expect(screen.queryByText(/improv|worse|better|target|ideal|should be/i)).not.toBeInTheDocument();
  });

  it("explains the band, and attributes it to the latest report", async () => {
    renderTrends();

    expect(await screen.findByText("Reference range on your latest report")).toBeInTheDocument();
    expect(
      screen.getByText(/the table shows each report's own/),
    ).toBeInTheDocument();
  });

  it("offers no hollow-dot legend when every point is dated by the report", async () => {
    renderTrends();
    await screen.findByText("In MG/DL");

    expect(screen.queryByText("Hollow dot: dated by upload")).not.toBeInTheDocument();
  });
});

describe("a marker reported in two units", () => {
  beforeEach(() => {
    asUser();
    serve({
      markers: [{ marker: "glucose", displayName: "Glucose", reportCount: 4 }],
      trend: {
        marker: "glucose",
        displayName: "Glucose",
        series: [
          {
            unit: "mg/dL",
            points: [
              point({ value: 92, date: "2026-05-11", refLow: 70, refHigh: 99 }),
              point({ value: 97, date: "2026-09-02", refLow: 70, refHigh: 99 }),
            ],
          },
          {
            unit: "mmol/L",
            points: [
              point({
                value: 5.4,
                date: "2026-10-09",
                dateSource: "UPLOADED",
                refLow: 3.9,
                refHigh: 5.5,
              }),
            ],
          },
        ],
      },
    });
  });

  it("gives each unit its own card and never converts between them", async () => {
    renderTrends();

    expect(await screen.findByText("In MG/DL")).toBeInTheDocument();
    expect(screen.getByText("In MMOL/L")).toBeInTheDocument();

    expect(
      screen.getByText(
        "Glucose was reported in 2 different units. Medi-Scan never converts between units, so each one gets its own chart.",
      ),
    ).toBeInTheDocument();
  });

  it("says a single-point series has no line yet, instead of drawing one", async () => {
    renderTrends();

    expect(
      await screen.findByText("Only one value in mmol/L so far. A line appears once there are two."),
    ).toBeInTheDocument();

    // And states the date rather than a change it cannot compute.
    expect(screen.getByText("Latest from 9 Oct 2026")).toBeInTheDocument();
  });

  it("explains the hollow dot when a point is dated by its upload", async () => {
    renderTrends();

    expect(await screen.findByText("Hollow dot: dated by upload")).toBeInTheDocument();
  });
});

describe("the table view", () => {
  beforeEach(() => {
    asUser();
    serve({
      markers: [{ marker: "ldl-cholesterol", displayName: "LDL Cholesterol", reportCount: 3 }],
      trend: {
        ...LDL,
        series: [
          {
            unit: "mg/dL",
            points: [
              point({ value: 148, date: "2026-03-14", refHigh: 100, flag: "HIGH" }),
              point({ value: 162, date: "2026-09-28", refLow: 50, refHigh: 130, flag: "HIGH" }),
            ],
          },
        ],
      },
    });
  });

  it("lists every value newest first, each with its own report's range", async () => {
    renderTrends();

    await userEvent.click(await screen.findByRole("tab", { name: "Table" }));

    const table = screen.getAllByRole("table")[0];
    const rows = within(table).getAllByRole("row").slice(1);

    expect(within(rows[0]).getByText("28 Sep 2026")).toBeInTheDocument();
    // Each row carries the range its own report printed, which the band cannot show.
    expect(within(rows[0]).getByText("50-130")).toBeInTheDocument();
    expect(within(rows[1]).getByText("14 Mar 2026")).toBeInTheDocument();
    expect(within(rows[1]).getByText("<100")).toBeInTheDocument();
  });

  it("links each value back to the report it came from", async () => {
    renderTrends();
    await userEvent.click(await screen.findByRole("tab", { name: "Table" }));

    const table = screen.getAllByRole("table")[0];
    expect(within(table).getAllByRole("link", { name: "Open" })[0]).toHaveAttribute(
      "href",
      "/reports/r1",
    );
  });
});

describe("choosing a marker", () => {
  it("puts it in the URL so the view can be linked and gone back to", async () => {
    asUser();
    serve({
      markers: [
        { marker: "ldl-cholesterol", displayName: "LDL Cholesterol", reportCount: 3 },
        { marker: "tsh", displayName: "TSH", reportCount: 2 },
      ],
      trend: LDL,
    });

    renderTrends();

    await userEvent.click(await screen.findByRole("button", { name: /TSH/ }));
    expect(push).toHaveBeenCalledWith("/trends?marker=tsh", { scroll: false });
  });

  it("loads a marker named in the URL even when it is not in the list", async () => {
    asUser();
    search = new URLSearchParams("marker=ferritin");
    serve({ markers: [], trend: { marker: "ferritin", displayName: "Ferritin", series: [] } });

    renderTrends();

    // A marker with a single point never reaches the list, but a link from a biomarker page
    // still has to open something rather than the "nothing to compare" screen.
    await waitFor(() =>
      expect(screen.queryByText("Nothing to compare yet.")).not.toBeInTheDocument(),
    );
    expect(await screen.findByText("No numeric values for this test yet.")).toBeInTheDocument();
  });
});
