import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ToastProvider } from "@/components/notices/toaster";
import { GUEST_STORAGE_KEY, resetGuestSessionForTests } from "../guest-session";
import { createQueryClient } from "../query-client";
import { resetSignInGate } from "../sign-in-gate";
import { createAuthChannel, resetAuthChannelForTests, type AuthChannel } from "./channel";
import { resetGoogleForTests, type GoogleCredentialResponse } from "./google";
import { AuthProvider, useAuth } from "./provider";
import { resetRefreshForTests } from "./refresh";
import { REFRESH_STORAGE_KEY, resetTokensForTests } from "./tokens";

/**
 * Tab start, signing in, and signing out everywhere (LLD 10, unit tests 1, 2, 4 and 6).
 *
 * Google is a fake object on `window`, which is all the real code ever touches: it asks
 * `window.google.accounts.id` for a credential and hands whatever comes back to the server.
 * Nothing here contacts Google, and nothing needs to.
 */

const pushMock = vi.fn();

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock, replace: pushMock, prefetch: () => {} }),
}));

const CLIENT_ID = "test-client-id.apps.googleusercontent.com";

function tokenResponse(extra: Record<string, unknown> = {}) {
  return {
    accessToken: "access-1",
    accessTokenExpiresAt: new Date(Date.now() + 900_000).toISOString(),
    refreshToken: "refresh-1",
    refreshTokenExpiresAt: new Date(Date.now() + 7 * 86_400_000).toISOString(),
    user: { id: "u1", displayName: "Saq", role: "USER", createdAt: "2026-10-02T09:30:00Z" },
    ...extra,
  };
}

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function problem(status: number, code: string) {
  return new Response(
    JSON.stringify({ type: "about:blank", title: code, status, detail: "", code, requestId: "r1" }),
    { status, headers: { "Content-Type": "application/problem+json" } },
  );
}

/** The slice of GIS the app uses, recorded so tests can drive the callback. */
function installFakeGoogle() {
  const calls = { initialize: 0, prompt: 0, renderButton: 0, disableAutoSelect: 0 };
  let callback: ((response: GoogleCredentialResponse) => void) | null = null;

  window.google = {
    accounts: {
      id: {
        initialize(config) {
          calls.initialize += 1;
          callback = config.callback;
        },
        prompt() {
          calls.prompt += 1;
        },
        renderButton() {
          calls.renderButton += 1;
        },
        disableAutoSelect() {
          calls.disableAutoSelect += 1;
        },
      },
    },
  };

  return {
    calls,
    /** Hands the page a credential, as Google would from One Tap or the button. */
    deliver(idToken: string) {
      callback?.({ credential: idToken });
    },
  };
}

function Probe() {
  const { state, waiting, user } = useAuth();
  return (
    <div>
      <span data-testid="status">{state.status}</span>
      <span data-testid="waiting">{String(waiting)}</span>
      <span data-testid="user">{user?.displayName ?? "-"}</span>
    </div>
  );
}

/** `clientId: null` stands for a build with the variable unset. */
function renderAuth(options: { clientId?: string | null } = {}) {
  const clientId = options.clientId === null ? undefined : (options.clientId ?? CLIENT_ID);
  const client = createQueryClient();
  return render(
    <QueryClientProvider client={client}>
      <ToastProvider>
        <AuthProvider clientId={clientId}>
          <Probe />
        </AuthProvider>
      </ToastProvider>
    </QueryClientProvider>,
  );
}

function status(): string {
  return screen.getByTestId("status").textContent ?? "";
}

let fetchMock: ReturnType<typeof vi.fn>;
let extraChannels: AuthChannel[] = [];

function callsTo(path: string) {
  return fetchMock.mock.calls.filter((call) => String(call[0]).endsWith(path));
}

