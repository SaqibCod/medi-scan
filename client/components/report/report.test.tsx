import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";

import { NoticeProvider } from "@/components/notices/notice-provider";
import { ToastProvider } from "@/components/notices/toaster";
import { ReportView } from "@/components/report/report-view";
import { createQueryClient } from "@/lib/query-client";
import { GUEST_STORAGE_KEY, resetGuestSessionForTests } from "@/lib/guest-session";
import { resetBackendStatusForTests } from "@/lib/backend-status";
import type { Biomarker, ReportResponse, ReportStatus } from "@/lib/api-types";

const push = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ push }) }));

const ID = "6f1c2a9e-3b4d-4e8f-9a1b-2c3d4e5f6a7b";

let fetchMock: ReturnType<typeof vi.fn>;

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function problem(status: number, code: string) {
  return new Response(
    JSON.stringify({ type: "about:blank", title: code, status, detail: "", code, requestId: "r9" }),
    { status, headers: { "Content-Type": "application/problem+json" } },
  );
}

function marker(over: Partial<Biomarker> & Pick<Biomarker, "id" | "testName">): Biomarker {
  return {
    rawValue: "1",
    numericValue: 1,
    unit: "mg/dL",
    referenceRangeText: "<100",
    refLow: null,
    refHigh: 100,
    flag: "NORMAL",
    biomarkerSlug: null,
    ...over,
  };
}

const BIOMARKERS: Biomarker[] = [
  marker({
    id: "b1",
    testName: "Total Cholesterol",
    rawValue: "238",
    numericValue: 238,
    referenceRangeText: "<200",
    flag: "HIGH",
  }),
  marker({
    id: "b2",
    testName: "LDL Cholesterol",
    rawValue: "162",
    numericValue: 162,
    flag: "HIGH",
    biomarkerSlug: "ldl-cholesterol",
  }),
  marker({ id: "b3", testName: "HDL Cholesterol", rawValue: "52", referenceRangeText: ">40" }),
  marker({ id: "b4", testName: "Ferritin", rawValue: "9", flag: "LOW" }),
  marker({
    id: "b5",
    testName: "Urine Protein",
    rawValue: "Negative",
    numericValue: null,
    unit: null,
    referenceRangeText: null,
    flag: "UNKNOWN",
  }),
];

function report(status: ReportStatus, over: Partial<ReportResponse> = {}): ReportResponse {
  return {
    id: ID,
    status,
    sourceType: "PDF",
    createdAt: "2026-10-02T09:31:00Z",
    // The extra 30s keeps the countdown on "23 h 12 min" rather than flooring to 11 as the
    // test's own milliseconds tick by.
    expiresAt: new Date(Date.now() + 23 * 3_600_000 + 12 * 60_000 + 30_000).toISOString(),
    error: null,
    result:
      status === "DONE"
        ? {
            collectedOn: "2026-09-28",
            biomarkers: BIOMARKERS,
            summary: "Most of your results are in the normal range.",
            highlights: [
              "Total Cholesterol is 238 mg/dL, above the reference range of <200.",
              "Ferritin is below the reference range on this report.",
            ],
            counts: { total: 5, low: 1, normal: 1, high: 2, unknown: 1 },
          }
        : null,
    ...over,
  };
}

function Wrapper({ children }: { children: ReactNode }) {
  return (
    <QueryClientProvider client={createQueryClient()}>
      <NoticeProvider>
        <ToastProvider>{children}</ToastProvider>
      </NoticeProvider>
    </QueryClientProvider>
  );
}

function renderReport(id = ID) {
  return render(<ReportView id={id} />, { wrapper: Wrapper });
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
  // Health, so the backend checker settles on ONLINE rather than inventing a 503 screen.
  fetchMock.mockImplementation((url: string) =>
    String(url).includes("/actuator/health")
      ? Promise.resolve(json(200, { status: "UP" }))
      : Promise.resolve(json(200, report("DONE"))),
  );
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  resetBackendStatusForTests();
});

