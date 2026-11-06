"use client";

import { Toast } from "@base-ui/react/toast";
import type { ReactNode } from "react";

import { Icon, type IconName } from "@/components/ds";

/**
 * Toasts.
 *
 * Base UI's headless toast gives the live region, the timeout, the swipe-to-dismiss and the
 * focus behaviour; every pixel of the look is ours. Bottom-right on desktop, full width at
 * the bottom on phones (ds.css).
 *
 * They close after 6 seconds unless they hold an action — an action a visitor never saw is
 * worse than no action.
 */

const TIMEOUT_MS = 6_000;

/** Extra fields we attach to a toast, beyond Base UI's title and description. */
interface ToastData {
  icon?: IconName;
  action?: { label: string; onClick: () => void };
}

export function ToastProvider({ children }: { children: ReactNode }) {
  return (
    <Toast.Provider timeout={TIMEOUT_MS}>
      {children}
      <Toaster />
    </Toast.Provider>
  );
}

function Toaster() {
  const { toasts } = Toast.useToastManager();

  return (
    <Toast.Portal>
      <Toast.Viewport className="ms-toasts">
        {toasts.map((toast) => {
          const data = (toast.data ?? {}) as ToastData;

          return (
            <Toast.Root key={toast.id} toast={toast} className="ms-toast">
              <Toast.Content className="contents">
                <span className="ms-toast__icon" aria-hidden="true">
                  <Icon name={data.icon ?? "clock"} size={16} />
                </span>

                <div className="ms-toast__body">
                  <Toast.Title className="ms-toast__title" />
                  <Toast.Description className="ms-toast__text" />

                  {data.action && (
                    <Toast.Action
                      className="ms-btn ms-btn--solid ms-btn--sm ms-toast__action"
                      onClick={data.action.onClick}
                    >
                      {data.action.label}
                    </Toast.Action>
                  )}
                </div>

                <Toast.Close
                  className="ms-iconbtn"
                  style={{ width: 28, height: 28 }}
                  aria-label="Dismiss"
                >
                  <Icon name="close" size={14} />
                </Toast.Close>
              </Toast.Content>
            </Toast.Root>
          );
        })}
      </Toast.Viewport>
    </Toast.Portal>
  );
}

export interface ShowToast {
  title: string;
  body?: string;
  icon?: IconName;
  action?: { label: string; onClick: () => void };
}

/**
 * `const toast = useToast(); toast({ title: "Copied" })`.
 *
 * A thin wrapper so callers never touch Base UI's shape directly — if the primitive is ever
 * swapped out, this is the only file that changes.
 */
export function useToast() {
  const manager = Toast.useToastManager();

  return ({ title, body, icon, action }: ShowToast) => {
    manager.add({
      title,
      description: body,
      // An actionable toast waits to be dealt with rather than vanishing.
      timeout: action ? 0 : TIMEOUT_MS,
      data: { icon, action } satisfies ToastData,
    });
  };
}
