import type { AppInfo, ProcInfo } from "../types";
import { iconUrl } from "../types";

interface Props {
  processes: ProcInfo[];
  keeps: Set<string>;
  onLaunch: (pkg: string) => void;
  onToggleKeep: (pkg: string) => void;
}

export function ProcessPanel({ processes, keeps, onLaunch, onToggleKeep }: Props) {
  if (processes.length === 0 && keeps.size === 0) return null;
  const items = processes.map(p => ({
    proc: p,
    kept: [...keeps].some(k => p.packageName === k || p.packageName.startsWith(k)),
  }));
  return (
    <section className="process-panel">
      <h2>Процессы ({processes.length})</h2>
      <ul className="proc-list">
        {items.map(({ proc, kept }) => {
          const app: AppInfo = {
            packageName: proc.packageName,
            label: proc.packageName.split(".").pop() ?? proc.packageName,
            versionName: `pid ${proc.pid}`,
            launchable: kept,
          };
          return (
            <li key={proc.packageName + proc.pid} className="proc-row">
              <img
                className="proc-icon"
                src={iconUrl(proc.packageName)}
                alt=""
                onError={e => {
                  (e.currentTarget as HTMLImageElement).style.visibility = "hidden";
                }}
              />
              <span className="proc-name">{app.label}</span>
              <span className={`proc-state ${kept ? "on" : "off"}`}>
                {kept ? "keep" : proc.importance}
              </span>
              <button className="mini-btn" onClick={() => onLaunch(proc.packageName)}>
                запустить
              </button>
              <button
                className={`mini-btn ${kept ? "mini-active" : ""}`}
                onClick={() => onToggleKeep(proc.packageName)}
              >
                {kept ? "снять keep" : "keep"}
              </button>
            </li>
          );
        })}
      </ul>
    </section>
  );
}