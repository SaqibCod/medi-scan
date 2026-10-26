"use client";

import type { CSSProperties } from "react";

import { cx } from "./cx";

export interface Step {
  title: string;
  body?: string;
}

export interface StepTimelineProps {
  steps: Step[];
  active: number;
  onSelect?: (index: number) => void;
  orientation?: "horizontal" | "vertical";
  style?: CSSProperties;
}

/** "01", "02", … — the numbering the mockups use. */
function stepNumber(index: number): string {
  return `${index < 9 ? "0" : ""}${index + 1}`;
}

/**
 * Numbered process steps with a hairline rail and a glowing active dot.
 *
 * Horizontal on desktop (labels only, the active body shown above it by the caller), vertical
 * on phones (the active step expands to show its body).
 */
export function StepTimeline({
  steps,
  active,
  onSelect,
  orientation = "horizontal",
  style,
}: StepTimelineProps) {
  const vertical = orientation === "vertical";

  return (
    <div
      className={cx("ms-timeline", vertical ? "ms-timeline--v" : "ms-timeline--h")}
      style={{ "--ms-steps": steps.length, ...style } as CSSProperties}
    >
      <div className="ms-timeline__rail" aria-hidden="true" />
      <div className="ms-timeline__list">
        {steps.map((step, i) => {
          const isActive = i === active;

          if (vertical) {
            return (
              <button
                key={step.title}
                type="button"
                className="ms-step"
                aria-expanded={isActive}
                onClick={() => onSelect?.(i)}
              >
                <span className="ms-step__dot" aria-hidden="true" />
                <span className="ms-step__head">
                  <span className="ms-step__num">{stepNumber(i)}</span>
                  <span className="ms-step__title">{step.title}</span>
                </span>
                {isActive && step.body && <span className="ms-step__body">{step.body}</span>}
              </button>
            );
          }

          return (
            <button
              key={step.title}
              type="button"
              className="ms-step"
              aria-pressed={isActive}
              onClick={() => onSelect?.(i)}
            >
              <span className="ms-step__dot" aria-hidden="true" />
              <span className="ms-step__label">{`${stepNumber(i)}  ${step.title}`}</span>
            </button>
          );
        })}
      </div>
    </div>
  );
}
