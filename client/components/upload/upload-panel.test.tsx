import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";

import { createQueryClient } from "@/lib/query-client";
import { GUEST_STORAGE_KEY, resetGuestSessionForTests } from "@/lib/guest-session";
import { resetBackendStatusForTests } from "@/lib/backend-status";
import { UploadPanel } from "@/components/landing/upload-panel";
import { UploadSourceProvider } from "./upload-source-context";

const push = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push }),
}));

let fetchMock: ReturnType<typeof vi.fn>;

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function Wrapper({ children }: { children: ReactNode }) {
  return (
    <QueryClientProvider client={createQueryClient()}>
      <UploadSourceProvider>{children}</UploadSourceProvider>
    </QueryClientProvider>
  );
}

function renderPanel() {
  return render(<UploadPanel />, { wrapper: Wrapper });
}

function fileInputs(): HTMLInputElement[] {
  return Array.from(document.querySelectorAll<HTMLInputElement>('input[type="file"]'));
}

/** The hidden input behind the wide desktop drop zone. */
function fileInput(): HTMLInputElement {
  return fileInputs()[0];
}

/**
 * The phone's "Take a photo" tile.
 *
 * It is the only input that accepts an image while OCR is off — the others carry
 * `accept="application/pdf"`, so a picker would not offer one. That is the point of the
 * tile: a photo can still reach the check and get an explanation (B5).
 */
function photoInput(): HTMLInputElement {
  const input = fileInputs().find((el) => el.accept.includes("image/"));
  if (!input) throw new Error("no photo input rendered");
  return input;
}

/**
 * Drops files on the panel.
 *
 * Drag and drop is the one route that ignores `accept`, so it is how a wrong-type file
 * actually arrives on desktop.
 */
function dropFiles(...files: File[]) {
  const zone = document.querySelector(".ms-drop")?.parentElement;
  if (!zone) throw new Error("no drop zone rendered");

  fireEvent.drop(zone, {
    dataTransfer: { files, items: [], types: ["Files"] },
  });
}

function pdf(name = "report.pdf"): File {
  return new File(["%PDF-1.4 fake"], name, { type: "application/pdf" });
}

beforeEach(() => {
  push.mockClear();
  resetGuestSessionForTests();
  resetBackendStatusForTests();
  window.sessionStorage.setItem(
    GUEST_STORAGE_KEY,
    JSON.stringify({ token: "guest-abc", expiresAt: new Date(Date.now() + 3_600_000).toISOString() }),
  );
  fetchMock = vi.fn();
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("the Analyze button", () => {
  it("stays shut until there is something to send and a tick", async () => {
    const user = userEvent.setup();
    renderPanel();

    const analyze = screen.getByRole("button", { name: /Analyze report/ });
    expect(analyze).toBeDisabled();
    expect(screen.getByText(/Choose a file, paste your report, or pick a sample\./)).toBeInTheDocument();

    await user.upload(fileInput(), pdf());
    expect(analyze).toBeDisabled();
    expect(screen.getByText(/Tick the box above to continue\./)).toBeInTheDocument();

    await user.click(screen.getByRole("checkbox"));
    expect(analyze).toBeEnabled();
  });

  it("says what is missing rather than leaving a dead control", async () => {
    const user = userEvent.setup();
    renderPanel();

    await user.click(screen.getByRole("checkbox"));
    // Consent given, still nothing chosen.
    expect(screen.getByText(/Choose a file, paste your report, or pick a sample\./)).toBeInTheDocument();
  });

  it("goes quiet once a notice is already explaining the problem", async () => {
    const user = userEvent.setup();
    renderPanel();

    const huge = pdf("huge.pdf");
    Object.defineProperty(huge, "size", { value: 11 * 1024 * 1024 });
    await user.upload(fileInput(), huge);
    await user.click(screen.getByRole("checkbox"));

    expect(screen.getByText(/the limit is 10 MB/)).toBeInTheDocument();
    // Repeating it under the button would be the same sentence twice.
    expect(screen.queryByText(/Tick the box above/)).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Analyze report/ })).toBeDisabled();
  });
});

