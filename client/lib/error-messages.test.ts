import { describe, expect, it } from "vitest";

import { ApiError } from "./api-error";
import { HTTP_ERROR_CODES, REPORT_ERROR_CODES } from "./api-types";
import { COVERED_CODES, messageForCode, messageForError, REQUIRED_CODES } from "./error-messages";

describe("coverage", () => {
  it("every code in the contract has wording", () => {
    // Reads the lists from api-types.ts, which mirror contract 8.1 and 8.2. Adding a code to
    // the contract without deciding what the user is told fails here.
    const missing = REQUIRED_CODES.filter((code) => !messageForCode(code));

    expect(missing, `No UI wording for: ${missing.join(", ")}`).toEqual([]);
  });

  it("has no wording for codes that do not exist", () => {
    // Catches a typo'd key, and wording left behind after a code was removed.
    const stray = COVERED_CODES.filter((code) => !REQUIRED_CODES.includes(code));

    expect(stray, `Wording for unknown codes: ${stray.join(", ")}`).toEqual([]);
  });

  it.each([...HTTP_ERROR_CODES, ...REPORT_ERROR_CODES])("%s reads as a sentence", (code) => {
    const message = messageForCode(code)!;

    expect(message.title.length).toBeGreaterThan(0);
    expect(message.body.length).toBeGreaterThan(0);
    // The voice rules: statements, sentence case, ending in a full stop.
    expect(message.title).toMatch(/[.?]$/);
    expect(message.title).not.toMatch(/^[a-z]/);
  });
});

describe("voice", () => {
  it("never shouts and never blames", () => {
    for (const code of REQUIRED_CODES) {
      const { title, body } = messageForCode(code)!;
      const text = `${title} ${body}`;

      expect(text, code).not.toMatch(/!{1,}/);
      expect(text, code).not.toMatch(/\b(oops|sorry|whoops|uh-oh)\b/i);
      // No emoji anywhere (design system).
      expect(text, code).not.toMatch(/\p{Extended_Pictographic}/u);
    }
  });

  it("states the real limits, not invented ones", () => {
    expect(messageForCode("FILE_TOO_LARGE")!.body).toContain("10 MB");
    expect(messageForCode("UNSUPPORTED_FILE_TYPE")!.body).toContain("PDF");
    expect(messageForCode("SESSION_INVALID")!.body).toContain("24 hours");
    expect(messageForCode("SESSION_INVALID")!.body).toContain("30 days");
  });

  it("offers the request id only where the server is at fault", () => {
    expect(messageForCode("INTERNAL_ERROR")!.showRequestId).toBe(true);
    // A rejected file is not a server fault, and a request id means nothing to the person.
    expect(messageForCode("FILE_TOO_LARGE")!.showRequestId).toBeUndefined();
  });
});

describe("choosing a message", () => {
  it("switches on the code, not on the server's prose", () => {
    const error = new ApiError({
      status: 413,
      code: "FILE_TOO_LARGE",
      title: "Some other wording entirely",
      detail: "The file is 14.2 MB. The limit is 10 MB.",
    });

    // The server's title and detail are for a human reading a log and may be reworded at any
    // time; the UI must not depend on them (root CLAUDE.md).
    expect(messageForError(error).title).toBe("This file is too large.");
  });

  it.each([
    [401, "SESSION_INVALID"],
    [403, "FORBIDDEN"],
    [404, "REPORT_NOT_FOUND"],
    [429, "RATE_LIMITED"],
    [500, "INTERNAL_ERROR"],
    [502, "INTERNAL_ERROR"],
    [400, "VALIDATION_ERROR"],
  ])("falls back by status (%i) when the code is unknown", (status, expectedCode) => {
    const error = new ApiError({ status, code: "INVENTED_LATER" });

    expect(messageForError(error)).toEqual(messageForCode(expectedCode));
  });

  it("has something to say even for a status it has never seen", () => {
    const error = new ApiError({ status: 418, code: "TEAPOT" });

    expect(messageForError(error).title.length).toBeGreaterThan(0);
  });
});
