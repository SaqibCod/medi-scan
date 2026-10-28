"use client";

import { Tabs } from "@/components/ds";
import { useUploadSource } from "./upload-source-context";

export const UPLOAD_PANEL_ID = "upload-source-panel";

/**
 * "Upload file" or "Paste text".
 *
 * Full width on phones, where the two options are the whole decision and a centred pill with
 * space either side of it looks like a label rather than a control. On desktop the wrapper
 * caps it so it reads as the small segmented pill in the mockup.
 */
export function SourceTabs() {
  const { tab, setTab } = useUploadSource();

  return (
    <div className="md:max-w-60">
      <Tabs
        label="How to add your report"
        panelId={UPLOAD_PANEL_ID}
        value={tab}
        onChange={(value) => setTab(value === "text" ? "text" : "file")}
        items={[
          { value: "file", label: "Upload file" },
          { value: "text", label: "Paste text" },
        ]}
        block
      />
    </div>
  );
}
