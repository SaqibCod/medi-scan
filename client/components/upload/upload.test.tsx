import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { ApiError } from "@/lib/api-error";
import type { UploadProblem } from "@/lib/upload-source";
import { UploadErrorNotice } from "./upload-error-notice";

/**
 * Every row of LLD 5.4, rendered.
 *
 * The table is the contract between the error codes and what a person reads, so each row is
 * asserted on rather than spot-checked: a code that quietly falls through to a generic
 * message is the failure this is here to catch.
 */

function apiError(status: number, code: string, extra: Record<string, unknown> = {}) {
  return new ApiError({ status, code, title: code, detail: "server wording", ...extra });
}

afterEach(() => {
  vi.useRealTimers();
});

describe("what the browser caught before sending", () => {
  const cases: Array<[string, UploadProblem, RegExp, string | null]> = [
    [
      "too large",
      { kind: "file-too-large", size: "14.2 MB" },
      /It is 14\.2 MB and the limit is 10 MB\./,
      "413 FILE_TOO_LARGE",
    ],
    ["photo", { kind: "photo-not-yet" }, /Photos are coming soon\./, null],
    [
      "wrong type",
      { kind: "wrong-type" },
      /Upload a PDF, or paste the text of your report\./,
      "415 UNSUPPORTED_FILE_TYPE",
    ],
    [
      "too short",
      { kind: "text-too-short" },
      /Paste at least 20 characters, including the test names, values and ranges\./,
      null,
    ],
    [
      "too long",
      { kind: "text-too-long" },
      /Paste up to 50,000 characters\./,
      null,
    ],
  ];

  it.each(cases)("%s says what happened", (_name, problem, body, code) => {
    render(<UploadErrorNotice problem={problem} />);

    expect(screen.getByText(body)).toBeInTheDocument();
    if (code) expect(screen.getByText(code)).toBeInTheDocument();
  });

  it("offers pasting instead of a photo, and nothing else does", async () => {
    const onPasteInstead = vi.fn();
    const user = userEvent.setup();

    const { rerender } = render(
      <UploadErrorNotice problem={{ kind: "photo-not-yet" }} onPasteInstead={onPasteInstead} />,
    );
    await user.click(screen.getByRole("button", { name: "Paste text instead" }));
    expect(onPasteInstead).toHaveBeenCalledOnce();

    rerender(<UploadErrorNotice problem={{ kind: "wrong-type" }} onPasteInstead={onPasteInstead} />);
    expect(screen.queryByRole("button", { name: "Paste text instead" })).not.toBeInTheDocument();
  });

  it("interrupts a screen reader, because focus is moving here", () => {
    render(<UploadErrorNotice problem={{ kind: "wrong-type" }} />);
    expect(screen.getByRole("alert")).toBeInTheDocument();
  });
});

