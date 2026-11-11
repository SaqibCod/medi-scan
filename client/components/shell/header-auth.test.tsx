import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { NoticeProvider } from "@/components/notices/notice-provider";
import { ToastProvider } from "@/components/notices/toaster";
import { Header } from "@/components/shell/header";
import { AuthProvider } from "@/lib/auth/provider";
import { resetBackendStatusForTests } from "@/lib/backend-status";
import { resetAuthChannelForTests } from "@/lib/auth/channel";
import { resetGoogleForTests } from "@/lib/auth/google";
import { resetRefreshForTests } from "@/lib/auth/refresh";
import { REFRESH_STORAGE_KEY, resetTokensForTests } from "@/lib/auth/tokens";
import { resetGuestSessionForTests } from "@/lib/guest-session";
import { createQueryClient } from "@/lib/query-client";

/**
 * The header's one changing slot, and the nav beside it (LLD 3).
 *
 * `CAN_SIGN_IN` is a build-time constant, so it is stubbed rather than driven by the
 * environment: a test that only passes when a developer happens to have a client id in their
 * `.env.local` is not a test.
 */

vi.mock("@/lib/env", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/env")>()),
  CAN_SIGN_IN: true,
  FEATURE_SIGNIN: true,
  GOOGLE_CLIENT_ID: "test-client-id.apps.googleusercontent.com",
}));

function tokenResponse() {
  return {
    accessToken: "access-1",
    accessTokenExpiresAt: new Date(Date.now() + 900_000).toISOString(),
    refreshToken: "refresh-1",
    refreshTokenExpiresAt: new Date(Date.now() + 7 * 86_400_000).toISOString(),
    user: { id: "u1", displayName: "Saq", role: "USER", createdAt: "2026-10-02T09:30:00Z" },
  };
}

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function installFakeGoogle() {
  window.google = {
    accounts: {
      id: {
        initialize() {},
        prompt() {},
        renderButton(parent: HTMLElement) {
          // Stands in for the iframe GIS injects, so the slot is no longer empty.
          const button = document.createElement("button");
          button.textContent = "Sign in with Google";
          parent.appendChild(button);
        },
        disableAutoSelect() {},
      },
    },
  };
}

function renderHeader() {
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <NoticeProvider>
        <ToastProvider>
          <AuthProvider clientId="test-client-id.apps.googleusercontent.com">
            <Header />
          </AuthProvider>
        </ToastProvider>
      </NoticeProvider>
    </QueryClientProvider>,
  );
}

let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  resetTokensForTests();
  resetRefreshForTests();
  resetGuestSessionForTests();
  resetGoogleForTests();
  resetAuthChannelForTests();
  resetBackendStatusForTests();
  installFakeGoogle();
  // Routed by URL, and building a fresh Response each time: a Response body can only be
  // read once, and the header's health check would otherwise drink the refresh's.
  fetchMock = vi.fn((url: string) =>
    Promise.resolve(
      String(url).includes("/actuator/health")
        ? json(200, { status: "UP" })
        : json(200, tokenResponse()),
    ),
  );
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  resetAuthChannelForTests();
  delete window.google;
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

function nav() {
  return screen.getByRole("navigation", { name: "Main" });
}

describe("a guest", () => {
  it("is offered Google's own button, not one of ours", async () => {
    renderHeader();

    const button = await screen.findByRole("button", { name: "Sign in with Google" });
    expect(button).toBeInTheDocument();

    // No account pill: nobody is signed in.
    expect(screen.queryByRole("link", { name: /your account/ })).not.toBeInTheDocument();
  });

  it("is not offered Trends, which would turn them away", async () => {
    renderHeader();
    await screen.findByRole("button", { name: "Sign in with Google" });

    expect(within(nav()).queryByRole("link", { name: "Trends" })).not.toBeInTheDocument();
    // My reports is theirs too, though: a guest has reports (LLD A6).
    expect(within(nav()).getByRole("link", { name: "My reports" })).toBeInTheDocument();
  });
});

describe("a signed-in visitor", () => {
  beforeEach(() => {
    window.sessionStorage.setItem(REFRESH_STORAGE_KEY, "refresh-0");
  });

  it("gets the account pill, with their initial, linking to the account page", async () => {
    renderHeader();

    const pill = await screen.findByRole("link", { name: /your account/ });
    expect(pill).toHaveAttribute("href", "/account");
    expect(pill).toHaveTextContent("S");
    expect(pill).toHaveTextContent("Saq");

    // And the sign-in button is gone, not merely hidden behind it.
    expect(screen.queryByRole("button", { name: "Sign in with Google" })).not.toBeInTheDocument();
  });

  it("gets the Trends link", async () => {
    renderHeader();
    await screen.findByRole("link", { name: /your account/ });

    expect(within(nav()).getByRole("link", { name: "Trends" })).toHaveAttribute("href", "/trends");
  });
});

describe("while the answer is still unknown", () => {
  it("holds a neutral shape of the same size rather than guessing", async () => {
    window.sessionStorage.setItem(REFRESH_STORAGE_KEY, "refresh-0");
    // A refresh that never answers: the tab stays RESOLVING.
    fetchMock.mockImplementation((url: string) =>
      String(url).includes("/actuator/health")
        ? Promise.resolve(json(200, { status: "UP" }))
        : new Promise(() => {}),
    );

    const { container } = renderHeader();

    await waitFor(() => expect(container.querySelector(".ms-skel")).toBeInTheDocument());

    // Neither claim is made while it is still being decided.
    expect(screen.queryByRole("button", { name: "Sign in with Google" })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /your account/ })).not.toBeInTheDocument();

    // And it is scenery, not something to announce or tab to.
    expect(container.querySelector(".ms-skel")).toHaveAttribute("aria-hidden", "true");
  });
});
