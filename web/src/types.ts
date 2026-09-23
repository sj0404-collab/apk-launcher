export interface AppInfo {
  packageName: string;
  label: string;
  versionName: string;
  launchable: boolean;
}

export interface ProcInfo {
  packageName: string;
  pid: number;
  importance: string;
}

export type BridgeKind = "zenPanel" | "none";

export const ICON_SCHEME = "icon";

export function iconUrl(pkg: string): string {
  return `${ICON_SCHEME}://${pkg}`;
}

export interface LauncherBridge {
  listApps(): string;
  launchApp(pkg: string): boolean;
  keepApp(pkg: string, keep: boolean): boolean;
  getKeeps(): string;
  listProcesses(): string;
  appIconAvailable(pkg: string): boolean;
}