"use client";

import type { CSSProperties, ReactNode } from "react";

import { Icon, type IconName } from "./icon";
import { cx } from "./cx";

export interface DropzoneProps {
  variant?: "wide" | "tile";
  icon?: IconName;
  title?: ReactNode;
  hint?: string;
  accept?: string;
  capture?: "environment" | "user";
  onFiles?: (files: FileList) => void;
  style?: CSSProperties;
}

/** Matches what the upload endpoint accepts. */
const DEFAULT_ACCEPT = "application/pdf,image/png,image/jpeg";

/**
 * File picker styled as a dashed drop area.
 *
 * The real `<input type="file">` is the control — it is visually hidden rather than replaced,
 * so it keeps its keyboard behaviour, its screen-reader role and the native picker. ds.css
 * draws the focus ring on the label when that input is focused, because a 1px transparent
 * input cannot show one itself (LLD 5).
 */
export function Dropzone({
  variant = "wide",
  icon,
  title,
  hint,
  accept,
  capture,
  onFiles,
  style,
}: DropzoneProps) {
  const tile = variant === "tile";

  return (
    <label className={cx("ms-drop", tile && "ms-drop--tile")} style={style}>
      <input
        type="file"
        accept={accept ?? DEFAULT_ACCEPT}
        capture={capture}
        onChange={(e) => {
          if (e.target.files) onFiles?.(e.target.files);
        }}
      />
      <span className="ms-drop__icon">
        <Icon name={icon ?? "file"} size={tile ? 20 : 24} />
      </span>
      <span style={{ display: "flex", flexDirection: "column", gap: 4 }}>
        <span className="ms-drop__title">
          {title ?? (
            <>
              Drop your report here, or <em>browse</em>
            </>
          )}
        </span>
        {!tile && <span className="ms-drop__hint">{hint ?? "PDF · PNG · JPEG — up to 10 MB"}</span>}
      </span>
    </label>
  );
}
