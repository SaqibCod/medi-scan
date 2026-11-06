import type { ReactNode } from "react";

export interface ChatBubbleProps {
  role: "user" | "assistant";
  children: ReactNode;
}

/** One chat message. Wrap several in a `.ms-chat` column. */
export function ChatBubble({ role, children }: ChatBubbleProps) {
  return <div className={`ms-bubble ms-bubble--${role === "user" ? "user" : "assistant"}`}>{children}</div>;
}
