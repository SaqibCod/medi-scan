import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClientProvider, focusManager } from "@tanstack/react-query";
import type { ReactNode } from "react";

import { ApiError } from "./api-error";
import { createQueryClient, queryKeys } from "./query-client";
import { GUEST_STORAGE_KEY, resetGuestSessionForTests } from "./guest-session";
import { resetBackendStatusForTests } from "./backend-status";
import {
  POLL_INTERVAL_MS,
  POLL_LIMIT_MS,
  createReport,
  deleteReport,
  isProcessing,
  isReportId,
  seedReport,
  useReport,
} from "./reports";
import type { CreateReportResponse, ReportResponse, ReportStatus } from "./api-types";

const API = "http://localhost:8080";
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
    JSON.stringify({ type: "about:blank", title: code, status, detail: "", code, requestId: "r1" }),
    { status, headers: { "Content-Type": "application/problem+json" } },
  );
}

function report(status: ReportStatus | string): ReportResponse {
  return {
    id: ID,
    status: status as ReportStatus,
    sourceType: "SAMPLE",
    createdAt: "2026-10-02T09:31:00Z",
    expiresAt: "2026-10-03T09:30:00Z",
    error: null,
    result: null,
  };
}

/** A live guest token, so nothing under test has to create a session first. */
function withGuestSession() {
  window.sessionStorage.setItem(
    GUEST_STORAGE_KEY,
    JSON.stringify({ token: "guest-abc", expiresAt: new Date(Date.now() + 3_600_000).toISOString() }),
  );
}

/** The URL and init of the nth fetch. */
function callAt(index: number) {
  const [url, init] = fetchMock.mock.calls[index] as [string, RequestInit];
  return { url, init };
}

function reportCalls() {
  return fetchMock.mock.calls.filter(([url]) => String(url).includes(`/api/reports/${ID}`));
}