beforeEach(() => {
  pushMock.mockClear();
  resetTokensForTests();
  resetRefreshForTests();
  resetGuestSessionForTests();
  resetGoogleForTests();
  resetAuthChannelForTests();
  resetSignInGate();
  fetchMock = vi.fn();
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  extraChannels.forEach((channel) => channel.close());
  extraChannels = [];
  resetAuthChannelForTests();
  delete window.google;
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("tab start", () => {
  it("with a refresh token, refreshes and ends up signed in", async () => {
    installFakeGoogle();
    window.sessionStorage.setItem(REFRESH_STORAGE_KEY, "refresh-0");
    fetchMock.mockResolvedValue(json(200, tokenResponse()));

    renderAuth();

    await waitFor(() => expect(status()).toBe("SIGNED_IN"));
    expect(screen.getByTestId("user")).toHaveTextContent("Saq");
    expect(callsTo("/api/auth/refresh")).toHaveLength(1);
  });

  it("with a rejected refresh token, clears it and falls back to a guest", async () => {
    const google = installFakeGoogle();
    window.sessionStorage.setItem(REFRESH_STORAGE_KEY, "refresh-stale");
    fetchMock.mockResolvedValue(problem(401, "REFRESH_TOKEN_INVALID"));

    renderAuth();

    await waitFor(() => expect(status()).toBe("GUEST"));
    expect(window.sessionStorage.getItem(REFRESH_STORAGE_KEY)).toBeNull();

    // And it asks Google, because a rejected token says nothing about who this is.
    await waitFor(() => expect(google.calls.prompt).toBe(1));
  });

  it("with no token at all, becomes a guest and asks Google once", async () => {
    const google = installFakeGoogle();

    renderAuth();

    await waitFor(() => expect(status()).toBe("GUEST"));
    await waitFor(() => expect(google.calls.initialize).toBe(1));
    expect(google.calls.prompt).toBe(1);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("never shows a guest after a network failure, because that would be a lie", async () => {
    installFakeGoogle();
    window.sessionStorage.setItem(REFRESH_STORAGE_KEY, "refresh-0");
    fetchMock.mockRejectedValue(new TypeError("Failed to fetch"));

    renderAuth();

    await waitFor(() => expect(callsTo("/api/auth/refresh").length).toBeGreaterThan(0));
    await new Promise((done) => setTimeout(done, 60));

    // Still resolving, and the token is still there to try again with.
    expect(status()).toBe("RESOLVING");
    expect(window.sessionStorage.getItem(REFRESH_STORAGE_KEY)).toBe("refresh-0");
  });

  it("stops making pages wait once the two-second budget is spent", async () => {
    installFakeGoogle();
    window.sessionStorage.setItem(REFRESH_STORAGE_KEY, "refresh-0");
    fetchMock.mockImplementation(() => new Promise(() => {}));

    renderAuth();

    expect(screen.getByTestId("waiting")).toHaveTextContent("true");
    await waitFor(() => expect(screen.getByTestId("waiting")).toHaveTextContent("false"), {
      timeout: 4_000,
    });
    // The page renders, but nothing claims the visitor is signed out.
    expect(status()).toBe("RESOLVING");
  }, 6_000);

  it("does not load Google at all when no client id is configured", async () => {
    const google = installFakeGoogle();

    renderAuth({ clientId: null });

    await waitFor(() => expect(status()).toBe("GUEST"));
    expect(google.calls.initialize).toBe(0);
    expect(google.calls.prompt).toBe(0);
  });
});

describe("signing in", () => {
  it("sends the guest session token so the guest reports are claimed", async () => {
    const google = installFakeGoogle();
    window.sessionStorage.setItem(
      GUEST_STORAGE_KEY,
      JSON.stringify({ token: "guest-abc", expiresAt: new Date(Date.now() + 3_600_000).toISOString() }),
    );
    fetchMock.mockResolvedValue(json(200, tokenResponse({ claimedReportCount: 2, newUser: true })));

    renderAuth();
    await waitFor(() => expect(google.calls.initialize).toBe(1));

    google.deliver("google-id-token");

    await waitFor(() => expect(status()).toBe("SIGNED_IN"));

    const [, init] = callsTo("/api/auth/google")[0] as [string, RequestInit];
    expect(JSON.parse(String(init.body))).toEqual({
      idToken: "google-id-token",
      guestSessionToken: "guest-abc",
    });

    // The guest token goes: those reports belong to the account now.
    expect(window.sessionStorage.getItem(GUEST_STORAGE_KEY)).toBeNull();
  });

  it("stores the refresh token but never the Google ID token", async () => {
    const google = installFakeGoogle();
    fetchMock.mockResolvedValue(json(200, tokenResponse({ claimedReportCount: 0, newUser: false })));

    renderAuth();
    await waitFor(() => expect(google.calls.initialize).toBe(1));
    google.deliver("google-id-token-that-must-not-be-kept");
    await waitFor(() => expect(status()).toBe("SIGNED_IN"));

    expect(window.sessionStorage.getItem(REFRESH_STORAGE_KEY)).toBe("refresh-1");

    const everything = JSON.stringify({
      session: { ...window.sessionStorage },
      local: { ...window.localStorage },
    });
    expect(everything).not.toContain("google-id-token-that-must-not-be-kept");
    expect(everything).not.toContain("access-1");
  });

  it("welcomes a new user and says what moved", async () => {
    const google = installFakeGoogle();
    fetchMock.mockResolvedValue(json(200, tokenResponse({ claimedReportCount: 2, newUser: true })));

    renderAuth();
    await waitFor(() => expect(google.calls.initialize).toBe(1));
    google.deliver("id-token");

    expect(await screen.findByText("Welcome to Medi-Scan, Saq.")).toBeInTheDocument();
    expect(
      screen.getByText("Your 2 guest reports moved to your account. They are now kept for 30 days."),
    ).toBeInTheDocument();
  });

  it("greets a returning user, and counts one report in the singular", async () => {
    const google = installFakeGoogle();
    fetchMock.mockResolvedValue(json(200, tokenResponse({ claimedReportCount: 1, newUser: false })));

    renderAuth();
    await waitFor(() => expect(google.calls.initialize).toBe(1));
    google.deliver("id-token");

    expect(await screen.findByText("Signed in as Saq.")).toBeInTheDocument();
    expect(screen.getByText("1 report from this tab moved to your account.")).toBeInTheDocument();
  });

  it("says nothing about reports when nothing was claimed", async () => {
    const google = installFakeGoogle();
    fetchMock.mockResolvedValue(json(200, tokenResponse({ claimedReportCount: 0, newUser: false })));

    renderAuth();
    await waitFor(() => expect(google.calls.initialize).toBe(1));
    google.deliver("id-token");

    expect(await screen.findByText("Signed in as Saq.")).toBeInTheDocument();
    expect(screen.queryByText(/moved to your account/)).not.toBeInTheDocument();
  });

  it("stays a guest and says so when Google's token is rejected", async () => {
    const google = installFakeGoogle();
    fetchMock.mockResolvedValue(problem(401, "GOOGLE_TOKEN_INVALID"));

    renderAuth();
    await waitFor(() => expect(google.calls.initialize).toBe(1));
    google.deliver("id-token");

    expect(await screen.findByText("Sign-in didn't work. Please try again.")).toBeInTheDocument();
    expect(status()).toBe("GUEST");
    expect(window.sessionStorage.getItem(REFRESH_STORAGE_KEY)).toBeNull();
  });
});

describe("signing out everywhere", () => {
  it("each tab revokes its own token, and the message carries none", async () => {
    installFakeGoogle();
    window.sessionStorage.setItem(REFRESH_STORAGE_KEY, "refresh-0");
    fetchMock.mockResolvedValue(json(200, tokenResponse()));

    // A second tab, listening on the same channel.
    const otherTab = createAuthChannel();
    extraChannels.push(otherTab);
    const heard: unknown[] = [];
    otherTab.subscribe((message) => heard.push(message));

    function SignOutButton() {
      const { signOut } = useAuth();
      return (
        <button type="button" onClick={() => void signOut()}>
          Sign out
        </button>
      );
    }

    const client = createQueryClient();
    render(
      <QueryClientProvider client={client}>
        <ToastProvider>
          <AuthProvider clientId={CLIENT_ID}>
            <Probe />
            <SignOutButton />
          </AuthProvider>
        </ToastProvider>
      </QueryClientProvider>,
    );

    await waitFor(() => expect(status()).toBe("SIGNED_IN"));

    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    await userEvent.click(screen.getByRole("button", { name: "Sign out" }));

    await waitFor(() => expect(status()).toBe("GUEST"));

    // Its own family, with its own token, from its own storage.
    const [, init] = callsTo("/api/auth/logout")[0] as [string, RequestInit];
    expect(JSON.parse(String(init.body))).toEqual({ refreshToken: "refresh-1" });

    // The broadcast says only that it happened.
    expect(heard).toEqual([{ type: "logout" }]);
    expect(JSON.stringify(heard)).not.toContain("refresh-1");

    expect(window.sessionStorage.getItem(REFRESH_STORAGE_KEY)).toBeNull();
    expect(pushMock).toHaveBeenCalledWith("/");
    expect(await screen.findByText("Signed out.")).toBeInTheDocument();
  });

  it("a tab told to log out revokes the token it holds and becomes a guest", async () => {
    const google = installFakeGoogle();
    window.sessionStorage.setItem(REFRESH_STORAGE_KEY, "refresh-0");
    fetchMock.mockResolvedValue(json(200, tokenResponse()));

    renderAuth();
    await waitFor(() => expect(status()).toBe("SIGNED_IN"));

    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));

    // Another tab signs out.
    const otherTab = createAuthChannel();
    extraChannels.push(otherTab);
    otherTab.post({ type: "logout" });

    await waitFor(() => expect(status()).toBe("GUEST"));

    const [, init] = callsTo("/api/auth/logout")[0] as [string, RequestInit];
    expect(JSON.parse(String(init.body))).toEqual({ refreshToken: "refresh-1" });

    // Google must not sign it straight back in.
    expect(google.calls.disableAutoSelect).toBeGreaterThan(0);

    // It stays where it is: no navigation, so Account and Trends show their signed-out view.
    expect(pushMock).not.toHaveBeenCalled();
  });
});
