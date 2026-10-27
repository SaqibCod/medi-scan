"use client";

import { useId, useRef, type KeyboardEvent } from "react";

import { cx } from "./cx";

export type TabItem = string | { value: string; label: string };

export interface TabsProps {
  items: TabItem[];
  value: string;
  onChange: (value: string) => void;
  label?: string;
  block?: boolean;
  /** Id of the panel each tab controls, so the tab can point at it. */
  panelId?: string;
}

function toTab(item: TabItem): { value: string; label: string } {
  return typeof item === "string" ? { value: item, label: item } : item;
}

/**
 * Segmented control. Two or three options only.
 *
 * The design system's bundle only handled clicks. A `tablist` is expected to work with the
 * arrow keys — one tab stop for the whole group, arrows to move between options — so that is
 * added here, along with `aria-controls` (LLD 5).
 */
export function Tabs({ items, value, onChange, label, block, panelId }: TabsProps) {
  const tabs = items.map(toTab);
  const refs = useRef<(HTMLButtonElement | null)[]>([]);
  const generatedId = useId();

  function focusTab(index: number) {
    const wrapped = (index + tabs.length) % tabs.length;
    refs.current[wrapped]?.focus();
    onChange(tabs[wrapped].value);
  }

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    const current = tabs.findIndex((t) => t.value === value);
    if (current < 0) return;

    switch (event.key) {
      case "ArrowRight":
      case "ArrowDown":
        event.preventDefault();
        focusTab(current + 1);
        break;
      case "ArrowLeft":
      case "ArrowUp":
        event.preventDefault();
        focusTab(current - 1);
        break;
      case "Home":
        event.preventDefault();
        focusTab(0);
        break;
      case "End":
        event.preventDefault();
        focusTab(tabs.length - 1);
        break;
    }
  }

  return (
    <div
      role="tablist"
      aria-label={label}
      className={cx("ms-tabs", block && "ms-tabs--block")}
      onKeyDown={onKeyDown}
    >
      {tabs.map((tab, i) => {
        const selected = tab.value === value;
        return (
          <button
            key={tab.value}
            ref={(el) => {
              refs.current[i] = el;
            }}
            type="button"
            role="tab"
            id={`${generatedId}-${tab.value}`}
            className="ms-tab"
            aria-selected={selected}
            aria-controls={panelId}
            // One tab stop for the group: Tab moves past the control, arrows move within it.
            tabIndex={selected ? 0 : -1}
            onClick={() => onChange(tab.value)}
          >
            {tab.label}
          </button>
        );
      })}
    </div>
  );
}