beforeEach(() => {
  resetGuestSessionForTests();
  resetBackendStatusForTests();
  fetchMock = vi.fn();
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe("report ids", () => {
  it("accepts a UUID and nothing else", () => {
    expect(isReportId(ID)).toBe(true);
    expect(isReportId(ID.toUpperCase())).toBe(true);

    // A path segment someone typed, or a probe. Neither becomes a request.
    expect(isReportId("latest")).toBe(false);
    expect(isReportId("../admin")).toBe(false);
    expect(isReportId(`${ID}x`)).toBe(false);
    expect(isReportId("")).toBe(false);
  });
});

describe("an unknown status", () => {
  it("counts as still running, so polling carries on", () => {
    // Contract 9.2: a status this build has not heard of is far likelier to be a new
    // intermediate step than a finished report.
    expect(isProcessing("PENDING")).toBe(true);
    expect(isProcessing("PROCESSING")).toBe(true);
    expect(isProcessing("EXTRACTING")).toBe(true);
    expect(isProcessing(undefined)).toBe(true);

    expect(isProcessing("DONE")).toBe(false);
    expect(isProcessing("FAILED")).toBe(false);
  });
});

describe("creating a report", () => {
  beforeEach(withGuestSession);

  const created: CreateReportResponse = {
    id: ID,
    status: "PENDING",
    sourceType: "PDF",
    createdAt: "2026-10-02T09:31:00Z",
    expiresAt: "2026-10-03T09:30:00Z",
  };

  it("sends a file as multipart, with no Content-Type of its own", async () => {
    fetchMock.mockResolvedValueOnce(json(202, created));

    const file = new File(["%PDF-1.4"], "report.pdf", { type: "application/pdf" });
    await createReport({ kind: "file", file });

    const { url, init } = callAt(0);
    expect(url).toBe(`${API}/api/reports`);
    expect(init.body).toBeInstanceOf(FormData);

    // The browser has to set the multipart boundary. Naming the type ourselves omits it and
    // the server cannot parse a single part.
    const headers = init.headers as Record<string, string>;
    expect(headers["Content-Type"]).toBeUndefined();

    const form = init.body as FormData;
    expect(form.get("file")).toBe(file);
    // The contract wants the string, not a boolean, in a multipart part.
    expect(form.get("consent")).toBe("true");
  });

  it("sends pasted text as JSON with consent", async () => {
    fetchMock.mockResolvedValueOnce(json(202, { ...created, sourceType: "TEXT" }));

    await createReport({ kind: "text", text: "LIPID PANEL\nLDL 162 mg/dL" });

    const { init } = callAt(0);
    expect(JSON.parse(init.body as string)).toEqual({
      text: "LIPID PANEL\nLDL 162 mg/dL",
      consent: true,
    });
  });

  it("sends a sample id as JSON with consent", async () => {
    fetchMock.mockResolvedValueOnce(json(202, { ...created, sourceType: "SAMPLE" }));

    await createReport({ kind: "sample", sampleId: "lipid-panel" });

    expect(JSON.parse(callAt(0).init.body as string)).toEqual({
      sampleId: "lipid-panel",
      consent: true,
    });
  });

  it("sends the guest token and never an Authorization header", async () => {
    fetchMock.mockResolvedValueOnce(json(202, created));

    await createReport({ kind: "sample", sampleId: "cbc" });

    const headers = callAt(0).init.headers as Record<string, string>;
    expect(headers["X-Session-Token"]).toBe("guest-abc");
    expect(headers.Authorization).toBeUndefined();
  });

  it("starts a new session and tries once more when the old one died", async () => {
    // 24 hours can pass with the landing page open.
    fetchMock
      .mockResolvedValueOnce(problem(401, "SESSION_INVALID"))
      .mockResolvedValueOnce(json(201, { token: "guest-new", expiresAt: "2099-01-01T00:00:00Z" }))
      .mockResolvedValueOnce(json(202, created));

    await expect(createReport({ kind: "sample", sampleId: "cbc" })).resolves.toEqual(created);

    expect(callAt(1).url).toBe(`${API}/api/sessions`);
    expect((callAt(2).init.headers as Record<string, string>)["X-Session-Token"]).toBe("guest-new");
  });

  it("gives up after one retry rather than looping", async () => {
    fetchMock
      .mockResolvedValueOnce(problem(401, "SESSION_INVALID"))
      .mockResolvedValueOnce(json(201, { token: "guest-new", expiresAt: "2099-01-01T00:00:00Z" }))
      .mockResolvedValueOnce(problem(401, "SESSION_INVALID"))
      .mockResolvedValue(json(201, { token: "guest-3", expiresAt: "2099-01-01T00:00:00Z" }));

    await expect(createReport({ kind: "sample", sampleId: "cbc" })).rejects.toThrow(ApiError);
  });

  it("passes an upload error straight through", async () => {
    fetchMock.mockResolvedValueOnce(problem(413, "FILE_TOO_LARGE"));

    const file = new File(["x"], "huge.pdf", { type: "application/pdf" });
    await expect(createReport({ kind: "file", file })).rejects.toMatchObject({
      status: 413,
      code: "FILE_TOO_LARGE",
    });
  });
});

describe("deleting a report", () => {
  beforeEach(withGuestSession);

  it("treats 204 as done", async () => {
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }));
    await expect(deleteReport(ID)).resolves.toBeUndefined();
  });

  it("treats 404 as done too", async () => {
    // Already expired, or deleted in another tab. They asked for it to not exist, and it
    // does not exist.
    fetchMock.mockResolvedValueOnce(problem(404, "REPORT_NOT_FOUND"));
    await expect(deleteReport(ID)).resolves.toBeUndefined();
  });

  it("still reports a real failure", async () => {
    fetchMock.mockResolvedValueOnce(problem(500, "INTERNAL_ERROR"));
    await expect(deleteReport(ID)).rejects.toMatchObject({ status: 500 });
  });
});

describe("seeding the cache from the 202", () => {
  it("draws the page as PENDING without waiting for a round trip", () => {
    const client = createQueryClient();
    const created: CreateReportResponse = {
      id: ID,
      status: "PENDING",
      sourceType: "PDF",
      createdAt: "2026-10-02T09:31:00Z",
      expiresAt: "2026-10-03T09:30:00Z",
    };

    seedReport(client, created);

    expect(client.getQueryData(queryKeys.report(ID))).toEqual({
      ...created,
      error: null,
      result: null,
    });
  });
});

