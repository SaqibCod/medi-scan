import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { NoticeProvider } from "@/components/notices/notice-provider";
import { ToastProvider } from "@/components/notices/toaster";
import { ReportsView } from "@/components/reports/reports-view";
import type { ReportListItem } from "@/lib/api-types";
import { AuthProvider } from "@/lib/auth/provider";
import { resetAuthChannelForTests } from "@/lib/auth/channel";
import { resetGoogleForTests } from "@/lib/auth/google";
import { resetRefreshForTests } from "@/lib/auth/refresh";
import { REFRESH_STORAGE_KEY, resetTokensForTests } from "@/lib/auth/tokens";
import { resetBackendStatusForTests } from "@/lib/backend-status";
import { GUEST_STORAGE_KEY, resetGuestSessionForTests } from "@/lib/guest-session";
import { createQueryClient } from "@/lib/query-client";

/**
 * My reports (LLD 10, unit test 8).
 *
 * Everything the server could say is stated by the test rather than guessed at, which is what
 * makes the awkward rows reachable: a report that failed, one still being read, one with no
 * collection date, a guest's and a user's view of the same expiry.
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

const DAY = 86_400_000;

function item(over: Partial<ReportListItem> & Pick<ReportListItem, "id">): ReportListItem {
  return {
    status: "DONE",
    sourceType: "PDF",
    createdAt: "2026-10-12T09:31:00Z",
    expiresAt: new Date(Date.now() + 30 * DAY).toISOString(),
    collectedOn: "2026-09-28",
    counts: { total: 5, low: 0, normal: 3, high: 2, unknown: 0 },
    errorCode: null,
    ...over,
  };
}

function page(items: ReportListItem[], over: Record<string, unknown> = {}) {
  return { items, page: 0, size: 6, totalItems: items.length, totalPages: 1, ...over };
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
    refreshTokenExpiresAt: new Date(Date.now() + 7 * DAY).toISOString(),
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

/** Every list request the page makes, in one place, answered by status. */
function serveReports(byStatus: Record<string, ReportListItem[]>, markers: unknown[] = []) {
  fetchMock.mockImplementation((url: string) => {
    const target = String(url);

    if (target.includes("/actuator/health")) return Promise.resolve(json(200, { status: "UP" }));
    if (target.includes("/api/auth/refresh")) return Promise.resolve(json(200, tokenResponse()));
    if (target.includes("/api/trends/markers")) return Promise.resolve(json(200, markers));
    if (target.includes("/api/trends")) {
      return Promise.resolve(json(200, { marker: "m", displayName: "M", series: [] }));
    }

    if (target.includes("/api/reports")) {
      const status = new URL(target).searchParams.get("status") ?? "all";
      return Promise.resolve(json(200, page(byStatus[status] ?? [])));
    }

    return Promise.resolve(json(200, {}));
  });
}

function renderReports() {
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <NoticeProvider>
        <ToastProvider>
          <AuthProvider clientId="test-client-id.apps.googleusercontent.com">
            <ReportsView />
          </AuthProvider>
        </ToastProvider>
      </NoticeProvider>
    </QueryClientProvider>,
  );
}

function asGuest() {
  window.sessionStorage.setItem(
    GUEST_STORAGE_KEY,
    JSON.stringify({ token: "guest-abc", expiresAt: new Date(Date.now() + DAY).toISOString() }),
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
  serveReports({});
});

