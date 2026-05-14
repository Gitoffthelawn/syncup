import React, { createContext, useCallback, useContext, useMemo, useState } from 'react';

interface AppReloadValue {
  reload: () => void;
  generation: number;
}

const Ctx = createContext<AppReloadValue | null>(null);

export function AppReloadProvider({ children }: { children: (generation: number) => React.ReactNode }) {
  const [generation, setGeneration] = useState(0);
  const reload = useCallback(() => setGeneration(g => g + 1), []);
  const value = useMemo<AppReloadValue>(() => ({ reload, generation }), [reload, generation]);
  return <Ctx.Provider value={value}>{children(generation)}</Ctx.Provider>;
}

export function useAppReload(): () => void {
  const v = useContext(Ctx);
  if (!v) {
    throw new Error('useAppReload must be used inside <AppReloadProvider>');
  }
  return v.reload;
}
