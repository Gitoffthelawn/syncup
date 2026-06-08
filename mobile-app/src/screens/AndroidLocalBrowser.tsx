import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  FlatList,
  Modal,
  Pressable,
  StatusBar,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import GoBridge from '../GoServerBridgeJSI';
import { colors } from '../components/ui';
import { Icon } from '../components/Icon';
import type { FsEntry } from '../fs/bridgeFs';

interface RawListing {
  path?: string;
  entries?: FsEntry[];
  error?: string;
}

function listLocal(path: string): { path: string; entries: FsEntry[] } {
  const raw = GoBridge.listLocalSubdirs(path);
  let parsed: RawListing;
  try {
    parsed = JSON.parse(raw) as RawListing;
  } catch (e) {
    throw new Error(`bad JSON from native bridge: ${String(e)}`);
  }
  if (parsed.error) throw new Error(parsed.error);
  return { path: parsed.path ?? path, entries: parsed.entries ?? [] };
}

function mkdirLocal(parent: string, name: string): string {
  const raw = GoBridge.mkdirLocalSubdir(parent, name);
  let parsed: RawListing;
  try {
    parsed = JSON.parse(raw) as RawListing;
  } catch (e) {
    throw new Error(`bad JSON from native bridge: ${String(e)}`);
  }
  if (parsed.error) throw new Error(parsed.error);
  if (!parsed.path) throw new Error('mkdirLocalSubdir returned no path');
  return parsed.path;
}

const FALLBACK_ROOT = '/storage/emulated/0';
let cachedExternalRoot: string | null = null;
function externalRoot(): string {
  if (cachedExternalRoot) return cachedExternalRoot;
  let root = FALLBACK_ROOT;
  try {
    const resolved = GoBridge.getExternalStorageRoot();
    if (resolved) root = resolved;
  } catch {
  }
  cachedExternalRoot = root;
  return root;
}
const ROOT_LABEL = 'Internal storage';

interface Props {
  visible: boolean;
  onCancel: () => void;
  onPick: (absolutePath: string) => void;
}

interface Crumb {
  label: string;
  path: string;
}

function buildCrumbs(path: string): Crumb[] {
  const root = externalRoot();
  const crumbs: Crumb[] = [{ label: ROOT_LABEL, path: root }];
  if (path === root) return crumbs;
  const rel = path.startsWith(root)
    ? path.slice(root.length).replace(/^\/+/, '')
    : '';
  if (!rel) return crumbs;
  const parts = rel.split('/').filter(Boolean);
  let acc = root;
  for (const part of parts) {
    acc = `${acc}/${part}`;
    crumbs.push({ label: part, path: acc });
  }
  return crumbs;
}

