/**
 * A Server-Sent Events parser.
 *
 * Hand-written rather than a dependency, and the reason is the endpoint's shape: chat is a
 * POST with a body and custom credential headers, which the browser's built-in `EventSource`
 * cannot do at all — it is GET-only and sends no headers. So the stream has to be read from a
 * `fetch` response body either way, and once you are doing that, the remaining work is this
 * file. `@microsoft/fetch-event-source` would add a dependency to avoid about sixty lines.
 *
 * Written as a pure incremental parser with no `fetch` in sight, which is what makes it
 * table-testable: every case below is a string in and events out, with no network, no timers
 * and no jsdom streaming quirks.
 *
 * The parts of the spec that matter here:
 * - events are separated by a blank line
 * - `event:` names the event, `data:` carries the payload, repeated `data:` lines join with
 *   newlines
 * - a line beginning `:` is a comment and is ignored, which is what the server's `: ping`
 *   keep-alive is
 * - a single optional space after the colon is part of the syntax, not the value
 * - line endings may be LF, CRLF or CR
 */

/** One parsed event. `data` is the raw payload; callers decide how to read it. */
export interface SseEvent {
  event: string;
  data: string;
}

/** The default event name when a frame carries `data:` with no `event:` line. */
const DEFAULT_EVENT = "message";

export interface SseParser {
  /** Feeds a chunk and returns whatever events completed. */
  push(chunk: string): SseEvent[];
  /**
   * Returns a trailing event that never got its blank line.
   *
   * A server that closes the connection straight after the last `data:` line leaves one frame
   * unterminated. Dropping it would silently lose the `done` event, which is the one event
   * the client most needs.
   */
  flush(): SseEvent[];
}

export function createSseParser(): SseParser {
  // Holds the partial tail of a chunk that ended mid-line. Network chunks split wherever TCP
  // decided to, which is routinely in the middle of a word — and, for this endpoint, in the
  // middle of a JSON payload.
  let buffer = "";
  let eventName = "";
  let dataLines: string[] = [];

  function takeFrame(out: SseEvent[]) {
    if (dataLines.length === 0) {
      // A frame with only a comment, or only an `event:` line, carries nothing to deliver.
      eventName = "";
      return;
    }
    out.push({ event: eventName || DEFAULT_EVENT, data: dataLines.join("\n") });
    eventName = "";
    dataLines = [];
  }

  function handleLine(line: string, out: SseEvent[]) {
    if (line === "") {
      takeFrame(out);
      return;
    }
    if (line.startsWith(":")) {
      // A comment. `: ping` arrives every few seconds while the model is thinking, and
      // treating it as data would put a stray token in the answer.
      return;
    }

    const colon = line.indexOf(":");
    const field = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? "" : line.slice(colon + 1);
    // Exactly one leading space belongs to the syntax; a second one is content.
    if (value.startsWith(" ")) value = value.slice(1);

    if (field === "event") eventName = value;
    else if (field === "data") dataLines.push(value);
    // `id` and `retry` are valid fields this endpoint never sends; ignoring them is correct.
  }

  return {
    push(chunk: string): SseEvent[] {
      const out: SseEvent[] = [];
      buffer += chunk;

      // Split on any line ending, keeping the last piece as the possibly-partial tail.
      const lines = buffer.split(/\r\n|\n|\r/);
      buffer = lines.pop() ?? "";

      for (const line of lines) handleLine(line, out);
      return out;
    },

    flush(): SseEvent[] {
      const out: SseEvent[] = [];
      if (buffer !== "") {
        handleLine(buffer, out);
        buffer = "";
      }
      takeFrame(out);
      return out;
    },
  };
}
