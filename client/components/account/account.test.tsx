import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { AccountView } from "@/components/account/account-view";
import { NoticeProvider } from "@/components/notices/notice-provider";
import { ToastProvider } from "@/components/notices/toaster";
import { AuthProvider } from "@/lib/auth/provider";
import { resetAuthChannelForTests } from "@/lib/auth/channel";
import { resetGoogleForTests } from "@/lib/auth/google";
import { resetRefreshForTests } from "@/lib/auth/refresh";
import { REFRESH_STORAGE_KEY, resetTokensForTests } from "@/lib/auth/tokens";
import { resetBackendStatusForTests } from "@/lib/backend-status";
import { resetGuestSessionForTests } from "@/lib/guest-session";
import { createQueryClient } from "@/lib/query-client";

/**
 * The account page (LLD 10, unit test 10).
 *
 * The two things worth testing are both about consequence: that deleting is gated behind a
 * deliberate act, and that succeeding at it leaves nothing behind.
 */

const push = vi.fn();

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push, replace: push, prefetch: () => {} }),
  useSearchParams: () => new URLSearchParams(),
}));

vi.mock("@/lib/env", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/env")>()),
  CAN_SIGN_IN: true,
  FEATURE_SIGNIN: true,
  GOOGLE_CLIENT_ID: "test-client-id.apps.googleusercontent.com",
}));

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

function serve(options: { reportCount?: number; deleteStatus?: number } = {}) {
  fetchMock.mockImplementation((url: string, init?: RequestInit) => {
    const target = String(url);

    if (target.includes("/actuator/health")) return Promise.resolve(json(200, { status: "UP" }));
    if (target.includes("/api/auth/refresh")) return Promise.resolve(json(200, tokenResponse()));
    if (target.includes("/api/auth/logout")) return Promise.resolve(new Response(null, { status: 204 }));
    if (target.includes("/api/trends/markers")) {
      return Promise.resolve(json(200, [{ marker: "tsh", displayName: "TSH", reportCount: 2 }]));
    }
    if (target.includes("/api/me")) {
      if (init?.method === "DELETE") {
        return Promise.resolve(new Response(null, { status: options.deleteStatus ?? 204 }));
      }
      return Promise.resolve(
        json(200, {
          id: "u1",
          displayName: "Saq",
          role: "USER",
          createdAt: "2026-10-02T09:30:00Z",
          reportCount: options.reportCount ?? 4,
        }),
      );
    }

    return Promise.resolve(json(200, {}));
  });
}

