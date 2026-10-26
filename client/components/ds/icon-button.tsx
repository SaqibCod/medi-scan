"use client";

import type { ComponentPropsWithRef } from "react";

import { Icon, type IconName } from "./icon";
import { cx } from "./cx";

export interface IconButtonProps
  extends Omit<ComponentPropsWithRef<"button">, "children" | "aria-label"> {
  icon: IconName;
  /** Required: it is the button's only accessible name, and its hover title. */
  label: string;
  pressed?: boolean;
  expanded?: boolean;
  size?: number;
}

/**
 * Round 44px icon-only button. 44px on every screen, because it is the touch target.
 *
 * The remaining props are spread onto the `<button>`, and `ref` is forwarded, so this can be
 * handed to a headless primitive as its trigger — Base UI's Dialog merges `aria-expanded`,
 * `aria-controls` and its own handlers in that way, and needs the ref to return focus when
 * the dialog closes. Without the spread the button would render but stay silent about its
 * state.
 */
export function IconButton({
  icon,
  label,
  pressed,
  expanded,
  size = 18,
  className,
  ...rest
}: IconButtonProps) {
  return (
    <button
      type="button"
      className={cx("ms-iconbtn", className)}
      aria-label={label}
      title={label}
      aria-pressed={pressed}
      aria-expanded={expanded}
      {...rest}
    >
      <Icon name={icon} size={size} />
    </button>
  );
}