describe("the done view", () => {
  it("shows the summary as plain text", async () => {
    renderReport();

    expect(
      await screen.findByText("Most of your results are in the normal range."),
    ).toBeInTheDocument();
  });

  it("gives each highlight the flag of the marker it names", async () => {
    renderReport();
    await screen.findByText(/Most of your results/);

    // B8: highlights are plain strings, so the chip is derived.
    const cholesterol = screen
      .getByText(/Total Cholesterol is 238 mg\/dL/)
      .closest("li") as HTMLElement;
    expect(within(cholesterol).getByText("HIGH")).toBeInTheDocument();

    const ferritin = screen.getByText(/Ferritin is below/).closest("li") as HTMLElement;
    expect(within(ferritin).getByText("LOW")).toBeInTheDocument();
  });

  it("states the dates and the deletion countdown", async () => {
    renderReport();
    await screen.findByText(/Most of your results/);

    expect(screen.getByText("Collected 28 Sep 2026")).toBeInTheDocument();
    expect(screen.getByText("Deleted in 23 h")).toBeInTheDocument();
    expect(screen.getByText(/23 h 12 min/)).toBeInTheDocument();
  });

  it("carries the disclaimer word for word", async () => {
    renderReport();
    await screen.findByText(/Most of your results/);

    expect(
      screen.getByText(
        /Medi-Scan explains what your report says and is not medical advice\. Talk to your doctor about what these results mean for you\./,
      ),
    ).toBeInTheDocument();
  });

  it("notes that ranges come from the report, not from us", async () => {
    renderReport();
    await screen.findByText(/Most of your results/);

    expect(screen.getByText(/Labs use different ranges/)).toBeInTheDocument();
  });

  it("shows a qualitative value as text, with a named chip for the missing flag", async () => {
    renderReport();
    await screen.findByText(/Most of your results/);

    expect(screen.getAllByText("Negative").length).toBeGreaterThan(0);
    // The design system renders an unrecognised flag as a neutral dash rather than the word,
    // so the meaning has to reach a screen reader some other way.
    expect(screen.getAllByText("Flag unknown").length).toBeGreaterThan(0);
  });

  it("links a marker to its page, carrying ids and nothing else", async () => {
    renderReport();
    await screen.findByText(/Most of your results/);

    const link = screen.getAllByRole("link", { name: "LDL Cholesterol" })[0];
    expect(link).toHaveAttribute("href", `/biomarkers/ldl-cholesterol?report=${ID}&b=b2`);
    // No value, no test name, no summary in the URL.
    expect(link.getAttribute("href")).not.toMatch(/162|mg%2FdL|Cholesterol=/);
  });

  it("leaves a marker with no curated page as plain text", async () => {
    renderReport();
    await screen.findByText(/Most of your results/);

    expect(screen.queryByRole("link", { name: "Ferritin" })).not.toBeInTheDocument();
  });
});

describe("the count tiles", () => {
  it("take their numbers from the server's counts", async () => {
    renderReport();
    await screen.findByText(/Most of your results/);

    const group = screen.getByRole("radiogroup");
    expect(within(group).getByRole("radio", { name: /5\s*All results/ })).toBeInTheDocument();
    expect(within(group).getByRole("radio", { name: /2\s*High/ })).toBeInTheDocument();
    expect(within(group).getByRole("radio", { name: /1\s*Unclear/ })).toBeInTheDocument();
  });

  it("filter the table and say what is shown", async () => {
    const user = userEvent.setup();
    renderReport();
    await screen.findByText(/Most of your results/);

    // The tile and the table caption both say "All results"; this is the caption.
    expect(screen.getByRole("heading", { name: "All results" })).toBeInTheDocument();
    expect(screen.getByText("5 of 5")).toBeInTheDocument();

    await user.click(screen.getByRole("radio", { name: /2\s*High/ }));

    expect(screen.getByText("2 of 5")).toBeInTheDocument();
    expect(screen.getAllByText("Total Cholesterol").length).toBeGreaterThan(0);
    expect(screen.queryByText("Ferritin")).not.toBeInTheDocument();
  });

  it("offer no filter for a flag nothing carries", async () => {
    fetchMock.mockImplementation((url: string) =>
      String(url).includes("/actuator/health")
        ? Promise.resolve(json(200, { status: "UP" }))
        : Promise.resolve(
            json(200, {
              ...report("DONE"),
              result: {
                ...report("DONE").result,
                counts: { total: 5, low: 0, normal: 1, high: 2, unknown: 1 },
              },
            }),
          ),
    );

    renderReport();
    await screen.findByText(/Most of your results/);

    expect(screen.getByRole("radio", { name: /0\s*Low/ })).toBeDisabled();
  });
});