describe("what the server said", () => {
  const cases: Array<[string, ApiError, RegExp, string]> = [
    [
      "FILE_TOO_LARGE",
      apiError(413, "FILE_TOO_LARGE"),
      /The limit is 10 MB\./,
      "413 FILE_TOO_LARGE",
    ],
    [
      "UNSUPPORTED_FILE_TYPE",
      apiError(415, "UNSUPPORTED_FILE_TYPE"),
      /Upload a PDF, or paste the text/,
      "415 UNSUPPORTED_FILE_TYPE",
    ],
    [
      "FILE_SIGNATURE_MISMATCH",
      apiError(400, "FILE_SIGNATURE_MISMATCH"),
      /Its contents do not match its name\./,
      "400 FILE_SIGNATURE_MISMATCH",
    ],
    [
      "BUSY",
      apiError(429, "BUSY"),
      /Your report was not sent\. Try again in a minute\./,
      "429 BUSY",
    ],
    [
      "CAPACITY",
      apiError(429, "CAPACITY"),
      /New reports open again at midnight UTC\./,
      "429 CAPACITY",
    ],
    [
      "SAMPLE_NOT_FOUND",
      apiError(404, "SAMPLE_NOT_FOUND"),
      /Pick another sample, or upload your own report\./,
      "404 SAMPLE_NOT_FOUND",
    ],
  ];

  it.each(cases)("%s renders its own copy", (_name, failure, body, code) => {
    render(<UploadErrorNotice failure={failure} />);

    expect(screen.getByText(body)).toBeInTheDocument();
    expect(screen.getByText(code)).toBeInTheDocument();
  });

  it("never shows the server's own wording as the message", () => {
    render(<UploadErrorNotice failure={apiError(429, "BUSY")} />);

    // Root rule: `title` and `detail` are written for a log and may be reworded at any time.
    expect(screen.queryByText(/server wording/)).not.toBeInTheDocument();
  });

  it("names the fields a validation error complained about, in plain words", () => {
    render(
      <UploadErrorNotice
        failure={apiError(400, "VALIDATION_ERROR", {
          errors: [{ field: "consent", message: "must be true" }],
        })}
      />,
    );

    expect(screen.getByText(/Something in the request wasn't right\./)).toBeInTheDocument();
    expect(screen.getByText(/the consent box/)).toBeInTheDocument();
    // Not the raw validator message.
    expect(screen.queryByText(/must be true/)).not.toBeInTheDocument();
  });

  it("quotes the request id on a server fault", () => {
    render(
      <UploadErrorNotice failure={apiError(500, "INTERNAL_ERROR", { requestId: "req-77" })} />,
    );

    expect(screen.getByText(/Something broke on our side\./)).toBeInTheDocument();
    expect(screen.getByText(/Request ID req-77\./)).toBeInTheDocument();
  });

  it("says a dropped connection did not send the report", () => {
    render(<UploadErrorNotice failure={apiError(0, "NETWORK_ERROR")} />);

    // The reassurance that matters: nothing was half-uploaded.
    expect(screen.getByText(/Your report was not sent\./)).toBeInTheDocument();
  });

  it.each(["BUSY", "NETWORK_ERROR", "INTERNAL_ERROR"])("%s offers a retry", async (code) => {
    const onRetry = vi.fn();
    const user = userEvent.setup();
    const status = code === "BUSY" ? 429 : code === "NETWORK_ERROR" ? 0 : 500;

    render(<UploadErrorNotice failure={apiError(status, code)} onRetry={onRetry} />);
    await user.click(screen.getByRole("button", { name: "Try again" }));

    expect(onRetry).toHaveBeenCalledOnce();
  });

  it("does not offer a retry for something retrying cannot fix", () => {
    render(<UploadErrorNotice failure={apiError(413, "FILE_TOO_LARGE")} onRetry={vi.fn()} />);
    expect(screen.queryByRole("button", { name: "Try again" })).not.toBeInTheDocument();
  });

  it("falls back for a code this build has never heard of", () => {
    // Contract 9.2: an unknown code is a generic error, not a blank notice.
    render(<UploadErrorNotice failure={apiError(400, "SOMETHING_NEW")} />);

    expect(screen.getByText(/That didn't go through\./)).toBeInTheDocument();
    expect(screen.getByText("400 SOMETHING_NEW")).toBeInTheDocument();
  });

  it("wins over a stale client-side problem", () => {
    // The server's answer is about the attempt that just happened.
    render(
      <UploadErrorNotice
        problem={{ kind: "file-too-large", size: "14.2 MB" }}
        failure={apiError(429, "BUSY")}
      />,
    );

    expect(screen.getByText(/Lots of reports are being read right now\./)).toBeInTheDocument();
    expect(screen.queryByText(/14\.2 MB/)).not.toBeInTheDocument();
  });
});

describe("the rate-limit countdown", () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
  });

  it("formats the wait the way the mockup does", () => {
    render(
      <UploadErrorNotice failure={apiError(429, "RATE_LIMITED", { retryAfterSec: 17 * 60 + 51 })} />,
    );

    expect(screen.getByText(/You've reached this hour's upload limit\./)).toBeInTheDocument();
    expect(screen.getByText(/Try again in 17 min 51 s\./)).toBeInTheDocument();
  });

  it("ticks down every second", async () => {
    render(<UploadErrorNotice failure={apiError(429, "RATE_LIMITED", { retryAfterSec: 65 })} />);

    expect(screen.getByText(/1 min 5 s/)).toBeInTheDocument();

    await vi.advanceTimersByTimeAsync(3000);
    expect(screen.getByText(/1 min 2 s/)).toBeInTheDocument();

    await vi.advanceTimersByTimeAsync(3000);
    expect(screen.getByText(/59 s/)).toBeInTheDocument();
  });

  it("stops at zero rather than counting into the negatives", async () => {
    render(<UploadErrorNotice failure={apiError(429, "RATE_LIMITED", { retryAfterSec: 2 })} />);

    await vi.advanceTimersByTimeAsync(10_000);
    expect(screen.getByText(/Try again in 0 s\./)).toBeInTheDocument();
  });

  it("says something useful when the server gave no Retry-After", () => {
    render(<UploadErrorNotice failure={apiError(429, "RATE_LIMITED")} />);
    expect(screen.getByText(/Try again shortly\./)).toBeInTheDocument();
  });
});

describe("a drop carrying several files", () => {
  it("is information, not a failure", () => {
    render(<UploadErrorNotice tooManyFiles />);

    const notice = screen.getByRole("status");
    expect(within(notice).getByText("Only one file at a time.")).toBeInTheDocument();
    // Polite, not assertive: nothing was refused and nothing needs fixing.
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});

describe("when nothing is wrong", () => {
  it("renders nothing at all", () => {
    const { container } = render(<UploadErrorNotice />);
    expect(container).toBeEmptyDOMElement();
  });
});