describe("polling", () => {
  function wrapper() {
    const client = createQueryClient();
    client.setDefaultOptions({ queries: { ...client.getDefaultOptions().queries, retry: false } });
    return function Wrapper({ children }: { children: ReactNode }) {
      return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
    };
  }

  beforeEach(() => {
    withGuestSession();
    vi.useFakeTimers({ shouldAdvanceTime: true });
  });

  it("asks again every 1.5 seconds while the report is running", async () => {
    fetchMock.mockResolvedValue(json(200, report("PROCESSING")));

    renderHook(() => useReport(ID), { wrapper: wrapper() });
    await waitFor(() => expect(reportCalls().length).toBe(1));

    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    await waitFor(() => expect(reportCalls().length).toBe(2));

    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    await waitFor(() => expect(reportCalls().length).toBe(3));
  });

  it("stops the moment the report is done", async () => {
    fetchMock
      .mockResolvedValueOnce(json(200, report("PROCESSING")))
      .mockResolvedValue(json(200, { ...report("DONE") }));

    const { result } = renderHook(() => useReport(ID), { wrapper: wrapper() });

    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    await waitFor(() => expect(result.current.report?.status).toBe("DONE"));

    const settled = reportCalls().length;
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 5);
    expect(reportCalls().length).toBe(settled);
  });

  it("stops when the report failed", async () => {
    fetchMock.mockResolvedValue(json(200, report("FAILED")));

    const { result } = renderHook(() => useReport(ID), { wrapper: wrapper() });
    await waitFor(() => expect(result.current.report?.status).toBe("FAILED"));

    const settled = reportCalls().length;
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 5);
    expect(reportCalls().length).toBe(settled);
  });

  it("keeps going on a status it has never heard of", async () => {
    fetchMock.mockResolvedValue(json(200, report("EMBEDDING")));

    renderHook(() => useReport(ID), { wrapper: wrapper() });
    await waitFor(() => expect(reportCalls().length).toBe(1));

    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS);
    await waitFor(() => expect(reportCalls().length).toBe(2));
  });

  it("stops asking after a 404, which is not going to change", async () => {
    fetchMock.mockResolvedValue(problem(404, "REPORT_NOT_FOUND"));

    const { result } = renderHook(() => useReport(ID), { wrapper: wrapper() });
    await waitFor(() => expect(result.current.error?.code).toBe("REPORT_NOT_FOUND"));

    const settled = reportCalls().length;
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 5);
    expect(reportCalls().length).toBe(settled);
  });

  it("stops asking after a dead session", async () => {
    fetchMock.mockResolvedValue(problem(401, "SESSION_INVALID"));

    const { result } = renderHook(() => useReport(ID), { wrapper: wrapper() });
    await waitFor(() => expect(result.current.error?.code).toBe("SESSION_INVALID"));

    const settled = reportCalls().length;
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 5);
    expect(reportCalls().length).toBe(settled);
  });

  it("never calls the API for an id that is not a UUID", async () => {
    renderHook(() => useReport("latest"), { wrapper: wrapper() });

    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 3);
    expect(reportCalls().length).toBe(0);
  });

  it("stops polling while the tab is hidden, and checks once on return", async () => {
    fetchMock.mockResolvedValue(json(200, report("PROCESSING")));

    renderHook(() => useReport(ID), { wrapper: wrapper() });
    await waitFor(() => expect(reportCalls().length).toBe(1));

    // Nobody is looking. TanStack keeps the interval ticking but skips the fetch unless
    // `refetchIntervalInBackground` is on, which it deliberately is not.
    focusManager.setFocused(false);
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 4);
    expect(reportCalls().length).toBe(1);

    // Coming back is worth one immediate check rather than up to 1.5 seconds of a stale page.
    focusManager.setFocused(true);
    await waitFor(() => expect(reportCalls().length).toBe(2));

    focusManager.setFocused(undefined);
  });

  it("gives up after two minutes and says so", async () => {
    fetchMock.mockResolvedValue(json(200, report("PROCESSING")));

    const { result } = renderHook(() => useReport(ID), { wrapper: wrapper() });
    await waitFor(() => expect(reportCalls().length).toBe(1));

    await vi.advanceTimersByTimeAsync(POLL_LIMIT_MS);
    await waitFor(() => expect(result.current.tookTooLong).toBe(true));

    // A tab left open overnight must not keep a free-tier backend awake by itself.
    const settled = reportCalls().length;
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 10);
    expect(reportCalls().length).toBe(settled);
  });

  it("carries on for another two minutes when asked to keep waiting", async () => {
    fetchMock.mockResolvedValue(json(200, report("PROCESSING")));

    const { result } = renderHook(() => useReport(ID), { wrapper: wrapper() });
    await vi.advanceTimersByTimeAsync(POLL_LIMIT_MS);
    await waitFor(() => expect(result.current.tookTooLong).toBe(true));

    const stalled = reportCalls().length;
    result.current.keepWaiting();

    await waitFor(() => expect(result.current.tookTooLong).toBe(false));
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 2);
    expect(reportCalls().length).toBeGreaterThan(stalled);
  });
});