describe("the processing view", () => {
  beforeEach(() => {
    fetchMock.mockImplementation((url: string) =>
      String(url).includes("/actuator/health")
        ? Promise.resolve(json(200, { status: "UP" }))
        : Promise.resolve(json(200, report("PROCESSING"))),
    );
  });

  it("says what is happening without pretending to measure it", async () => {
    renderReport();

    expect(await screen.findByText("Reading your report.")).toBeInTheDocument();
    expect(screen.getByText("PROCESSING")).toBeInTheDocument();

    // B3: the five steps explain the pipeline. None of them is marked done, because the
    // backend does not report which one it is on.
    expect(screen.getByText("Mask")).toBeInTheDocument();
    expect(screen.queryByRole("progressbar")).not.toBeInTheDocument();
  });

  it("says QUEUED while it is still pending", async () => {
    fetchMock.mockImplementation((url: string) =>
      String(url).includes("/actuator/health")
        ? Promise.resolve(json(200, { status: "UP" }))
        : Promise.resolve(json(200, report("PENDING"))),
    );

    renderReport();
    expect(await screen.findByText("QUEUED")).toBeInTheDocument();
  });

  it("announces the state once, not on every poll", async () => {
    renderReport();
    await screen.findByText("Reading your report.");

    const live = screen.getByRole("status");
    expect(live).toHaveTextContent("Your report is being read.");
  });
});

describe("the failed view", () => {
  function failWith(code: string) {
    fetchMock.mockImplementation((url: string) =>
      String(url).includes("/actuator/health")
        ? Promise.resolve(json(200, { status: "UP" }))
        : Promise.resolve(
            json(200, report("FAILED", { error: { code, message: "server wording" } })),
          ),
    );
  }

  it("explains UNREADABLE without offering OCR that does not exist yet", async () => {
    failWith("UNREADABLE");
    renderReport();

    expect(await screen.findByText("We couldn't read text from this file.")).toBeInTheDocument();
    expect(screen.getByText(/coming soon/)).toBeInTheDocument();
    // B4: no "try a clearer photo" while the build cannot read one.
    expect(screen.queryByText(/clearer photo/)).not.toBeInTheDocument();
  });

  it("names the report, not a request, in the code line", async () => {
    failWith("EXTRACTION_FAILED");
    renderReport();
    await screen.findByText("Something went wrong reading the results.");

    // B9: a background job failure has no request id to quote.
    expect(screen.getByText(`EXTRACTION_FAILED · report ${ID.slice(0, 8)}`)).toBeInTheDocument();
  });

  it("never shows the server's own message", async () => {
    failWith("LLM_UNAVAILABLE");
    renderReport();
    await screen.findByText("The AI service is unavailable right now.");

    expect(screen.queryByText("server wording")).not.toBeInTheDocument();
  });

  it("offers two ways back in", async () => {
    failWith("NO_RESULTS_FOUND");
    renderReport();
    await screen.findByText("We didn't find lab results in this document.");

    expect(screen.getByRole("link", { name: /Try another file/ })).toHaveAttribute(
      "href",
      "/#upload",
    );
    expect(screen.getByRole("link", { name: /Try a sample report/ })).toHaveAttribute(
      "href",
      "/#samples",
    );
  });

  it("falls back for a failure code the contract adds later", async () => {
    failWith("SOMETHING_NEW");
    renderReport();

    expect(
      await screen.findByText("We couldn't finish reading this report."),
    ).toBeInTheDocument();
  });
});

