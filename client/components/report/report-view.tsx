"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";

import { useNotices } from "@/components/notices/notice-provider";
import { useToast } from "@/components/notices/toaster";
import {
  GuestSessionEndedScreen,
  ReportNotAvailableScreen,
  ServerErrorScreen,
  ServerNotRespondingScreen,
} from "@/components/system/screens";
import { GUEST_SESSION_ENDED } from "@/lib/guest-session";
import { isProcessing, isReportId, useForgetReport, useReport } from "@/lib/reports";
import { DeleteReportDialog } from "./delete-report-dialog";
import { FailedView } from "./failed-view";
import { ProcessingView } from "./processing-view";
import { ResultsView } from "./results-view";

/**
 * Which of the report page's states to show.
 *
 * Every branch is driven by the error `code` rather than by the status number or the
 * server's wording, as the root rules require. The order matters: a dead session has to win
 * over "report not found", because the report may well exist — the caller just stopped being
 * anyone who can see it.
 */
export function ReportView({ id }: { id: string }) {
  const router = useRouter();
  const toast = useToast();
  const forgetReport = useForgetReport();
  const { backend } = useNotices();

  const { report, error, tookTooLong, keepWaiting } = useReport(id);
  const [deleting, setDeleting] = useState(false);
  const [sessionEnded, setSessionEnded] = useState(false);

  // A guest session can die while this page is open — the poll is the thing most likely to
  // find out. 7a publishes the event from the API client; the page decides what to show.
  useEffect(() => {
    const onEnded = () => setSessionEnded(true);
    window.addEventListener(GUEST_SESSION_ENDED, onEnded);
    return () => window.removeEventListener(GUEST_SESSION_ENDED, onEnded);
  }, []);

  const onDeleted = useCallback(() => {
    setDeleting(false);
    forgetReport(id);
    toast({
      icon: "trash",
      title: "Report deleted.",
      body: "The file, its values and summary are gone from our server.",
    });
    router.push("/");
  }, [forgetReport, id, router, toast]);

  // A malformed id is a 404 without asking the server. `useReport` already refuses to fetch.
  if (!isReportId(id)) return <ReportNotAvailableScreen />;

  if (sessionEnded || error?.code === "SESSION_INVALID") return <GuestSessionEndedScreen />;
  if (error?.code === "REPORT_NOT_FOUND") return <ReportNotAvailableScreen />;

  if (error) {
    // The backend checker is the authority on whether this is "the server is asleep" or
    // "the server answered with a fault", and it already knows.
    if (backend.state === "DOWN") return <ServerNotRespondingScreen />;
    if (!error.isRetryable) return <ServerErrorScreen requestId={error.requestId} />;
    if (backend.state !== "WAKING") {
      return <ServerErrorScreen requestId={error.requestId} onRetry={keepWaiting} />;
    }
    // Waking: the 7a banner is already explaining it and polling continues underneath.
  }

  const dialog = (
    <DeleteReportDialog
      reportId={id}
      open={deleting}
      onOpenChange={setDeleting}
      onDeleted={onDeleted}
    />
  );

  if (!report) return <ProcessingSkeleton />;

  if (report.status === "FAILED") {
    return (
      <>
        <FailedView report={report} onDelete={() => setDeleting(true)} />
        {dialog}
      </>
    );
  }

  if (isProcessing(report.status)) {
    return (
      <>
        <ProcessingView
          report={report}
          tookTooLong={tookTooLong}
          onKeepWaiting={keepWaiting}
          onDelete={() => setDeleting(true)}
        />
        {dialog}
      </>
    );
  }

  return (
    <>
      <ResultsView report={report} onDelete={() => setDeleting(true)} />
      {dialog}
    </>
  );
}

/**
 * The first moment, before the first response.
 *
 * Usually invisible: an upload seeds the cache with the `202` body, so the real processing
 * view draws immediately. This is what a report opened in a fresh tab sees for one round
 * trip.
 */
function ProcessingSkeleton() {
  return (
    <div className="py-space-9" role="status" aria-label="Loading your report">
      <div className="h-4 w-40 rounded-sm bg-surface-2" />
      <div className="mt-space-5 h-12 w-2/3 rounded-sm bg-surface-2" />
      <div className="mt-space-3 h-12 w-1/2 rounded-sm bg-surface-2" />
    </div>
  );
}