export function AndroidLocalBrowser({ visible, onCancel, onPick }: Props) {
  const [path, setPath] = useState<string>(externalRoot);
  const [entries, setEntries] = useState<FsEntry[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [creating, setCreating] = useState(false);
  const [newName, setNewName] = useState('');

  const crumbs = useMemo(() => buildCrumbs(path), [path]);
  const currentLabel = useMemo(() => {
    if (path === externalRoot()) return ROOT_LABEL;
    return path.split('/').filter(Boolean).pop() ?? ROOT_LABEL;
  }, [path]);

  const load = useCallback((p: string) => {
    setLoading(true);
    setError(null);
    try {
      const { entries: e, path: resolvedPath } = listLocal(p);
      const dirs = e
        .filter(x => x.isDir)
        .sort((a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: 'base' }));
      setEntries(dirs);
      setPath(resolvedPath);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (visible) {
      setPath(externalRoot());
      setCreating(false);
      setNewName('');
    }
  }, [visible]);

  useEffect(() => {
    if (visible && path) load(path);
  }, [visible, path, load]);

  const openSubdir = (name: string) => {
    setPath(`${path}/${name}`);
  };

  const goTo = (target: string) => {
    if (target !== path) setPath(target);
  };

  const createFolder = () => {
    const n = newName.trim();
    if (!n) return;
    try {
      const created = mkdirLocal(path, n);
      setCreating(false);
      setNewName('');
      setPath(created);
    } catch (e) {
      Alert.alert('Could not create folder', e instanceof Error ? e.message : String(e));
    }
  };

  return (
    <Modal visible={visible} animationType="slide" onRequestClose={onCancel}>
      <SafeAreaView style={styles.root} edges={['top', 'bottom']}>
        <StatusBar barStyle="light-content" backgroundColor={colors.bg} />

        <View style={styles.appBar}>
          <TouchableOpacity onPress={onCancel} style={styles.appBarBtn} hitSlop={10}>
            <Text style={styles.appBarBtnText}>✕</Text>
          </TouchableOpacity>
          <Text style={styles.appBarTitle} numberOfLines={1}>
            {currentLabel}
          </Text>
          <TouchableOpacity
            onPress={() => setCreating(true)}
            style={styles.appBarBtn}
            hitSlop={10}
            disabled={creating}
          >
            <Icon name="add" size={22} color={creating ? colors.textDim : colors.text} />
          </TouchableOpacity>
        </View>

        {crumbs.length > 1 && (
          <View style={styles.crumbBar}>
            <FlatList
              horizontal
              data={crumbs}
              keyExtractor={c => c.path}
              showsHorizontalScrollIndicator={false}
              contentContainerStyle={styles.crumbContent}
              renderItem={({ item, index }) => {
                const isLast = index === crumbs.length - 1;
                return (
                  <View style={styles.crumbRow}>
                    <Pressable onPress={() => goTo(item.path)} hitSlop={4}>
                      <Text
                        style={[styles.crumb, isLast && styles.crumbActive]}
                        numberOfLines={1}
                      >
                        {item.label}
                      </Text>
                    </Pressable>
                    {!isLast && <Text style={styles.crumbSep}>›</Text>}
                  </View>
                );
              }}
            />
          </View>
        )}

        {error && <Text style={styles.error}>{error}</Text>}

        {creating && (
          <View style={styles.createRow}>
            <TextInput
              autoFocus
              value={newName}
              onChangeText={setNewName}
              placeholder="New folder name"
              placeholderTextColor={colors.textDim}
              style={styles.createInput}
              onSubmitEditing={createFolder}
              autoCorrect={false}
              autoCapitalize="none"
            />
            <TouchableOpacity onPress={createFolder} style={styles.createBtn}>
              <Text style={styles.createBtnText}>Create</Text>
            </TouchableOpacity>
            <TouchableOpacity
              onPress={() => {
                setCreating(false);
                setNewName('');
              }}
              style={styles.createCancel}
              hitSlop={6}
            >
              <Text style={styles.createCancelText}>✕</Text>
            </TouchableOpacity>
          </View>
        )}

        {loading ? (
          <View style={styles.loading}>
            <ActivityIndicator color={colors.textDim} />
          </View>
        ) : (
          <FlatList
            data={entries}
            keyExtractor={e => e.name}
            contentContainerStyle={styles.list}
            ListEmptyComponent={
              <Text style={styles.empty}>This folder has no subfolders.</Text>
            }
            renderItem={({ item }) => (
              <TouchableOpacity style={styles.row} onPress={() => openSubdir(item.name)}>
                <View style={styles.rowIcon}>
                  <Icon name="folder" size={22} color={colors.accent} />
                </View>
                <Text style={styles.rowName} numberOfLines={1}>{item.name}</Text>
                <Text style={styles.rowArrow}>›</Text>
              </TouchableOpacity>
            )}
          />
        )}

        <View style={styles.footer}>
          <TouchableOpacity style={styles.useBtn} onPress={() => onPick(path)}>
            <Text style={styles.useBtnText}>USE THIS FOLDER</Text>
          </TouchableOpacity>
        </View>
      </SafeAreaView>
    </Modal>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: colors.bg,
  },
  appBar: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 8,
    paddingVertical: 8,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: colors.border,
    gap: 4,
  },
  appBarBtn: {
    width: 44,
    height: 44,
    alignItems: 'center',
    justifyContent: 'center',
  },
  appBarBtnText: {
    color: colors.text,
    fontSize: 22,
    fontWeight: '500',
  },
  appBarTitle: {
    flex: 1,
    color: colors.text,
    fontSize: 17,
    fontWeight: '600',
    marginHorizontal: 4,
  },
  crumbBar: {
    backgroundColor: colors.card,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: colors.border,
  },
  crumbContent: {
    paddingHorizontal: 16,
    paddingVertical: 12,
    alignItems: 'center',
  },
  crumbRow: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  crumb: {
    color: colors.textDim,
    fontSize: 14,
    fontWeight: '500',
    paddingHorizontal: 4,
  },
  crumbActive: {
    color: colors.text,
    fontWeight: '600',
  },
  crumbSep: {
    color: colors.textDim,
    fontSize: 16,
    paddingHorizontal: 2,
  },
  error: {
    color: colors.error,
    fontSize: 13,
    padding: 16,
  },
  loading: {
    flex: 1,
    paddingTop: 60,
    alignItems: 'center',
  },
  list: {
    flexGrow: 1,
    paddingVertical: 4,
  },
  empty: {
    color: colors.textDim,
    fontSize: 14,
    textAlign: 'center',
    padding: 40,
    fontStyle: 'italic',
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingVertical: 16,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: colors.border,
    gap: 16,
  },
  rowIcon: {
    width: 40,
    height: 40,
    borderRadius: 8,
    backgroundColor: colors.card,
    alignItems: 'center',
    justifyContent: 'center',
  },
  rowName: {
    color: colors.text,
    fontSize: 16,
    flex: 1,
  },
  rowArrow: {
    color: colors.textDim,
    fontSize: 22,
  },
  footer: {
    paddingHorizontal: 16,
    paddingTop: 12,
    paddingBottom: 12,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: colors.border,
    backgroundColor: colors.card,
  },
  useBtn: {
    backgroundColor: colors.accent,
    paddingVertical: 14,
    borderRadius: 10,
    alignItems: 'center',
  },
  useBtnText: {
    color: '#fff',
    fontSize: 14,
    fontWeight: '700',
    letterSpacing: 1,
  },
  createRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    paddingHorizontal: 16,
    paddingVertical: 12,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: colors.border,
    backgroundColor: colors.card,
  },
  createInput: {
    flex: 1,
    color: colors.text,
    backgroundColor: colors.bg,
    paddingHorizontal: 12,
    paddingVertical: 10,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: colors.border,
    fontSize: 14,
  },
  createBtn: {
    backgroundColor: colors.accent,
    paddingVertical: 10,
    paddingHorizontal: 14,
    borderRadius: 8,
  },
  createBtnText: {
    color: '#fff',
    fontWeight: '600',
    fontSize: 14,
  },
  createCancel: {
    width: 36,
    height: 36,
    alignItems: 'center',
    justifyContent: 'center',
  },
  createCancelText: {
    color: colors.textDim,
    fontSize: 18,
  },
});
