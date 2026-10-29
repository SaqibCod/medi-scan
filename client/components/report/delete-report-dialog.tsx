"use client";

import { useState } from "react";
import { AlertDialog } from "@base-ui/react/alert-dialog";

import { Button } from "@/components/ds";
import { InlineNotice } from "@/components/notices/inline-notice";
import { deleteReport } from "@/lib/reports";
import { ApiError } from "@/lib/api-error";

/**
 * "Delete this report?"
 *
 * Base UI's `AlertDialog` rather than `Dialog`, which is the same restyling job but refuses
 * to close on an outside click or a stray Escape-adjacent gesture. Deleting a report cannot
 * be undone, so dismissing it has to be a thing someone chose, not a thing that happened.
 * Focus is trapped, Escape cancels, and focus returns to the trigger — all from the
 * primitive. None of the look comes from it; every colour and shape here is a token.
 */
export function DeleteReportDialog({
  reportId,
  open,
  onOpenChange,
  onDeleted,
}: {
  reportId: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onDeleted: () => void;
}) {
  const [deleting, setDeleting] = useState(false);
  const [failure, setFailure] = useState<ApiError | null>(null);

  function confirm() {
    setDeleting(true);
    setFailure(null);

    void deleteReport(reportId)
      .then(() => {
        // 204 and 404 both land here: either way the report no longer exists, which is what
        // was asked for.
        onDeleted();
      })
      .catch((error: unknown) => {
        // The dialog stays open. Closing it on a failure would leave someone believing the
        // report was deleted when it was not.
        setFailure(
          error instanceof ApiError
            ? error
            : new ApiError({ status: 0, code: "UNKNOWN_ERROR", title: "", detail: "" }),
        );
        setDeleting(false);
      });
  }

  return (
    <AlertDialog.Root
      open={open}
      onOpenChange={(next) => {
        if (deleting) return;
        if (!next) setFailure(null);
        onOpenChange(next);
      }}
    >
      <AlertDialog.Portal>
        <AlertDialog.Backdrop className="fixed inset-0 z-40 bg-bg/80 backdrop-blur-[var(--blur-bar)]" />

        <AlertDialog.Popup className="fixed left-1/2 top-1/2 z-50 w-[calc(100vw-2rem)] max-w-[460px] -translate-x-1/2 -translate-y-1/2 rounded-lg border border-line bg-surface p-space-7">
          <AlertDialog.Title className="t-title text-ink">
            Delete this report?
          </AlertDialog.Title>

          <AlertDialog.Description className="t-body-s mt-space-4 text-ink-2">
            The file, its values and summary are removed from our server. This can&apos;t be
            undone.
          </AlertDialog.Description>

          {failure && (
            <div className="mt-space-5">
              <UploadFailureNotice failure={failure} onRetry={confirm} />
            </div>
          )}

          <div className="mt-space-7 flex flex-wrap justify-end gap-space-3">
            <AlertDialog.Close
              render={<Button variant="outline" disabled={deleting}>Cancel</Button>}
            />
            <Button variant="accent" icon="trash" disabled={deleting} onClick={confirm}>
              {deleting ? "Deleting…" : "Delete report"}
            </Button>
          </div>
        </AlertDialog.Popup>
      </AlertDialog.Portal>
    </AlertDialog.Root>
  );
}

/** A delete that failed is its own small case: it never reached the upload-error table. */
function UploadFailureNotice({ failure, onRetry }: { failure: ApiError; onRetry: () => void }) {
  const offline = failure.code === "NETWORK_ERROR" || failure.code === "TIMEOUT";

  return (
    <InlineNotice
      assertive
      title={offline ? "We couldn't reach the server." : "We couldn't delete this report."}
      code={failure.status > 0 ? `${failure.status} ${failure.code}` : undefined}
      action={
        <Button variant="outline" size="sm" onClick={onRetry}>
          Try again
        </Button>
      }
    >
      <p>
        {offline
          ? "Nothing was deleted. Try again when the server is back."
          : "The report is still there. Try again in a moment."}
      </p>
    </InlineNotice>
  );
}