function renderAccount() {
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <NoticeProvider>
        <ToastProvider>
          <AuthProvider clientId="test-client-id.apps.googleusercontent.com">
            <AccountView />
          </AuthProvider>
        </ToastProvider>
      </NoticeProvider>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  push.mockClear();
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

function asUser() {
  window.sessionStorage.setItem(REFRESH_STORAGE_KEY, "refresh-0");
}

describe("not signed in", () => {
  it("offers sign-in rather than an empty account", async () => {
    renderAccount();

    expect(await screen.findByText("Sign in to see")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Sign in with Google" })).toBeInTheDocument();
    // Guests are a first-class way to use this, not a dead end.
    expect(screen.getByRole("link", { name: "Continue as guest" })).toHaveAttribute("href", "/");
  });

  it("claims nothing while the answer is still unknown", async () => {
    asUser();
    fetchMock.mockImplementation((url: string) =>
      String(url).includes("/actuator/health")
        ? Promise.resolve(json(200, { status: "UP" }))
        : new Promise(() => {}),
    );

    renderAccount();

    // Neither the account nor the sign-in card: the page says nothing it does not know.
    await waitFor(() =>
      expect(document.querySelector(".ms-skel")).toBeInTheDocument(),
    );
    expect(screen.queryByText("Sign in to see")).not.toBeInTheDocument();
  });
});

describe("signed in", () => {
  beforeEach(asUser);

  it("shows the profile, the counts, and what is kept", async () => {
    renderAccount();

    expect(await screen.findByText("Signed in as Saq.")).toBeInTheDocument();
    expect(screen.getByText("Google account")).toBeInTheDocument();
    expect(screen.getByText("What Medi-Scan keeps about you")).toBeInTheDocument();

    // The count appears twice on purpose — as a fact in the profile and as a way into the
    // list — so each is asserted where it lives rather than by its text alone.
    const facts = await screen.findAllByRole("definition");
    expect(facts.map((fact) => fact.textContent)).toEqual(["2 Oct 2026", "4"]);

    const reportsCard = screen.getByRole("link", { name: /My reports, each kept for 30 days/ });
    expect(reportsCard).toHaveTextContent("4");
    expect(reportsCard).toHaveAttribute("href", "/reports");

    const trendsCard = screen.getByRole("link", { name: /Markers you can follow on Trends/ });
    expect(trendsCard).toHaveTextContent("1");
  });

  it("names the account id among what it keeps, not just the display name", async () => {
    renderAccount();

    // SECURITY.md records that an account is Google's `sub`, a name, a role and timestamps.
    // The card has to say so.
    expect(await screen.findByText("Your Google name and account id")).toBeInTheDocument();
    expect(screen.getByText(/never see your Google password, your email address or your photo/)).toBeInTheDocument();
  });

  it("says that signing out reaches every tab", async () => {
    renderAccount();

    expect(
      await screen.findByText("Signs you out in every open tab of this browser."),
    ).toBeInTheDocument();
  });
});

describe("deleting the account", () => {
  beforeEach(asUser);

  it("asks again, with the number, before it will do anything", async () => {
    renderAccount();

    await userEvent.click(await screen.findByRole("button", { name: /Delete account/ }));

    expect(screen.getByText("Delete your account and all 4 of your reports for good?")).toBeInTheDocument();
    // And the button is inert until the box is ticked.
    expect(screen.getByRole("button", { name: /Delete my account/ })).toBeDisabled();
  });

  it("counts one report in the singular", async () => {
    serve({ reportCount: 1 });
    renderAccount();

    await userEvent.click(await screen.findByRole("button", { name: /Delete account/ }));

    expect(screen.getByText("Delete your account and your 1 report for good?")).toBeInTheDocument();
  });

  it("stays shut until the checkbox is ticked", async () => {
    renderAccount();

    await userEvent.click(await screen.findByRole("button", { name: /Delete account/ }));
    await userEvent.click(screen.getByRole("checkbox"));

    expect(screen.getByRole("button", { name: /Delete my account/ })).toBeEnabled();

    const deleteCalls = fetchMock.mock.calls.filter(
      (call) => String(call[0]).includes("/api/me") && (call[1] as RequestInit)?.method === "DELETE",
    );
    expect(deleteCalls).toHaveLength(0);
  });

  it("clears every token and tells the other tabs", async () => {
    renderAccount();

    await userEvent.click(await screen.findByRole("button", { name: /Delete account/ }));
    await userEvent.click(screen.getByRole("checkbox"));
    await userEvent.click(screen.getByRole("button", { name: /Delete my account/ }));

    expect(await screen.findByText("Account deleted.")).toBeInTheDocument();
    expect(window.sessionStorage.getItem(REFRESH_STORAGE_KEY)).toBeNull();

    // And it stays put, so the page can show its signed-out state in place (LLD 10, e2e 5).
    expect(push).not.toHaveBeenCalled();
    expect(await screen.findByText("Sign in to see")).toBeInTheDocument();
  });

  it("keeps the account, and says so, when the delete fails", async () => {
    serve({ deleteStatus: 500 });
    renderAccount();

    await userEvent.click(await screen.findByRole("button", { name: /Delete account/ }));
    await userEvent.click(screen.getByRole("checkbox"));
    await userEvent.click(screen.getByRole("button", { name: /Delete my account/ }));

    expect(await screen.findByText(/Your account is unchanged/)).toBeInTheDocument();
    // Still signed in, and the panel is still open rather than closing on a lie.
    expect(screen.getByRole("button", { name: /Delete my account/ })).toBeInTheDocument();
    expect(window.sessionStorage.getItem(REFRESH_STORAGE_KEY)).toBe("refresh-1");
  });

  it("can be called off", async () => {
    renderAccount();

    await userEvent.click(await screen.findByRole("button", { name: /Delete account/ }));
    await userEvent.click(screen.getByRole("button", { name: "Keep my account" }));

    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Delete account/ })).toBeInTheDocument();
  });
});
