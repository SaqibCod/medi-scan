"use client";

import { useState, type DragEvent } from "react";

import { Dropzone } from "@/components/ds";
import { cx } from "@/components/ds/cx";
import { FILE_ACCEPT, copy } from "@/lib/copy";
import { useUploadSource } from "./upload-source-context";

/**
 * Choosing a file, by drag, by drop, or by picker.
 *
 * Two layouts, as the design system asks: one wide drop area on desktop, two tiles on
 * phones. Both render, and CSS decides — a JavaScript breakpoint would get the first paint
 * wrong on one of them.
 *
 * The "Take a photo" tile stays visible while OCR is off (B5). It does not promise anything:
 * picking a photo shows the notice that explains why it cannot be read yet and offers the
 * thing that does work. A tile that disappeared would leave someone holding a paper report
 * with no idea the app had ever considered it.
 */
export function FilePicker() {
  const { source, chooseFiles } = useUploadSource();
  const [draggingOver, setDraggingOver] = useState(false);

  const chosen = source.kind === "file" ? source.file.name : null;

  function onDrop(event: DragEvent<HTMLDivElement>) {
    event.preventDefault();
    setDraggingOver(false);
    if (event.dataTransfer.files.length > 0) chooseFiles(event.dataTransfer.files);
  }

  return (
    <div
      onDragOver={(event) => {
        // Without this the browser navigates to the dropped file and the page is gone.
        event.preventDefault();
        setDraggingOver(true);
      }}
      onDragLeave={() => setDraggingOver(false)}
      onDrop={onDrop}
      className={cx("rounded-md transition-colors", draggingOver && "bg-accent-soft")}
    >
      {/* Desktop: one wide area. */}
      <div className="hidden md:block">
        <Dropzone
          accept={FILE_ACCEPT}
          hint={copy.dropzoneHint}
          onFiles={chooseFiles}
          title={
            chosen ?? (
              <>
                Drop your report here, or <em>browse</em>
              </>
            )
          }
        />
      </div>

      {/* Phone: two tiles, with the limit line once beneath them. */}
      <div className="md:hidden">
        <div className="grid grid-cols-2 gap-space-3">
          <Dropzone
            variant="tile"
            icon="file"
            accept={FILE_ACCEPT}
            onFiles={chooseFiles}
            title="Choose file"
          />
          <Dropzone
            variant="tile"
            icon="camera"
            // Deliberately not narrowed by the flag: the point of the tile while OCR is off
            // is that a photo reaches the check and gets an explanation.
            accept="image/png,image/jpeg"
            capture="environment"
            onFiles={chooseFiles}
            title={copy.takePhotoLabel}
          />
        </div>
        <p className="t-meta mt-space-3 text-ink-3">{copy.dropzoneHint}</p>
        {chosen && <p className="t-body-s mt-space-3 text-ink">{chosen}</p>}
      </div>
    </div>
  );
}
