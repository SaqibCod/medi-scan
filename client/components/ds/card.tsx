"use client";

import type { CSSProperties, ReactNode } from "react";

import { cx } from "./cx";

export interface CardProps {
  variant?: "surface" | "glass" | "panel";
  selected?: boolean;
  onClick?: () => void;
  children: ReactNode;
  style?: CSSProperties;
  className?: string;
}

/**
 * Rounded container.
 *
 * `glass` floats over the helix or a gradient; `panel` is the large upload panel. Passing
 * `onClick` turns it into a real `<button>` — not a div with a handler — so it is focusable
 * and works with the keyboard without any extra wiring.
 */
export function Card({ variant = "surface", selected, onClick, children, style, className }: CardProps) {
  const classes = cx(
    "ms-card",
    variant === "glass" && "ms-card--glass",
    variant === "panel" && "ms-card--glass ms-card--panel",
    selected && "ms-card--selected",
    onClick && "ms-card--interactive",
    className,
  );

  if (onClick) {
    return (
      <button type="button" className={classes} onClick={onClick} style={style}>
        {children}
      </button>
    );
  }

  return (
    <div className={classes} style={style}>
      {children}
    </div>
  );
}
