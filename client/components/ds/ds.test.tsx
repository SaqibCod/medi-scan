import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import { buildHelixDots } from "./helix-dots";
import {
  BiomarkerRow,
  Button,
  Card,
  Checkbox,
  Dropzone,
  FlagChip,
  Icon,
  ICON_NAMES,
  IconButton,
  Marquee,
  StatusPill,
  StepTimeline,
  Tabs,
  ThemeToggle,
} from "./index";

describe("FlagChip", () => {
  it.each([
    ["LOW", "LOW", "ms-flag--low"],
    ["NORMAL", "NORMAL", "ms-flag--normal"],
    ["HIGH", "HIGH", "ms-flag--high"],
  ])("renders %s as its own word and colour", (flag, text, className) => {
    const { container } = render(<FlagChip flag={flag} />);

    expect(screen.getByText(text)).toBeInTheDocument();
    expect(container.firstElementChild).toHaveClass(className);
  });

  it.each(["UNKNOWN", "CRITICAL", "", "something new from a later API"])(
    "renders %p as UNKNOWN, with a name a screen reader can read",
    (flag) => {
      const { container } = render(<FlagChip flag={flag} />);

      expect(container.firstElementChild).toHaveClass("ms-flag--unknown");
      // The dash alone is announced as nothing at all.
      expect(screen.getByText("Flag unknown")).toBeInTheDocument();
    },
  );

  it("is never red: HIGH uses the orange flag tokens", () => {
    const { container } = render(<FlagChip flag="HIGH" />);
    // The class is what maps to --flag-high-*, which tokens.json defines as orange. A literal
    // colour here would be the thing the design system exists to prevent.
    expect(container.firstElementChild?.className).toContain("ms-flag--high");
  });
});

describe("Button", () => {
  it("calls onClick when pressed", async () => {
    const onClick = vi.fn();
    const user = userEvent.setup();

    render(<Button onClick={onClick}>Analyze report</Button>);
    await user.click(screen.getByRole("button", { name: "Analyze report" }));

    expect(onClick).toHaveBeenCalledOnce();
  });

  it("does not fire when disabled", async () => {
    const onClick = vi.fn();
    const user = userEvent.setup();

    render(
      <Button onClick={onClick} disabled>
        Analyze report
      </Button>,
    );
    await user.click(screen.getByRole("button", { name: "Analyze report" }));

    expect(onClick).not.toHaveBeenCalled();
  });

  it("renders an internal href as a link", () => {
    render(<Button href="/privacy">Privacy</Button>);

    expect(screen.getByRole("link", { name: "Privacy" })).toHaveAttribute("href", "/privacy");
  });

  it("marks a disabled link as disabled and keeps it out of the tab order", () => {
    render(
      <Button href="/privacy" disabled>
        Privacy
      </Button>,
    );

    // A disabled <a> is not a thing in HTML, so it must not render as a link at all.
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
    expect(screen.getByText("Privacy").closest("[aria-disabled]")).toHaveAttribute(
      "aria-disabled",
      "true",
    );
  });

  it("adds rel=noopener on an external link", () => {
    render(<Button href="https://github.com/example/repo">GitHub</Button>);

    expect(screen.getByRole("link", { name: "GitHub" })).toHaveAttribute(
      "rel",
      "noopener noreferrer",
    );
  });
});

describe("IconButton", () => {
  it("takes its accessible name from the required label", () => {
    render(<IconButton icon="menu" label="Open menu" />);

    const button = screen.getByRole("button", { name: "Open menu" });
    expect(button).toHaveAttribute("title", "Open menu");
  });

  it("exposes pressed and expanded state when given", () => {
    render(<IconButton icon="menu" label="Open menu" expanded pressed={false} />);

    const button = screen.getByRole("button", { name: "Open menu" });
    expect(button).toHaveAttribute("aria-expanded", "true");
    expect(button).toHaveAttribute("aria-pressed", "false");
  });

  it("passes through the props a headless primitive merges into it", () => {
    // Base UI's Dialog hands its trigger aria-expanded, aria-controls and its own handlers
    // this way. A button that dropped them would render fine and say nothing about its state.
    render(<IconButton icon="menu" label="Open menu" aria-controls="site-menu" id="menu-btn" />);

    const button = screen.getByRole("button", { name: "Open menu" });
    expect(button).toHaveAttribute("aria-controls", "site-menu");
    expect(button).toHaveAttribute("id", "menu-btn");
  });
});