describe("errors", () => {
  function answerWith(response: Response) {
    fetchMock.mockImplementation((url: string) =>
      String(url).includes("/actuator/health")
        ? Promise.resolve(json(200, { status: "UP" }))
        : Promise.resolve(response.clone()),
    );
  }

  it("shows 'report not available' for an id that is not a UUID, without asking", async () => {
    renderReport("latest");

    expect(await screen.findByText("We can't find this report.")).toBeInTheDocument();
    expect(fetchMock.mock.calls.every(([url]) => String(url).includes("health"))).toBe(true);
  });

  it("shows 'report not available' on a 404", async () => {
    answerWith(problem(404, "REPORT_NOT_FOUND"));
    renderReport();

    expect(await screen.findByText("We can't find this report.")).toBeInTheDocument();
  });

  it("shows 'guest session ended' on a dead session", async () => {
    answerWith(problem(401, "SESSION_INVALID"));
    renderReport();

    expect(await screen.findByText("Your guest session has ended.")).toBeInTheDocument();
  });
});

describe("deleting", () => {
  it("asks first, then clears the cache, toasts and leaves", async () => {
    const user = userEvent.setup();
    renderReport();
    await screen.findByText(/Most of your results/);

    await user.click(screen.getByRole("button", { name: /Delete report/ }));

    const dialog = await screen.findByRole("alertdialog");
    expect(within(dialog).getByText("Delete this report?")).toBeInTheDocument();
    expect(within(dialog).getByText(/can't be undone/)).toBeInTheDocument();

    fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }));
    await user.click(within(dialog).getByRole("button", { name: /^Delete report$/ }));

    expect(await screen.findByText("Report deleted.")).toBeInTheDocument();
    expect(push).toHaveBeenCalledWith("/");
  });

  it("treats a 404 as deleted too", async () => {
    const user = userEvent.setup();
    renderReport();
    await screen.findByText(/Most of your results/);

    await user.click(screen.getByRole("button", { name: /Delete report/ }));
    const dialog = await screen.findByRole("alertdialog");

    // Already gone — expired, or deleted in another tab.
    fetchMock.mockResolvedValueOnce(problem(404, "REPORT_NOT_FOUND"));
    await user.click(within(dialog).getByRole("button", { name: /^Delete report$/ }));

    expect(await screen.findByText("Report deleted.")).toBeInTheDocument();
    expect(push).toHaveBeenCalledWith("/");
  });

  it("keeps the dialog open when the delete failed", async () => {
    const user = userEvent.setup();
    renderReport();
    await screen.findByText(/Most of your results/);

    await user.click(screen.getByRole("button", { name: /Delete report/ }));
    const dialog = await screen.findByRole("alertdialog");

    fetchMock.mockResolvedValueOnce(problem(500, "INTERNAL_ERROR"));
    await user.click(within(dialog).getByRole("button", { name: /^Delete report$/ }));

    // Closing here would leave someone believing it was deleted when it was not.
    expect(await within(dialog).findByText("We couldn't delete this report.")).toBeInTheDocument();
    expect(screen.getByRole("alertdialog")).toBeInTheDocument();
    expect(push).not.toHaveBeenCalled();
  });

  it("can be cancelled", async () => {
    const user = userEvent.setup();
    renderReport();
    await screen.findByText(/Most of your results/);

    await user.click(screen.getByRole("button", { name: /Delete report/ }));
    const dialog = await screen.findByRole("alertdialog");
    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));

    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    expect(push).not.toHaveBeenCalled();
  });

  it("can be reached from the processing view", async () => {
    const user = userEvent.setup();
    fetchMock.mockImplementation((url: string) =>
      String(url).includes("/actuator/health")
        ? Promise.resolve(json(200, { status: "UP" }))
        : Promise.resolve(json(200, report("PROCESSING"))),
    );

    renderReport();
    await screen.findByText("Reading your report.");

    await user.click(screen.getByRole("button", { name: /Cancel and delete/ }));
    expect(await screen.findByRole("alertdialog")).toBeInTheDocument();
  });
});

describe("what never leaves the page", () => {
  it("puts no report content in storage", async () => {
    renderReport();
    await screen.findByText(/Most of your results/);

    const everything = JSON.stringify({
      local: { ...window.localStorage },
      session: { ...window.sessionStorage },
    });

    expect(everything).not.toMatch(/Cholesterol|238|162|normal range/);
    expect(Object.keys(window.sessionStorage)).toEqual([GUEST_STORAGE_KEY]);
  });
});