describe("switching between sources", () => {
  it("drops the file when the paste tab is chosen", async () => {
    const user = userEvent.setup();
    renderPanel();

    await user.upload(fileInput(), pdf());
    await user.click(screen.getByRole("checkbox"));
    expect(screen.getByRole("button", { name: /Analyze report/ })).toBeEnabled();

    await user.click(screen.getByRole("tab", { name: "Paste text" }));

    // A file still held while the paste box is showing would be sent by a button the
    // visitor believes is about their pasted text.
    expect(screen.getByRole("button", { name: /Analyze report/ })).toBeDisabled();
    expect(screen.getByRole("textbox")).toHaveValue("");
  });

  it("keeps the tick, because consent was about the page, not the file", async () => {
    const user = userEvent.setup();
    renderPanel();

    await user.click(screen.getByRole("checkbox"));
    await user.click(screen.getByRole("tab", { name: "Paste text" }));

    expect(screen.getByRole("checkbox")).toBeChecked();
  });

  it("clears a stale error when a new file is chosen", async () => {
    const user = userEvent.setup();
    renderPanel();

    // Dropped rather than picked: `accept` keeps a .docx out of the picker, so dragging it
    // in is the way one actually arrives.
    dropFiles(new File(["x"], "notes.docx", { type: "" }));
    expect(screen.getByText(/We can't read this kind of file\./)).toBeInTheDocument();

    await user.upload(fileInput(), pdf());
    expect(screen.queryByText(/We can't read this kind of file\./)).not.toBeInTheDocument();
  });

  it("takes the first of several dropped files and says so", () => {
    renderPanel();

    dropFiles(pdf("first.pdf"), pdf("second.pdf"));

    expect(screen.getByText("Only one file at a time.")).toBeInTheDocument();
    expect(screen.getAllByText("first.pdf").length).toBeGreaterThan(0);
  });

  it("switches to the paste tab from the photo notice", async () => {
    const user = userEvent.setup();
    renderPanel();

    await user.upload(photoInput(), new File(["x"], "IMG_1.png", { type: "image/png" }));
    expect(screen.getByText(/Photos are coming soon\./)).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Paste text instead" }));

    expect(screen.getByRole("tab", { name: "Paste text" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    expect(screen.queryByText(/Photos are coming soon\./)).not.toBeInTheDocument();
  });
});

describe("sending", () => {
  it("posts the file and goes to the report", async () => {
    const user = userEvent.setup();
    fetchMock.mockResolvedValueOnce(
      json(202, {
        id: "6f1c2a9e-3b4d-4e8f-9a1b-2c3d4e5f6a7b",
        status: "PENDING",
        sourceType: "PDF",
        createdAt: "2026-10-02T09:31:00Z",
        expiresAt: "2026-10-03T09:30:00Z",
      }),
    );

    renderPanel();
    await user.upload(fileInput(), pdf());
    await user.click(screen.getByRole("checkbox"));
    await user.click(screen.getByRole("button", { name: /Analyze report/ }));

    await vi.waitFor(() =>
      expect(push).toHaveBeenCalledWith("/reports/6f1c2a9e-3b4d-4e8f-9a1b-2c3d4e5f6a7b"),
    );
  });

  it("shows the server's refusal and lets them try again", async () => {
    const user = userEvent.setup();
    fetchMock.mockResolvedValue(
      new Response(
        JSON.stringify({
          type: "about:blank",
          title: "BUSY",
          status: 429,
          detail: "",
          code: "BUSY",
          requestId: "r1",
        }),
        { status: 429, headers: { "Content-Type": "application/problem+json" } },
      ),
    );

    renderPanel();
    await user.upload(fileInput(), pdf());
    await user.click(screen.getByRole("checkbox"));
    await user.click(screen.getByRole("button", { name: /Analyze report/ }));

    expect(await screen.findByText(/Lots of reports are being read right now\./)).toBeInTheDocument();
    expect(push).not.toHaveBeenCalled();
    // The button comes back, rather than staying stuck on "Sending…".
    expect(screen.getByRole("button", { name: /Analyze report/ })).toBeEnabled();
  });

  it("cannot be submitted twice", async () => {
    const user = userEvent.setup();
    let resolve: ((response: Response) => void) | undefined;
    fetchMock.mockReturnValue(
      new Promise<Response>((r) => {
        resolve = r;
      }),
    );

    renderPanel();
    await user.upload(fileInput(), pdf());
    await user.click(screen.getByRole("checkbox"));

    const analyze = screen.getByRole("button", { name: /Analyze report/ });
    await user.click(analyze);

    expect(await screen.findByRole("button", { name: /Sending…/ })).toBeDisabled();
    expect(fetchMock).toHaveBeenCalledOnce();

    resolve?.(json(202, { id: "x", status: "PENDING" }));
  });
});
