import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { streamChat } from "./chat";

/**
 * The streaming client (LLD 10.3, 13).
 *
 * Driven against a stubbed `fetch` returning a real `ReadableStream`, so the reader loop, the
 * decoder and the parser are all exercised — but with no network and no backend. The
 * alternative, MSW, would test MSW's streaming support as much as this code.
 */
describe("streamChat", () => {
  const handlers = () => ({
    onMeta: vi.fn(),
    onToken: vi.fn(),
    onSources: vi.fn(),
    onDone: vi.fn(),
    onError: vi.fn(),
  });

  /** A response whose body yields each chunk in turn, as a network would. */
  function streamOf(...chunks: string[]): Response {
    const encoder = new TextEncoder();
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        for (const chunk of chunks) controller.enqueue(encoder.encode(chunk));
        controller.close();
      },
    });
    return new Response(body, {
      status: 200,
      headers: { "Content-Type": "text/event-stream" },
    });
  }

  beforeEach(() => {
    vi.stubGlobal("sessionStorage", {
      getItem: () => JSON.stringify({ token: "test-token", expiresAt: "2099-01-01T00:00:00Z" }),
      setItem: () => {},
      removeItem: () => {},
    });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("delivers meta, tokens, sources and done in order", async () => {
    const h = handlers();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        streamOf(
          'event: meta\ndata: {"answerId":"a1","cached":false}\n\n',
          'event: token\ndata: {"text":"Your LDL "}\n\n',
          'event: token\ndata: {"text":"is 162 mg/dL."}\n\n',
          'event: sources\ndata: {"report":true,"pages":[{"slug":"ldl-cholesterol","title":"LDL","heading":"What it measures"}]}\n\n',
          'event: done\ndata: {"finishReason":"COMPLETE"}\n\n',
        ),
      ),
    );

    await streamChat("r1", "Why?", [], new AbortController().signal, h);

    expect(h.onMeta).toHaveBeenCalledWith({ answerId: "a1", cached: false });
    expect(h.onToken.mock.calls.map((c) => c[0])).toEqual(["Your LDL ", "is 162 mg/dL."]);
    expect(h.onSources).toHaveBeenCalledWith(
      [{ slug: "ldl-cholesterol", title: "LDL", heading: "What it measures" }],
      true,
    );
    expect(h.onDone).toHaveBeenCalledWith("COMPLETE");
    expect(h.onError).not.toHaveBeenCalled();
  });

  it("ignores ping comments between events", async () => {
    const h = handlers();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        streamOf(': ping\n\n', 'event: token\ndata: {"text":"hi"}\n\n', ": ping\n\n"),
      ),
    );

    await streamChat("r1", "Why?", [], new AbortController().signal, h);

    expect(h.onToken.mock.calls.map((c) => c[0])).toEqual(["hi"]);
  });

  it("ignores unknown event names", async () => {
    // Contract 4.4. A server that gains an event must not break an older client.
    const h = handlers();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        streamOf('event: surprise\ndata: {}\n\n', 'event: done\ndata: {"finishReason":"COMPLETE"}\n\n'),
      ),
    );

    await streamChat("r1", "Why?", [], new AbortController().signal, h);

    expect(h.onDone).toHaveBeenCalledWith("COMPLETE");
  });

  it("treats an unknown finishReason as COMPLETE", async () => {
    // Contract 9.2's rule for unknown enum values.
    const h = handlers();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(streamOf('event: done\ndata: {"finishReason":"WAT"}\n\n')),
    );

    await streamChat("r1", "Why?", [], new AbortController().signal, h);

    expect(h.onDone).toHaveBeenCalledWith("COMPLETE");
  });

  it("reports a mid-stream error event", async () => {
    const h = handlers();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        streamOf(
          'event: token\ndata: {"text":"partial"}\n\n',
          'event: error\ndata: {"code":"CAPACITY","message":"limit"}\n\n',
        ),
      ),
    );

    await streamChat("r1", "Why?", [], new AbortController().signal, h);

    // The partial answer still arrived, and no `done` follows an error.
    expect(h.onToken).toHaveBeenCalledWith("partial");
    expect(h.onError).toHaveBeenCalledWith({ code: "CAPACITY", retryAt: null });
    expect(h.onDone).not.toHaveBeenCalled();
  });

  it("throws the contract's error for a refusal before the stream", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({ status: 409, code: "REPORT_NOT_READY", title: "x", detail: "y" }),
          { status: 409, headers: { "Content-Type": "application/problem+json" } },
        ),
      ),
    );

    await expect(
      streamChat("r1", "Why?", [], new AbortController().signal, handlers()),
    ).rejects.toMatchObject({ code: "REPORT_NOT_READY" });
  });

  it("resolves quietly when the caller aborts", async () => {
    // Stop being pressed is not a failure to report as one.
    const controller = new AbortController();
    controller.abort();
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new DOMException("aborted", "AbortError")));

    await expect(
      streamChat("r1", "Why?", [], controller.signal, handlers()),
    ).resolves.toBeUndefined();
  });

  it("sends the question and history in the body", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(streamOf('event: done\ndata: {"finishReason":"COMPLETE"}\n\n'));
    vi.stubGlobal("fetch", fetchMock);

    await streamChat(
      "r1",
      "And the other one?",
      [{ role: "user", content: "earlier" }],
      new AbortController().signal,
      handlers(),
    );

    const body = JSON.parse(fetchMock.mock.calls[0][1].body);
    expect(body).toEqual({
      question: "And the other one?",
      history: [{ role: "user", content: "earlier" }],
    });
  });

  it("asks for an event stream and sends the guest credential", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(streamOf('event: done\ndata: {"finishReason":"COMPLETE"}\n\n'));
    vi.stubGlobal("fetch", fetchMock);

    await streamChat("r1", "Why?", [], new AbortController().signal, handlers());

    const init = fetchMock.mock.calls[0][1];
    expect(init.headers.Accept).toBe("text/event-stream");
    expect(init.headers["X-Session-Token"]).toBe("test-token");
    // No cookie ever carries a credential here.
    expect(init.credentials).toBe("omit");
  });
});
