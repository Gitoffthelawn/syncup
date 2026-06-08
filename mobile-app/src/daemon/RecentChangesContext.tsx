import React, { createContext, useContext, useEffect, useState } from 'react';
import { useEvents } from './EventsContext';

// ring buffer of recent file changes; subscribed at app root so we don't miss
// changes that happen while another screen is mounted. cap at 100.

export interface RecentChange {
  id: number;
  time: string;
  folder: string;
  item: string;
  type: string; // file | dir | symlink
  action: string; // update | delete | metadata
  error: string | null;
}

interface DiskChangeData {
  folder?: string;
  path?: string;
  type?: string;
  action?: string; // modified | deleted
}

interface ItemFinishedData {
  folder?: string;
  item?: string;
  type?: string;
  action?: string;
  error?: string | null;
}

interface RecentChangesContextValue {
  changes: RecentChange[];
  clear: () => void;
}

const Ctx = createContext<RecentChangesContextValue | null>(null);
const MAX_ENTRIES = 100;

// disk events report "modified"/"deleted"; normalize to the update/delete
// vocabulary the rest of the UI (icons, labels) already speaks.
function normalizeAction(action: string | undefined): string {
  if (action === 'deleted' || action === 'delete') return 'delete';
  if (action === 'metadata') return 'metadata';
  return 'update';
}

export function RecentChangesProvider({ children }: { children: React.ReactNode }) {
  const { subscribe } = useEvents();
  const [changes, setChanges] = useState<RecentChange[]>([]);

  const push = (next: RecentChange) =>
    setChanges(prev => {
      const out = [next, ...prev];
      if (out.length > MAX_ENTRIES) out.length = MAX_ENTRIES;
      return out;
    });

  useEffect(() => {
    const unsubscribe = subscribe(
      ['LocalChangeDetected', 'RemoteChangeDetected', 'ItemFinished'],
      evt => {
        if (evt.type === 'ItemFinished') {
          const d = (evt.data ?? {}) as ItemFinishedData;
          if (!d.folder || !d.item || !d.error) return;
          push({
            id: evt.id,
            time: evt.time,
            folder: d.folder,
            item: d.item,
            type: d.type ?? 'file',
            action: normalizeAction(d.action),
            error: String(d.error),
          });
          return;
        }

        const d = (evt.data ?? {}) as DiskChangeData;
        if (!d.folder || !d.path) return;
        push({
          id: evt.id,
          time: evt.time,
          folder: d.folder,
          item: d.path,
          type: d.type ?? 'file',
          action: normalizeAction(d.action),
          error: null,
        });
      },
    );
    return unsubscribe;
  }, [subscribe]);

  const clear = () => setChanges([]);

  return <Ctx.Provider value={{ changes, clear }}>{children}</Ctx.Provider>;
}

export function useRecentChanges(): RecentChangesContextValue {
  const v = useContext(Ctx);
  if (!v) throw new Error('useRecentChanges must be used inside <RecentChangesProvider>');
  return v;
}
