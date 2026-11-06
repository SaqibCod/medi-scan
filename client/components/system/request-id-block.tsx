"use client";

import { Button } from "@/components/ds";
import { useToast } from "@/components/notices/toaster";

/**
 * The request id, with a button that copies it (mockup `--05`).
 *
 * The id is the only thing that connects what a person saw to the line in the server log, so
 * it has to be selectable and copyable rather than just printed. Set in the mono `data` style
 * because it is a value to be read character by character.
 */
export function RequestIdBlock({ requestId }: { requestId: string }) {
  const toast = useToast();

  async function copy() {
    try {
      await navigator.clipboard.writeText(requestId);
      toast({ title: "Copied", body: "The request ID is on your clipboard.", icon: "file" });
    } catch {
      // Clipboard access can be refused, and there is nothing useful to do about it — the id
      // is on screen and selectable, which is the fallback.
      toast({ title: "Couldn't copy", body: "Select the ID and copy it by hand.", icon: "file" });
    }
  }

  return (
    <div className="inline-flex flex-col gap-space-3 rounded-lg border border-line bg-surface p-space-5">
      <p className="t-meta text-ink-3">REQUEST ID</p>
      <p className="t-data select-all break-all text-ink">{requestId}</p>
      <div>
        <Button variant="outline" size="sm" onClick={copy}>
          Copy ID
        </Button>
      </div>
    </div>
  );
}
