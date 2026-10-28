"use client";

import { Button, Card, Eyebrow } from "@/components/ds";
import { ConsentCheck } from "@/components/upload/consent-check";
import { FilePicker } from "@/components/upload/file-picker";
import { PasteBox } from "@/components/upload/paste-box";
import { SelectedSample } from "@/components/upload/selected-sample";
import { SourceTabs, UPLOAD_PANEL_ID } from "@/components/upload/source-tabs";
import { UploadErrorNotice } from "@/components/upload/upload-error-notice";
import { useUploadSource } from "@/components/upload/upload-source-context";

/**
 * "01 · Start here · Analyze a report".
 *
 * The one thing on the landing page that does work, so it sits above everything that
 * explains it. A sample chosen further down replaces the tabs here rather than appearing
 * beside them: there is one slot, and the button has to be unambiguous about what it sends.
 */
export function UploadPanel() {
  const { source, tab, sending, problem, failure, tooManyFiles, ready, missing, switchToPasting, submit } =
    useUploadSource();

  const showingSample = source.kind === "sample";

  return (
    <section id="upload" aria-labelledby="upload-heading" className="scroll-mt-[96px]">
      <Card variant="panel" className="relative overflow-hidden">
        <div className="flex flex-col gap-space-6 md:flex-row md:gap-space-8">
          <div className="flex items-start gap-space-5 md:w-[240px] md:shrink-0 md:flex-col md:gap-space-4">
            <span className="t-numeral-xl leading-none text-ink">01</span>
            <div>
              <Eyebrow>Start here</Eyebrow>
              <h2 id="upload-heading" className="t-title mt-space-2 text-ink">
                Analyze a report
              </h2>
              <p className="t-body-s mt-space-3 text-ink-2">
                No account needed. Guest reports are deleted automatically after 24 hours.
              </p>
            </div>
          </div>

          <div className="flex min-w-0 flex-1 flex-col gap-space-5">
            {showingSample ? <SelectedSample /> : <SourceTabs />}

            <div id={UPLOAD_PANEL_ID} role={showingSample ? undefined : "tabpanel"}>
              {showingSample ? null : tab === "file" ? <FilePicker /> : <PasteBox />}
            </div>

            <UploadErrorNotice
              problem={problem}
              failure={failure}
              tooManyFiles={tooManyFiles}
              onPasteInstead={switchToPasting}
              onRetry={submit}
            />

            <div className="flex flex-col gap-space-4 md:flex-row md:items-start md:justify-between">
              <div className="min-w-0 flex-1">
                <ConsentCheck />
              </div>

              <div className="flex flex-col items-stretch gap-space-2 md:items-end">
                <Button
                  variant="accent"
                  iconEnd="arrow-right"
                  disabled={!ready || sending}
                  onClick={submit}
                >
                  {sending ? "Sending…" : "Analyze report"}
                </Button>
                {missing && !sending && (
                  <p className="t-meta text-ink-3 md:text-right">{missing}</p>
                )}
              </div>
            </div>
          </div>
        </div>
      </Card>
    </section>
  );
}
