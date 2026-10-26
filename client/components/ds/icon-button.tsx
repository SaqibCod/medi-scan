"use client";

import type { CSSProperties } from "react";

import { Icon, type IconName } from "./icon";
import { cx } from "./cx";

export interface IconButtonProps {
  icon: IconName;
  /** Required: it is the button's only accessible name, and its hover title. */
  label: string;
  onClick?: () => void;
  pressed?: boolean;
  expanded?: boolean;
  controls?: string;
  size?: number;
  style?: CSSProperties;
  className?: string;
}

/** Round 44px icon-only button. 44px on every screen, because it is the touch target. */
export function IconButton({
  icon,
  label,
  onClick,
  pressed,
  expanded,
  controls,
  size = 18,
  style,
  className,
}: IconButtonProps) {
  return (
    <button
      type="button"
      className={cx("ms-iconbtn", className)}
      onClick={onClick}
      aria-label={label}
      title={label}
      aria-pressed={pressed}
      aria-expanded={expanded}
      aria-controls={controls}
      style={style}
    >
      <Icon name={icon} size={size} />
    </button>
  );
}
