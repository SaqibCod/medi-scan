"use client";

import type { CSSProperties, ReactNode } from "react";

export interface CheckboxProps {
  checked?: boolean;
  defaultChecked?: boolean;
  onChange?: (checked: boolean) => void;
  children: ReactNode;
  style?: CSSProperties;
}

/**
 * Checkbox with a wrapping label, so the whole label is a hit target.
 *
 * Its main job is the consent checkbox the upload API requires (`consent: true`), which keeps
 * the Analyze button disabled until it is ticked.
 */
export function Checkbox({ checked, defaultChecked, onChange, children, style }: CheckboxProps) {
  // React decides controlled vs uncontrolled from whether `checked` is present, so the two
  // props have to be mutually exclusive rather than both passed with one undefined.
  const controlled = checked !== undefined;

  return (
    <label className="ms-check" style={style}>
      <input
        type="checkbox"
        {...(controlled ? { checked } : { defaultChecked })}
        onChange={(e) => onChange?.(e.target.checked)}
      />
      <span>{children}</span>
    </label>
  );
}
