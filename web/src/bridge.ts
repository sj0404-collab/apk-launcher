import type { AppInfo, BridgeKind, LauncherBridge, ProcInfo } from "./types";

export interface BridgeState {
  kind: BridgeKind;
  ready: boolean;
}

export function detectBridge(): BridgeState {
  const b = (window as unknown as { ZenBridge?: LauncherBridge }).ZenBridge;
  if (!b || typeof b.listApps !== "function") {
    return { kind: "none", ready: false };
  }
  return { kind: "zenPanel", ready: true };
}

export function safeParse<T>(raw: string, fallback: T): T {
  try {
    return JSON.parse(raw) as T;
  } catch {
    return fallback;
  }
}

export function loadApps(): AppInfo[] {
  const b = (window as unknown as { ZenBridge?: LauncherBridge }).ZenBridge;
  if (!b) return [];
  const raw = b.listApps();
  const wrap = safeParse<{ apps: AppInfo[] }>(raw, { apps: [] });
  if (Array.isArray(wrap)) return wrap as unknown as AppInfo[];
  return Array.isArray(wrap.apps) ? wrap.apps : [];
}

export function launchApp(pkg: string): boolean {
  const b = (window as unknown as { ZenBridge?: LauncherBridge }).ZenBridge;
  if (!b) return false;
  return b.launchApp(pkg);
}

export function keepApp(pkg: string, keep: boolean): boolean {
  const b = (window as unknown as { ZenBridge?: LauncherBridge }).ZenBridge;
  if (!b) return false;
  return b.keepApp(pkg, keep);
}

export function loadKeeps(): Set<string> {
  const b = (window as unknown as { ZenBridge?: LauncherBridge }).ZenBridge;
  if (!b) return new Set();
  const raw = b.getKeeps();
  const wrap = safeParse<{ keeps: string[] }>(raw, { keeps: [] });
  const list = Array.isArray(wrap) ? (wrap as unknown as string[]) : wrap.keeps;
  return new Set(Array.isArray(list) ? list : []);
}

export function loadProcesses(): ProcInfo[] {
  const b = (window as unknown as { ZenBridge?: LauncherBridge }).ZenBridge;
  if (!b) return [];
  const raw = b.listProcesses();
  const wrap = safeParse<{ processes: ProcInfo[] }>(raw, { processes: [] });
  if (Array.isArray(wrap)) return wrap as unknown as ProcInfo[];
  return Array.isArray(wrap.processes) ? wrap.processes : [];
}