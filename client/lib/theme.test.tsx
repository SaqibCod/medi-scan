import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { Providers } from "@/app/providers";
import { DEFAULT_THEME, readAppliedTheme, THEME_STORAGE_KEY } from "@/lib/theme";
import { useThemeChoice } from "@/lib/use-theme-choice";

/** Lets a test say what the device setting is before next-themes reads it. */
function setDevicePrefersDark(prefersDark: boolean) {
  const listeners = new Set<(e: MediaQueryListEvent) => void>();

  window.matchMedia = ((query: string) => ({
    matches: query.includes("prefers-color-scheme: dark") ? prefersDark : false,
    media: query,
    onchange: null,
    addListener: (l: (e: MediaQueryListEvent) => void) => listeners.add(l),
    removeListener: (l: (e: MediaQueryListEvent) => void) => listeners.delete(l),
    addEventListener: (_: string, l: (e: MediaQueryListEvent) => void) => listeners.add(l),
    removeEventListener: (_: string, l: (e: MediaQueryListEvent) => void) => listeners.delete(l),
    dispatchEvent: () => false,
  })) as unknown as typeof window.matchMedia;

  return listeners;
}

function Toggle() {
  const { theme, toggle } = useThemeChoice();
  return (
    <button type="button" onClick={toggle} suppressHydrationWarning>
      {theme}
    </button>
  );
}

function renderToggle() {
  return render(
    <Providers>
      <Toggle />
    </Providers>,
  );
}

const appliedTheme = () => document.documentElement.getAttribute("data-theme");

describe("theme precedence", () => {
  beforeEach(() => {
    setDevicePrefersDark(true);
  });

  it("uses the saved choice when there is one, over the device setting", async () => {
    setDevicePrefersDark(true);
    window.localStorage.setItem(THEME_STORAGE_KEY, "light");

    renderToggle();

    await waitFor(() => expect(appliedTheme()).toBe("light"));
    expect(await screen.findByRole("button", { name: "light" })).toBeInTheDocument();
  });

  it("follows the device setting when nothing is saved", async () => {
    setDevicePrefersDark(false);

    renderToggle();

    await waitFor(() => expect(appliedTheme()).toBe("light"));
  });

  it("stores nothing until the visitor actually chooses", async () => {
    setDevicePrefersDark(false);

    renderToggle();
    await waitFor(() => expect(appliedTheme()).toBe("light"));

    // Following the device is not a choice, so there is nothing to remember yet.
    expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBeNull();
  });

  it("falls back to dark when there is neither a saved choice nor a device preference", async () => {
    setDevicePrefersDark(false);
    // A browser that reports no colour-scheme support at all.
    window.matchMedia = ((query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    })) as unknown as typeof window.matchMedia;

    document.documentElement.removeAttribute("data-theme");
    expect(readAppliedTheme()).toBe(DEFAULT_THEME);
    expect(DEFAULT_THEME).toBe("dark");
  });
});

describe("the toggle", () => {
  beforeEach(() => {
    setDevicePrefersDark(true);
  });

  it("writes the chosen theme to ms-theme and applies it", async () => {
    const user = userEvent.setup();
    renderToggle();

    await waitFor(() => expect(appliedTheme()).toBe("dark"));

    await user.click(await screen.findByRole("button", { name: "dark" }));

    await waitFor(() => expect(appliedTheme()).toBe("light"));
    expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBe("light");
  });

  it("writes only 'dark' or 'light', never 'system'", async () => {
    const user = userEvent.setup();
    renderToggle();

    await waitFor(() => expect(appliedTheme()).toBe("dark"));

    await user.click(await screen.findByRole("button", { name: "dark" }));
    await waitFor(() => expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBe("light"));

    await user.click(await screen.findByRole("button", { name: "light" }));
    await waitFor(() => expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBe("dark"));
  });

  it("seeds its first render from the attribute the pre-hydration script set", () => {
    // What that script leaves behind for a visitor who chose light. Reading it is what stops
    // the toggle rendering a sun and then swapping it to a moon after mount; the first paint
    // itself is asserted end-to-end, where a real browser can be asked what it painted.
    document.documentElement.setAttribute("data-theme", "light");
    expect(readAppliedTheme()).toBe("light");

    document.documentElement.setAttribute("data-theme", "dark");
    expect(readAppliedTheme()).toBe("dark");
  });

  it("ignores a junk attribute value rather than rendering an unknown theme", () => {
    document.documentElement.setAttribute("data-theme", "chartreuse");
    expect(readAppliedTheme()).toBe(DEFAULT_THEME);
  });
});

describe("a broken localStorage", () => {
  it("does not stop the page rendering", async () => {
    setDevicePrefersDark(true);

    // Safari in private mode, and any browser with storage blocked for the site.
    const getItem = vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("storage is disabled");
    });
    const setItem = vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("storage is disabled");
    });

    try {
      const user = userEvent.setup();
      renderToggle();

      await waitFor(() => expect(appliedTheme()).toBe("dark"));

      // The choice cannot be remembered, but it must still take effect for this tab.
      await act(async () => {
        await user.click(screen.getByRole("button"));
      });
      await waitFor(() => expect(appliedTheme()).toBe("light"));
    } finally {
      getItem.mockRestore();
      setItem.mockRestore();
    }
  });
});
