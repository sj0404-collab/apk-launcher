import { useCallback, useEffect, useMemo, useState } from "react";
import type { AppInfo, ProcInfo } from "./types";
import {
  BridgeState,
  detectBridge,
  keepApp,
  launchApp,
  loadApps,
  loadKeeps,
  loadProcesses,
} from "./bridge";
import { AppCard } from "./components/AppCard";
import { ProcessPanel } from "./components/ProcessPanel";
import { KeepBar } from "./components/KeepBar";

export default function App() {
  const [bridge, setBridge] = useState<BridgeState>({ kind: "none", ready: false });
  const [apps, setApps] = useState<AppInfo[]>([]);
  const [keeps, setKeeps] = useState<Set<string>>(new Set());
  const [processes, setProcesses] = useState<ProcInfo[]>([]);
  const [query, setQuery] = useState("");
  const [showKeptOnly, setShowKeptOnly] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(() => {
    setApps(loadApps());
    setKeeps(loadKeeps());
    setProcesses(loadProcesses());
  }, []);

  useEffect(() => {
    const state = detectBridge();
    setBridge(state);
    if (!state.ready) {
      setError("Мост ZenBridge не найден. Откройте лаунчер внутри APK-обёртки, а не в браузере.");
      return;
    }
    refresh();
    const timer = window.setInterval(refresh, 2500);
    return () => window.clearInterval(timer);
  }, [refresh]);

  const toggleKeep = useCallback(
    (pkg: string) => {
      const next = !keeps.has(pkg);
      const ok = keepApp(pkg, next);
      if (!ok) {
        setError("Не удалось изменить keep-alive. Проверьте разрешение на фоновую работу.");
        return;
      }
      setKeeps(prev => {
        const copy = new Set(prev);
        if (next) copy.add(pkg);
        else copy.delete(pkg);
        return copy;
      });
    },
    [keeps],
  );

  const doLaunch = useCallback((pkg: string) => {
    if (!launchApp(pkg)) setError("Не удалось запустить приложение.");
  }, []);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return apps.filter(a => {
      if (showKeptOnly && !keeps.has(a.packageName)) return false;
      if (!q) return true;
      return (
        a.label.toLowerCase().includes(q) || a.packageName.toLowerCase().includes(q)
      );
    });
  }, [apps, query, keeps, showKeptOnly]);

  const aliveCount = useMemo(
    () =>
      processes.filter(p =>
        [...keeps].some(k => p.packageName === k || p.packageName.startsWith(k)),
      ).length,
    [processes, keeps],
  );

  return (
    <div className="shell">
      <header className="topbar">
        <div className="brand">
          <span className="brand-dot" />
          <h1>APK Launcher</h1>
          <span className={`chip ${bridge.ready ? "chip-ok" : "chip-no"}`}>
            {bridge.ready ? "обёртка активна" : "без моста"}
          </span>
        </div>
        <input
          className="search"
          placeholder="Поиск приложений…"
          value={query}
          onChange={e => setQuery(e.target.value)}
        />
      </header>

      <KeepBar
        bridge={bridge.ready}
        total={apps.length}
        kept={keeps.size}
        alive={aliveCount}
        showKeptOnly={showKeptOnly}
        onToggleFilter={() => setShowKeptOnly(v => !v)}
        onRefresh={refresh}
      />

      {error && (
        <div className="error" onClick={() => setError(null)}>
          {error}
        </div>
      )}

      <main className="grid">
        {filtered.map(app => (
          <AppCard
            key={app.packageName}
            app={app}
            kept={keeps.has(app.packageName)}
            running={processes.some(
              p => p.packageName === app.packageName || p.packageName.startsWith(app.packageName),
            )}
            onLaunch={() => doLaunch(app.packageName)}
            onToggleKeep={() => toggleKeep(app.packageName)}
          />
        ))}
        {filtered.length === 0 && (
          <div className="empty">Ничего не найдено</div>
        )}
      </main>

      <ProcessPanel
        processes={processes}
        keeps={keeps}
        onLaunch={doLaunch}
        onToggleKeep={toggleKeep}
      />
    </div>
  );
}