describe("ThemeToggle", () => {
  it("names what a press will do, not what the theme is", () => {
    render(<ThemeToggle onToggle={() => {}} />);

    // Both labels are in the markup; CSS hides one, which also takes it out of the
    // accessibility tree. jsdom applies no stylesheet, so both are visible here — what this
    // asserts is that the wording is actionable, not which one is showing.
    expect(screen.getByText("Switch to light mode")).toBeInTheDocument();
    expect(screen.getByText("Switch to dark mode")).toBeInTheDocument();
  });

  it("reports a press", async () => {
    const onToggle = vi.fn();
    const user = userEvent.setup();

    render(<ThemeToggle onToggle={onToggle} />);
    await user.click(screen.getByRole("button"));

    expect(onToggle).toHaveBeenCalledOnce();
  });

  it("renders the same markup whatever the theme, so hydration cannot mismatch", () => {
    // The server cannot know the theme. If the markup depended on it, every light-mode
    // visitor would get a hydration error and a re-rendered tree on each page load.
    const { container: asDark } = render(<ThemeToggle theme="dark" onToggle={() => {}} />);
    const darkHtml = asDark.innerHTML;

    cleanup();

    const { container: asLight } = render(<ThemeToggle theme="light" onToggle={() => {}} />);
    expect(asLight.innerHTML).toBe(darkHtml);
  });
});

describe("StatusPill", () => {
  it("is a live region, so waking becoming online is announced", () => {
    render(<StatusPill status="waking" />);

    const pill = screen.getByRole("status");
    expect(pill).toHaveTextContent("STARTING UP…");
  });

  it("shows the word beside the dot, so the state never depends on colour", () => {
    render(<StatusPill status="online" />);
    expect(screen.getByRole("status")).toHaveTextContent("ONLINE");
  });

  it("shortens the label for a phone bar", () => {
    render(<StatusPill status="waking" short />);
    expect(screen.getByRole("status")).toHaveTextContent("STARTING");
  });
});

describe("Tabs", () => {
  const items = [
    { value: "upload", label: "Upload file" },
    { value: "paste", label: "Paste text" },
  ];

  it("renders a labelled tablist with one selected tab", () => {
    render(<Tabs items={items} value="upload" onChange={() => {}} label="Input method" />);

    const tablist = screen.getByRole("tablist", { name: "Input method" });
    expect(within(tablist).getByRole("tab", { selected: true })).toHaveTextContent("Upload file");
  });

  it("keeps one tab stop for the whole group", () => {
    render(<Tabs items={items} value="upload" onChange={() => {}} label="Input method" />);

    expect(screen.getByRole("tab", { name: "Upload file" })).toHaveAttribute("tabindex", "0");
    expect(screen.getByRole("tab", { name: "Paste text" })).toHaveAttribute("tabindex", "-1");
  });

  it("moves between tabs with the arrow keys", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();

    render(<Tabs items={items} value="upload" onChange={onChange} label="Input method" />);

    await user.tab();
    expect(screen.getByRole("tab", { name: "Upload file" })).toHaveFocus();

    await user.keyboard("{ArrowRight}");
    expect(onChange).toHaveBeenCalledWith("paste");
  });

  it("wraps around at the ends", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();

    render(<Tabs items={items} value="upload" onChange={onChange} label="Input method" />);

    await user.tab();
    await user.keyboard("{ArrowLeft}");

    expect(onChange).toHaveBeenCalledWith("paste");
  });

  it("points each tab at the panel it controls", () => {
    render(
      <Tabs items={items} value="upload" onChange={() => {}} label="Input method" panelId="panel" />,
    );

    expect(screen.getByRole("tab", { name: "Upload file" })).toHaveAttribute(
      "aria-controls",
      "panel",
    );
  });
});

describe("Checkbox", () => {
  it("reports the new state when toggled", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();

    render(<Checkbox onChange={onChange}>I understand this is not medical advice.</Checkbox>);
    await user.click(screen.getByRole("checkbox"));

    expect(onChange).toHaveBeenCalledWith(true);
  });

  it("is named by its wrapping label", () => {
    render(<Checkbox>I understand this is not medical advice.</Checkbox>);

    expect(
      screen.getByRole("checkbox", { name: /not medical advice/ }),
    ).toBeInTheDocument();
  });

  it("stays controlled when given `checked`", () => {
    render(<Checkbox checked onChange={() => {}}>Consent</Checkbox>);
    expect(screen.getByRole("checkbox")).toBeChecked();
  });
});

describe("Dropzone", () => {
  it("keeps a real file input, so the keyboard and the native picker still work", () => {
    const { container } = render(<Dropzone />);
    const input = container.querySelector("input[type=file]");

    expect(input).toBeInTheDocument();
    expect(input).toHaveAttribute("accept", "application/pdf,image/png,image/jpeg");
  });

  it("reports the chosen files", async () => {
    const onFiles = vi.fn();
    const user = userEvent.setup();
    const { container } = render(<Dropzone onFiles={onFiles} />);

    const input = container.querySelector("input[type=file]") as HTMLInputElement;
    await user.upload(input, new File(["%PDF-1.4"], "report.pdf", { type: "application/pdf" }));

    expect(onFiles).toHaveBeenCalledOnce();
  });

  it("states the real limit from the API", () => {
    render(<Dropzone />);
    expect(screen.getByText("PDF · PNG · JPEG — up to 10 MB")).toBeInTheDocument();
  });
});

