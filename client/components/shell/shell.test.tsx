import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import { Providers } from "@/app/providers";
import { Disclaimer } from "./disclaimer";
import { Footer } from "./footer";
import { Header } from "./header";
import { MobileMenu } from "./mobile-menu";
import { DISCLAIMER, NAV_LINKS } from "./nav";

function renderInProviders(ui: React.ReactElement) {
  return render(<Providers>{ui}</Providers>);
}

describe("the header", () => {
  it("names its navigation, so it is a landmark worth skipping to", () => {
    renderInProviders(<Header />);

    expect(screen.getAllByRole("navigation", { name: "Main" }).length).toBeGreaterThan(0);
  });

  it("carries all four nav links", () => {
    renderInProviders(<Header />);

    const nav = screen.getAllByRole("navigation", { name: "Main" })[0];
    for (const link of NAV_LINKS) {
      expect(within(nav).getByRole("link", { name: link.label })).toHaveAttribute(
        "href",
        link.href,
      );
    }
  });

  it("links the wordmark home, with a name that is not punctuation", () => {
    renderInProviders(<Header />);

    // The middle dot in MEDI·SCAN is decoration; a screen reader gets the real name.
    expect(screen.getByRole("link", { name: "Medi-Scan, home" })).toHaveAttribute("href", "/");
  });

  it("shows no status pill until the first health check has answered", () => {
    renderInProviders(<Header />);

    // CHECKING shows nothing at all, so the bar never flashes a state before it is true.
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("offers the theme toggle", () => {
    renderInProviders(<Header />);

    expect(screen.getByRole("button", { name: /Switch to (light|dark) mode/ })).toBeInTheDocument();
  });

  it("shows no sign-in button, because sign-in does not work yet", () => {
    renderInProviders(<Header />);

    // F8: hidden until its phase beats present and broken.
    expect(screen.queryByRole("button", { name: /sign in/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /sign in/i })).not.toBeInTheDocument();
  });
});

describe("the mobile menu", () => {
  it("opens, and says so", async () => {
    const user = userEvent.setup();
    renderInProviders(<MobileMenu />);

    const trigger = screen.getByRole("button", { name: "Open menu" });
    expect(trigger).toHaveAttribute("aria-expanded", "false");

    await user.click(trigger);

    expect(await screen.findByRole("dialog")).toBeInTheDocument();
  });

  it("closes on Escape and gives focus back to the button", async () => {
    const user = userEvent.setup();
    renderInProviders(<MobileMenu />);

    const trigger = screen.getByRole("button", { name: "Open menu" });
    await user.click(trigger);
    await screen.findByRole("dialog");

    await user.keyboard("{Escape}");

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    // Focus must come back, or a keyboard user is dropped at the top of the document.
    expect(screen.getByRole("button", { name: "Open menu" })).toHaveFocus();
  });

  it("closes when a link is followed", async () => {
    const user = userEvent.setup();
    renderInProviders(<MobileMenu />);

    await user.click(screen.getByRole("button", { name: "Open menu" }));
    const dialog = await screen.findByRole("dialog");

    await user.click(within(dialog).getByRole("link", { name: "Privacy" }));

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("holds every nav link", async () => {
    const user = userEvent.setup();
    renderInProviders(<MobileMenu />);

    await user.click(screen.getByRole("button", { name: "Open menu" }));
    const dialog = await screen.findByRole("dialog");

    for (const link of NAV_LINKS) {
      expect(within(dialog).getByRole("link", { name: link.label })).toBeInTheDocument();
    }
  });
});

describe("the footer", () => {
  it("says what this is and that it is not medical advice", () => {
    render(<Footer />);

    expect(screen.getByText(/is not medical advice/)).toBeInTheDocument();
    expect(screen.getByText(/Always talk to a doctor/)).toBeInTheDocument();
  });

  it("sets the wordmark as scenery, not as something to read again", () => {
    const { container } = render(<Footer />);

    // The giant faded wordmark is the brand used as a graphic. A screen reader announcing
    // "MEDI·SCAN" at the end of every page would be noise — the name is already in the
    // header and in the paragraph above it.
    const wordmark = container.querySelector('[aria-hidden="true"]');
    expect(wordmark).toHaveTextContent("MEDI·SCAN");
  });

  it("links privacy", () => {
    render(<Footer />);

    expect(screen.getByRole("link", { name: "Privacy" })).toHaveAttribute("href", "/privacy");
  });

  it("hides the GitHub link when no repo URL is configured", () => {
    // Better than a link that goes nowhere.
    render(<Footer />);

    expect(screen.queryByRole("link", { name: "GitHub" })).not.toBeInTheDocument();
  });
});

describe("the disclaimer", () => {
  it("is the exact sentence the rules require", () => {
    render(<Disclaimer />);

    // Word for word (root CLAUDE.md). A component, so it cannot be paraphrased by accident.
    expect(screen.getByText(DISCLAIMER)).toBeInTheDocument();
    expect(DISCLAIMER).toBe(
      "Medi-Scan explains what your report says and is not medical advice.",
    );
  });
});