afterEach(() => {
  resetAuthChannelForTests();
  delete window.google;
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("rows", () => {
  it("dates a report by collection when it has one, and says so", async () => {
    asUser();
    serveReports({ all: [item({ id: "r1", collectedOn: "2026-09-28" })] });

    renderReports();

    const row = await screen.findByRole("listitem");
    expect(within(row).getByText("28")).toBeInTheDocument();
    expect(within(row).getByText("SEP 2026")).toBeInTheDocument();
    expect(within(row).getByText("COLLECTED")).toBeInTheDocument();
  });

  it("falls back to the upload date, and says that instead", async () => {
    asUser();
    serveReports({ all: [item({ id: "r1", collectedOn: null })] });

    renderReports();

    const row = await screen.findByRole("listitem");
    expect(within(row).getByText("UPLOADED")).toBeInTheDocument();
  });

  it("names each kind of report by what it turned out to be", async () => {
    asUser();
    serveReports({
      all: [
        item({ id: "r1", sourceType: "SAMPLE" }),
        item({ id: "r2", status: "PROCESSING", counts: null, collectedOn: null }),
        item({ id: "r3", status: "FAILED", counts: null, errorCode: "UNREADABLE", collectedOn: null }),
        item({ id: "r4", sourceType: "TEXT" }),
      ],
    });

    renderReports();

    await screen.findByText("Sample report");

    // Scoped to the list: "New report" is also the button in the page header.
    const list = screen.getByRole("list");
    expect(within(list).getByText("Sample report")).toBeInTheDocument();
    expect(within(list).getByText("New report")).toBeInTheDocument();
    expect(within(list).getByText("Report could not be read")).toBeInTheDocument();
    expect(within(list).getByText("Lab report")).toBeInTheDocument();

    // The source tag, which is what tells a PDF from a photo from pasted text.
    expect(within(list).getByText("Sample")).toBeInTheDocument();
    expect(within(list).getByText("Pasted text")).toBeInTheDocument();
  });

  it("says what a failed report failed at, in one line", async () => {
    asUser();
    serveReports({
      all: [item({ id: "r1", status: "FAILED", counts: null, errorCode: "UNREADABLE" })],
    });

    renderReports();

    expect(await screen.findByText("Couldn't read text from this file")).toBeInTheDocument();
  });

  it("falls back to a general line for a failure code it has never heard of", async () => {
    asUser();
    serveReports({
      all: [item({ id: "r1", status: "FAILED", counts: null, errorCode: "SOMETHING_NEW" })],
    });

    renderReports();

    expect(await screen.findByText("We couldn't finish reading this report")).toBeInTheDocument();
  });

  it("says a processing report is being read, and claims no progress", async () => {
    asUser();
    serveReports({ all: [item({ id: "r1", status: "PENDING", counts: null })] });

    renderReports();

    expect(await screen.findByText("Reading your report…")).toBeInTheDocument();
  });
});

describe("the flag bar", () => {
  it("spells the counts out, because colour alone is never the message", async () => {
    asUser();
    serveReports({
      all: [item({ id: "r1", counts: { total: 5, low: 1, normal: 2, high: 2, unknown: 0 } })],
    });

    renderReports();

    expect(await screen.findByText("5 tests · 2 high · 1 low · 2 normal")).toBeInTheDocument();
  });

  it("omits a flag nothing carries", async () => {
    asUser();
    serveReports({
      all: [item({ id: "r1", counts: { total: 3, low: 0, normal: 3, high: 0, unknown: 0 } })],
    });

    renderReports();

    const label = await screen.findByText(/3 tests/);
    expect(label).toHaveTextContent("3 tests · 3 normal");
    expect(label).not.toHaveTextContent("high");
  });

  it("counts values it could not check, so the numbers add up", async () => {
    asUser();
    serveReports({
      all: [item({ id: "r1", counts: { total: 4, low: 0, normal: 2, high: 1, unknown: 1 } })],
    });

    renderReports();

    expect(await screen.findByText("4 tests · 1 high · 2 normal · 1 unchecked")).toBeInTheDocument();
  });

  it("sizes the segments by their counts", async () => {
    asUser();
    serveReports({
      all: [item({ id: "r1", counts: { total: 10, low: 0, normal: 8, high: 2, unknown: 0 } })],
    });

    const { container } = renderReports();
    await screen.findByText(/10 tests/);

    const segments = container.querySelectorAll<HTMLElement>(".ms-flagbar__seg");
    expect(segments).toHaveLength(2);
    expect(segments[0].style.flexGrow).toBe("2");
    expect(segments[1].style.flexGrow).toBe("8");
  });
});

describe("how long a report has left", () => {
  it("tells a signed-in reader in days", async () => {
    asUser();
    serveReports({
      // A margin, because every countdown floors: see `formatDeletedIn`, which never
      // promises time the report does not have.
      all: [item({ id: "r1", expiresAt: new Date(Date.now() + 30 * DAY + 60_000).toISOString() })],
    });

    renderReports();

    expect(await screen.findByText("Expires in 30 days")).toBeInTheDocument();
  });

  it("tells a guest in hours, and in the warning colour", async () => {
    asGuest();
    serveReports({
      all: [item({ id: "r1", expiresAt: new Date(Date.now() + 23 * 3_600_000 + 60_000).toISOString() })],
    });

    renderReports();

    const left = await screen.findByText("Deleted in 23 h");
    expect(left).toHaveClass("text-flag-high-fg");
  });
});

describe("the tabs", () => {
  it("carry a count each, with Processing covering both unfinished statuses", async () => {
    asUser();
    serveReports({
      all: Array.from({ length: 14 }, (_, i) => item({ id: `a${i}` })),
      DONE: Array.from({ length: 11 }, (_, i) => item({ id: `d${i}` })),
      PENDING: [item({ id: "p1" })],
      PROCESSING: [item({ id: "p2" })],
      FAILED: [item({ id: "f1" }), item({ id: "f2" })],
    });

    renderReports();

    expect(await screen.findByRole("tab", { name: "All 14" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Done 11" })).toBeInTheDocument();
    // One PENDING plus one PROCESSING: the tab says two, not one.
    expect(screen.getByRole("tab", { name: "Processing 2" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Failed 2" })).toBeInTheDocument();
  });

  it("put the chosen tab in the URL and return to the first page", async () => {
    asUser();
    search = new URLSearchParams("page=2");
    serveReports({ all: [item({ id: "r1" })], FAILED: [] });

    renderReports();
    await screen.findByRole("tab", { name: /All/ });

    await userEvent.click(screen.getByRole("tab", { name: /Failed/ }));

    // Page 2 of "All" is not page 2 of "Failed", so the page resets rather than landing empty.
    expect(push).toHaveBeenCalledWith("/reports?status=FAILED", { scroll: false });
  });

  it("merge both unfinished statuses into one list", async () => {
    asUser();
    search = new URLSearchParams("status=processing");
    serveReports({
      PENDING: [
        item({ id: "p1", status: "PENDING", counts: null, collectedOn: null, createdAt: "2026-10-01T10:00:00Z" }),
      ],
      PROCESSING: [
        item({ id: "p2", status: "PROCESSING", counts: null, collectedOn: null, createdAt: "2026-10-02T10:00:00Z" }),
      ],
    });

    renderReports();

    await waitFor(() => expect(screen.getAllByRole("listitem")).toHaveLength(2));

    // Newest first, across the two lists rather than within each.
    const rows = screen.getAllByRole("listitem");
    expect(within(rows[0]).getByText("2")).toBeInTheDocument();
    expect(within(rows[1]).getByText("1")).toBeInTheDocument();
  });
});

describe("pagination", () => {
  it("puts the page in the URL", async () => {
    asUser();
    serveReports({ all: Array.from({ length: 6 }, (_, i) => item({ id: `r${i}` })) });
    fetchMock.mockImplementation((url: string) => {
      const target = String(url);
      if (target.includes("/actuator/health")) return Promise.resolve(json(200, { status: "UP" }));
      if (target.includes("/api/auth/refresh")) return Promise.resolve(json(200, tokenResponse()));
      if (target.includes("/api/trends")) return Promise.resolve(json(200, []));
      return Promise.resolve(
        json(200, page(Array.from({ length: 6 }, (_, i) => item({ id: `r${i}` })), {
          totalItems: 14,
          totalPages: 3,
        })),
      );
    });

    renderReports();

    await userEvent.click(await screen.findByRole("button", { name: "2" }));
    expect(push).toHaveBeenCalledWith("/reports?page=1", { scroll: false });
  });

  it("says which slice is on screen", async () => {
    asUser();
    fetchMock.mockImplementation((url: string) => {
      const target = String(url);
      if (target.includes("/actuator/health")) return Promise.resolve(json(200, { status: "UP" }));
      if (target.includes("/api/auth/refresh")) return Promise.resolve(json(200, tokenResponse()));
      if (target.includes("/api/trends")) return Promise.resolve(json(200, []));
      return Promise.resolve(
        json(200, page(Array.from({ length: 6 }, (_, i) => item({ id: `r${i}` })), {
          totalItems: 14,
          totalPages: 3,
        })),
      );
    });

    renderReports();

    expect(await screen.findByText("Showing 1–6 of 14")).toBeInTheDocument();
  });
});

describe("a guest", () => {
  it("with no session asks the server nothing and is shown the empty state", async () => {
    // No ms.guest, no ms.refresh: nothing to list, and asking would create a session.
    renderReports();

    expect(await screen.findByText("No reports yet.")).toBeInTheDocument();

    const listCalls = fetchMock.mock.calls.filter((call) => String(call[0]).includes("/api/reports"));
    expect(listCalls).toHaveLength(0);
    const sessionCalls = fetchMock.mock.calls.filter((call) => String(call[0]).includes("/api/sessions"));
    expect(sessionCalls).toHaveLength(0);
  });

  it("with reports is offered the way to keep them", async () => {
    asGuest();
    serveReports({ all: [item({ id: "r1" })] });

    renderReports();

    expect(await screen.findByText(/live only in this browser tab/)).toBeInTheDocument();
    expect(screen.getByText(/deleted automatically 24 hours after your session started/)).toBeInTheDocument();
  });

  it("is not shown the trends teaser, having nothing to compare", async () => {
    asGuest();
    serveReports({ all: [item({ id: "r1" })] }, [
      { marker: "ldl-cholesterol", displayName: "LDL Cholesterol", reportCount: 3 },
    ]);

    renderReports();
    await screen.findByText("Lab report");

    expect(screen.queryByText(/appears? in more than one report/)).not.toBeInTheDocument();
  });
});

describe("a signed-in reader", () => {
  it("is shown the trends teaser once a marker repeats", async () => {
    asUser();
    serveReports({ all: [item({ id: "r1" })] }, [
      { marker: "ldl-cholesterol", displayName: "LDL Cholesterol", reportCount: 3 },
      { marker: "tsh", displayName: "TSH", reportCount: 2 },
    ]);

    renderReports();

    expect(await screen.findByText("2 markers appear in more than one report.")).toBeInTheDocument();
    expect(screen.getByText("LDL Cholesterol")).toBeInTheDocument();
  });

  it("sees the 30-day footer rather than the guest one", async () => {
    asUser();
    serveReports({ all: [item({ id: "r1" })] });

    renderReports();

    expect(
      await screen.findByText(
        "Reports are deleted automatically 30 days after upload. Chats are never saved.",
      ),
    ).toBeInTheDocument();
  });
});
