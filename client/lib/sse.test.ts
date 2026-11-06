import { describe, expect, it } from "vitest";

import { createSseParser } from "./sse";

/**
 * The SSE parser (LLD 10.3, 13).
 *
 * Every case is a string in and events out — no fetch, no timers, no jsdom. That is the point
 * of keeping the parser pure: the failures that actually happen on a network are chunk
 * boundaries in awkward places, and those are trivial to reproduce here and awkward to
 * reproduce through a real stream.
 */
describe("createSseParser", () => {
  /** Feeds every chunk and returns everything parsed, flush included. */
  function parseAll(...chunks: string[]) {
    const parser = createSseParser();
    const events = chunks.flatMap((chunk) => parser.push(chunk));
    return [...events, ...parser.flush()];
  }

  it("parses one complete event", () => {
    expect(parseAll('event: token\ndata: {"text":"hi"}\n\n')).toEqual([
      { event: "token", data: '{"text":"hi"}' },
    ]);
  });

  it("parses several events in one chunk", () => {
    const events = parseAll(
      'event: meta\ndata: {"cached":false}\n\nevent: token\ndata: {"text":"hi"}\n\n',
    );

    expect(events.map((e) => e.event)).toEqual(["meta", "token"]);
  });

  it("joins an event split across chunks", () => {
    // The case that drove the design: the live API splits wherever TCP decides to, and this
    // endpoint's payloads are JSON — half a payload is not parseable.
    expect(parseAll("event: tok", 'en\ndata: {"te', 'xt":"hi"}\n\n')).toEqual([
      { event: "token", data: '{"text":"hi"}' },
    ]);
  });

  it("joins an event split one character at a time", () => {
    const whole = 'event: done\ndata: {"finishReason":"COMPLETE"}\n\n';
    expect(parseAll(...whole.split(""))).toEqual([
      { event: "done", data: '{"finishReason":"COMPLETE"}' },
    ]);
  });

  it("ignores comment lines", () => {
    // `: ping` arrives every few seconds while the model is thinking. Treated as data it
    // would put a stray token in the middle of the answer.
    expect(parseAll(': ping\n\nevent: token\ndata: {"text":"hi"}\n\n')).toEqual([
      { event: "token", data: '{"text":"hi"}' },
    ]);
  });

  it("ignores a ping split across chunks", () => {
    expect(parseAll(": pi", "ng\n\n", 'event: token\ndata: {"text":"hi"}\n\n')).toEqual([
      { event: "token", data: '{"text":"hi"}' },
    ]);
  });

  it.each([
    ["LF", 'event: token\ndata: {"text":"hi"}\n\n'],
    ["CRLF", 'event: token\r\ndata: {"text":"hi"}\r\n\r\n'],
    ["CR", 'event: token\rdata: {"text":"hi"}\r\r'],
  ])("handles %s line endings", (_name, raw) => {
    expect(parseAll(raw)).toEqual([{ event: "token", data: '{"text":"hi"}' }]);
  });

  it("joins repeated data lines with newlines", () => {
    expect(parseAll("event: token\ndata: one\ndata: two\n\n")).toEqual([
      { event: "token", data: "one\ntwo" },
    ]);
  });

  it("strips exactly one space after the colon", () => {
    // A second space is content, not syntax — and the answer's own leading spaces matter,
    // because the client appends token payloads verbatim.
    expect(parseAll("event: token\ndata:  two spaces\n\n")).toEqual([
      { event: "token", data: " two spaces" },
    ]);
  });

  it("handles a field with no space after the colon", () => {
    expect(parseAll("event:token\ndata:{}\n\n")).toEqual([{ event: "token", data: "{}" }]);
  });

  it("flushes a final event that never got its blank line", () => {
    // A server closing straight after the last data line leaves the frame unterminated.
    // Dropping it would lose `done`, which is the event the client most needs.
    expect(parseAll('event: done\ndata: {"finishReason":"COMPLETE"}')).toEqual([
      { event: "done", data: '{"finishReason":"COMPLETE"}' },
    ]);
  });

  it("returns nothing for a frame with only a comment", () => {
    expect(parseAll(": ping\n\n")).toEqual([]);
  });

  it("returns nothing for an empty stream", () => {
    expect(parseAll("")).toEqual([]);
  });

  it("defaults the event name when only data is sent", () => {
    expect(parseAll("data: bare\n\n")).toEqual([{ event: "message", data: "bare" }]);
  });

  it("keeps payloads that themselves contain blank lines", () => {
    // Markdown answers contain blank lines between paragraphs. They arrive as separate
    // `data:` lines, which is exactly why repeated data lines join with a newline.
    expect(parseAll("event: token\ndata: one\ndata: \ndata: two\n\n")).toEqual([
      { event: "token", data: "one\n\ntwo" },
    ]);
  });

  it("does not emit an event until its blank line arrives", () => {
    const parser = createSseParser();

    expect(parser.push('event: token\ndata: {"text":"hi"}\n')).toEqual([]);
    expect(parser.push("\n")).toEqual([
      { event: "token", data: '{"text":"hi"}' },
    ]);
  });
});
