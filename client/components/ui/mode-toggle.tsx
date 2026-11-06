"use client";

import { Moon, Sun } from "lucide-react";
import { useTheme } from "next-themes";

import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";

const THEMES = [
  { value: "light", label: "Light" },
  { value: "dark", label: "Dark" },
  { value: "system", label: "System" },
] as const;

export function ModeToggle() {
  const { setTheme, theme } = useTheme();

  return (
    <DropdownMenu>
      {/*
        `render` rather than wrapping a <Button> in the trigger: the trigger renders its own
        button, so nesting one inside produced a button within a button — invalid HTML and a
        confusing stop for screen readers and keyboard users.
      */}
      <DropdownMenuTrigger
        render={
          // `relative` so the absolutely positioned Moon below anchors to this button. Without
          // it the icon anchored to the nearest positioned ancestor — the sticky header.
          <Button variant="outline" size="icon" aria-label="Change theme" className="relative" />
        }
      >
        {/*
          The icons cross-fade by rotating and scaling. Only transform is transitioned —
          `transition-all` would animate colour and layout properties too — and the whole
          thing is disabled under prefers-reduced-motion.
        */}
        <Sun
          aria-hidden="true"
          className="size-[1.2rem] rotate-0 scale-100 transition-transform duration-200 motion-reduce:transition-none dark:-rotate-90 dark:scale-0"
        />
        <Moon
          aria-hidden="true"
          className="absolute size-[1.2rem] rotate-90 scale-0 transition-transform duration-200 motion-reduce:transition-none dark:rotate-0 dark:scale-100"
        />
      </DropdownMenuTrigger>

      <DropdownMenuContent align="end">
        {THEMES.map((option) => (
          <DropdownMenuItem
            key={option.value}
            onClick={() => setTheme(option.value)}
            // Tells assistive tech which theme is active, rather than presenting three
            // identical-looking choices.
            aria-current={theme === option.value ? "true" : undefined}
          >
            {option.label}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
