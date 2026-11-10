import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

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
import {
  ForbiddenScreen,
  GuestSessionEndedScreen,
  ReportNotAvailableScreen,
} from "./screens";

/**
 * The sign-in controls 7a and 7b left out of the system screens (LLD 3, "Unhidden now").
 *
 * The sibling spec covers the unconfigured build, where they are all absent. This one covers
 * the build that ships.
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
          const button = document.createElement("button");
          button.textContent = "Sign in with Google";
          parent.appendChild(button);
        },
        disableAutoSelect() {},
      },
    },
  };
}

function renderScreen(ui: React.ReactElement) {
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <NoticeProvider>
        <ToastProvider>
          <AuthProvider clientId="test-client-id.apps.googleusercontent.com">{ui}</AuthProvider>
        </ToastProvider>
      </NoticeProvider>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  resetTokensForTests();
  resetRefreshForTests();
  resetGuestSessionForTests();
  resetGoogleForTests();
  resetAuthChannelForTests();
  resetBackendStatusForTests();
  installFakeGoogle();
  vi.stubGlobal(
    "fetch",
    vi.fn((url: string) =>
      Promise.resolve(
        String(url).includes("/actuator/health")
          ? json(200, { status: "UP" })
          : json(200, tokenResponse()),
      ),
    ),
  );
});

afterEach(() => {
  resetAuthChannelForTests();
  delete window.google;
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("a guest who has hit a wall", () => {
  it("is offered sign-in on 'Report not available', the likeliest cause", async () => {
    renderScreen(<ReportNotAvailableScreen />);

    expect(await screen.findByRole("button", { name: "Sign in with Google" })).toBeInTheDocument();
    // The other two ways out are still there.
    expect(screen.getByRole("link", { name: /Analyze a report/ })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "My reports" })).toBeInTheDocument();
  });

  it("is offered sign-in on 'Guest session ended', which the copy already suggests", async () => {
    renderScreen(<GuestSessionEndedScreen />);

    expect(await screen.findByRole("button", { name: "Sign in with Google" })).toBeInTheDocument();
  });

  it("is not shown an account link on the admin screen, having no account", async () => {
    renderScreen(<ForbiddenScreen />);

    expect(screen.queryByRole("link", { name: "Your account" })).not.toBeInTheDocument();
  });
});

describe("a signed-in visitor", () => {
  beforeEach(() => {
    window.sessionStorage.setItem(REFRESH_STORAGE_KEY, "refresh-0");
  });

  it("is pointed at their account from the admins-only screen", async () => {
    renderScreen(<ForbiddenScreen />);

    const link = await screen.findByRole("link", { name: "Your account" });
    expect(link).toHaveAttribute("href", "/account");
  });

  it("is not asked to sign in again on a report they cannot see", async () => {
    renderScreen(<ReportNotAvailableScreen />);

    // Give the refresh time to land, then confirm the offer never appears.
    expect(await screen.findByRole("link", { name: "My reports" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Sign in with Google" })).not.toBeInTheDocument();
  });
});