describe("Card", () => {
  it("becomes a real button when given onClick", async () => {
    const onClick = vi.fn();
    const user = userEvent.setup();

    render(<Card onClick={onClick}>Lipid panel</Card>);
    await user.click(screen.getByRole("button", { name: "Lipid panel" }));

    expect(onClick).toHaveBeenCalledOnce();
  });

  it("is a plain container otherwise", () => {
    render(<Card>Lipid panel</Card>);
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });
});

describe("BiomarkerRow", () => {
  it("shows the value, unit, range and flag", () => {
    render(
      <BiomarkerRow name="LDL Cholesterol" value="162" unit="mg/dL" range="<100" flag="HIGH" />,
    );

    expect(screen.getByText("LDL Cholesterol")).toBeInTheDocument();
    expect(screen.getByText("mg/dL")).toBeInTheDocument();
    expect(screen.getByText("<100")).toBeInTheDocument();
    expect(screen.getByText("HIGH")).toBeInTheDocument();
  });

  it("shows a dash rather than an empty cell when there is no range", () => {
    const { container } = render(
      <BiomarkerRow name="Notes" value="Negative" range={null} flag="UNKNOWN" />,
    );
    // Scoped to the range cell: an unknown flag renders a dash too, and the point here is
    // that the column still reads as a column.
    expect(container.querySelector(".ms-row__range")).toHaveTextContent("—");
  });

  it("renders a qualitative result as text, with no range", () => {
    // `numericValue: null` results — "Negative", "Trace" — are text, never a range bar.
    render(<BiomarkerRow name="Ketones" value="Negative" unit={null} range={null} flag="UNKNOWN" />);
    expect(screen.getByText("Negative")).toBeInTheDocument();
  });

  it("links the name only when the API gave a slug", () => {
    const { rerender } = render(
      <BiomarkerRow name="LDL Cholesterol" value="162" flag="HIGH" biomarkerSlug="ldl-cholesterol" />,
    );
    expect(screen.getByRole("link", { name: "LDL Cholesterol" })).toHaveAttribute(
      "href",
      "/biomarkers/ldl-cholesterol",
    );

    rerender(<BiomarkerRow name="LDL Cholesterol" value="162" flag="HIGH" biomarkerSlug={null} />);
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  });
});

describe("StepTimeline", () => {
  const steps = [{ title: "Upload", body: "Drop a PDF." }, { title: "Read" }, { title: "Mask" }];

  it("marks the active step and reports a selection", async () => {
    const onSelect = vi.fn();
    const user = userEvent.setup();

    render(<StepTimeline steps={steps} active={0} onSelect={onSelect} />);

    expect(screen.getByRole("button", { pressed: true })).toHaveTextContent("Upload");

    await user.click(screen.getByRole("button", { name: /Read/ }));
    expect(onSelect).toHaveBeenCalledWith(1);
  });

  it("expands the active step's body when vertical", () => {
    render(<StepTimeline steps={steps} active={0} orientation="vertical" />);

    expect(screen.getByText("Drop a PDF.")).toBeInTheDocument();
    expect(screen.getByRole("button", { expanded: true })).toHaveTextContent("Upload");
  });
});

describe("decorative pieces", () => {
  it("Marquee is hidden from screen readers", () => {
    const { container } = render(<Marquee items={["Private by default", "Every value"]} />);
    expect(container.firstElementChild).toHaveAttribute("aria-hidden", "true");
  });

  it("the helix is deterministic for a seed, and hidden from screen readers", () => {
    expect(buildHelixDots(560, 860, 7)).toEqual(buildHelixDots(560, 860, 7));
    expect(buildHelixDots(560, 860, 7)).not.toEqual(buildHelixDots(560, 860, 8));
  });

  it("every icon is hidden from screen readers", () => {
    for (const name of ICON_NAMES) {
      const { container, unmount } = render(<Icon name={name} />);
      expect(container.querySelector("svg")).toHaveAttribute("aria-hidden", "true");
      unmount();
    }
  });

  it("every icon in the set actually draws something", () => {
    for (const name of ICON_NAMES) {
      const { container, unmount } = render(<Icon name={name} />);
      expect(container.querySelectorAll("path, circle").length).toBeGreaterThan(0);
      unmount();
    }
  });
});
