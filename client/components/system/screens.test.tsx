import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import { Providers } from "@/app/providers";
import {
  ForbiddenScreen,
  GuestSessionEndedScreen,
  NotFoundScreen,
  ReportNotAvailableScreen,
  ServerErrorScreen,
  ServerNotRespondingScreen,
} from "./screens";

function renderScreen(ui: React.ReactElement) {
  return render(<Providers>{ui}</Providers>);
}

describe("every system screen", () => {
  const screens = [
    ["404 page not found", <NotFoundScreen key="a" />, "404", "Page not found"],
    ["report not available", <ReportNotAvailableScreen key="b" />, "404", "Report not available"],
    ["guest session ended", <GuestSessionEndedScreen key="c" />, "401", "Guest session ended"],
    ["admins only", <ForbiddenScreen key="d" />, "403", "Admins only"],
    ["server error", <ServerErrorScreen key="e" />, "500", "Something went wrong"],
    ["server not responding", <ServerNotRespondingScreen key="f" />, "503", "Can't reach the server"],
  ] as const;

  it.each(screens)("%s has one heading and says what happened in words", (_name, ui, _numeral, eyebrow) => {
    renderScreen(ui);

    expect(screen.getByRole("heading", { level: 1 })).toBeInTheDocument();
    // The status also has to exist as words: the numeral is hidden from screen readers, so
    // the eyebrow is the only thing that explains the situation to them.
    expect(screen.getByText(eyebrow)).toBeInTheDocument();
  });

  it.each(screens)("%s hides its numeral from screen readers", (_name, ui, numeral) => {
    const { container } = renderScreen(ui);

    const figure = container.querySelector(".ms-sysnum");
    expect(figure).toHaveTextContent(numeral);
    // "503" read aloud is not an explanation.
    expect(figure).toHaveAttribute("aria-hidden", "true");
  });

  it.each(screens)("%s offers a way out", (_name, ui) => {
    renderScreen(ui);

    // A dead end with no action is the one thing an error screen must not be.
    const ways = [...screen.queryAllByRole("link"), ...screen.queryAllByRole("button")];
    expect(ways.length).toBeGreaterThan(0);
  });

  it.each(screens)("%s shows no sign-in control, because sign-in does not work yet", (_name, ui) => {
    renderScreen(ui);

    // F8: the mockups show one on three of these; it arrives in 7c.
    expect(screen.queryByText(/sign in with google/i)).not.toBeInTheDocument();
  });
});

describe("404", () => {
  it("points home and at the report list", () => {
    renderScreen(<NotFoundScreen />);

    expect(screen.getByRole("link", { name: /Back to home/ })).toHaveAttribute("href", "/");
    expect(screen.getByRole("link", { name: /My reports/ })).toHaveAttribute("href", "/reports");
  });

  it("reassures that nothing was lost", () => {
    renderScreen(<NotFoundScreen />);
    expect(screen.getByText(/Nothing about your reports has changed/)).toBeInTheDocument();
  });
});

describe("report not available", () => {
  it("states the real retention periods", () => {
    renderScreen(<ReportNotAvailableScreen />);

    expect(screen.getByText(/deleted 24 hours after the session started/)).toBeInTheDocument();
    expect(screen.getByText(/Saved reports last 30 days/)).toBeInTheDocument();
  });

  it("explains the new-tab case, which is the likeliest cause", () => {
    renderScreen(<ReportNotAvailableScreen />);
    expect(screen.getByText(/Opened it in a new tab/)).toBeInTheDocument();
  });
});

describe("guest session ended", () => {
  it("does not create a session just by being shown", () => {
    // Arriving on a screen must never start a guest session — only an action does (LLD 7).
    renderScreen(<GuestSessionEndedScreen />);

    expect(window.sessionStorage.length).toBe(0);
  });
});

describe("500", () => {
  it("offers the request id, and copies it", async () => {
    // `setup()` installs a working clipboard stub, so the assertion can be what actually
    // matters: that the id ends up on the clipboard, not that a particular function was hit.
    const user = userEvent.setup();

    renderScreen(<ServerErrorScreen requestId="req-abc-123" onRetry={() => {}} />);

    expect(screen.getByText("req-abc-123")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Copy ID" }));

    await expect(navigator.clipboard.readText()).resolves.toBe("req-abc-123");
    expect(await screen.findByText("Copied")).toBeInTheDocument();
  });

  it("hides the request id block when there is no request to quote", () => {
    // A render error has no request id; an empty "REQUEST ID" heading would invite someone to
    // quote nothing.
    renderScreen(<ServerErrorScreen onRetry={() => {}} />);

    expect(screen.queryByText("REQUEST ID")).not.toBeInTheDocument();
    expect(screen.getByText(/reload the page/)).toBeInTheDocument();
  });

  it("calls reset when Try again is pressed", async () => {
    const onRetry = vi.fn();
    const user = userEvent.setup();

    renderScreen(<ServerErrorScreen onRetry={onRetry} />);
    await user.click(screen.getByRole("button", { name: /Try again/ }));

    expect(onRetry).toHaveBeenCalledOnce();
  });

  it("still offers a way home with no retry available", () => {
    renderScreen(<ServerErrorScreen />);
    expect(screen.getByRole("link", { name: /Back to home/ })).toHaveAttribute("href", "/");
  });
});

describe("503", () => {
  it("says the reports are safe, because that is the real worry", () => {
    renderScreen(<ServerNotRespondingScreen />);
    expect(screen.getByText(/Your reports are safe/)).toBeInTheDocument();
  });

  it("offers Retry now", () => {
    renderScreen(<ServerNotRespondingScreen />);
    expect(screen.getByRole("button", { name: /Retry now/ })).toBeInTheDocument();
  });
});
