"use client";

import type { CSSProperties, ReactNode } from "react";
import Link from "next/link";

import { Icon, type IconName } from "./icon";
import { cx } from "./cx";

export interface ButtonProps {
  variant?: "solid" | "outline" | "accent";
  size?: "sm" | "md" | "lg";
  block?: boolean;
  icon?: IconName;
  iconEnd?: IconName;
  href?: string;
  disabled?: boolean;
  type?: "button" | "submit";
  onClick?: () => void;
  children: ReactNode;
  style?: CSSProperties;
  className?: string;
  /** For the one case a link needs a name the visible label does not give (icon-only CTAs). */
  "aria-label"?: string;
}

/**
 * Pill button or link.
 *
 * `solid` is the main action of a section, `outline` the alternative beside it, and `accent`
 * is reserved for the submit that actually sends a report.
 */
export function Button({
  variant = "solid",
  size = "md",
  block,
  icon,
  iconEnd,
  href,
  disabled,
  type = "button",
  onClick,
  children,
  style,
  className,
  "aria-label": ariaLabel,
}: ButtonProps) {
  const classes = cx(
    "ms-btn",
    `ms-btn--${variant}`,
    size !== "md" && `ms-btn--${size}`,
    block && "ms-btn--block",
    className,
  );

  const content = (
    <>
      {icon && <Icon name={icon} size={size === "sm" ? 12 : 16} />}
      <span>{children}</span>
      {iconEnd && <Icon name={iconEnd} size={16} />}
    </>
  );

  if (href) {
    // An external href gets a plain anchor; next/link would try to client-navigate to it.
    // Anything starting with a single slash is ours.
    const internal = href.startsWith("/") && !href.startsWith("//");

    // A disabled link is not a thing in HTML. Rendering it as a non-focusable span keeps it
    // out of the tab order rather than offering a control that silently does nothing.
    if (disabled) {
      return (
        <span className={classes} aria-disabled="true" style={style} aria-label={ariaLabel}>
          {content}
        </span>
      );
    }

    if (internal) {
      return (
        <Link href={href} className={classes} onClick={onClick} style={style} aria-label={ariaLabel}>
          {content}
        </Link>
      );
    }

    return (
      <a
        href={href}
        className={classes}
        onClick={onClick}
        style={style}
        aria-label={ariaLabel}
        rel="noopener noreferrer"
      >
        {content}
      </a>
    );
  }

  return (
    <button
      type={type}
      className={classes}
      onClick={onClick}
      disabled={disabled}
      style={style}
      aria-label={ariaLabel}
    >
      {content}
    </button>
  );
}
