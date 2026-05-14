import AsyncStorage from '@react-native-async-storage/async-storage';

// @PhotoBackup/backedUp is the per-device upload tracker; restoring it onto
// a new device would make the app think those photos were already uploaded.
const EXCLUDED_KEYS = new Set<string>([
  '@PhotoBackup/backedUp',
]);

export async function exportAsyncStorage(): Promise<string> {
  const keys = await AsyncStorage.getAllKeys();
  const filtered = keys.filter(k => !EXCLUDED_KEYS.has(k));
  if (filtered.length === 0) return '{}';
  const pairs = await AsyncStorage.multiGet(filtered);
  const out: Record<string, string> = {};
  for (const [k, v] of pairs) {
    if (v !== null) out[k] = v;
  }
  return JSON.stringify(out);
}

export async function importAsyncStorage(json: string): Promise<number> {
  if (!json || json === '{}') return 0;
  let obj: Record<string, unknown>;
  try {
    obj = JSON.parse(json) as Record<string, unknown>;
  } catch {
    return 0;
  }
  const pairs: [string, string][] = [];
  for (const [key, value] of Object.entries(obj)) {
    if (typeof key !== 'string' || typeof value !== 'string') continue;
    if (EXCLUDED_KEYS.has(key)) continue;
    pairs.push([key, value]);
  }
  if (pairs.length === 0) return 0;
  await AsyncStorage.multiSet(pairs);
  return pairs.length;
}
