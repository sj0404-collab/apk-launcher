interface Props {
  bridge: boolean;
  total: number;
  kept: number;
  alive: number;
  showKeptOnly: boolean;
  onToggleFilter: () => void;
  onRefresh: () => void;
}

export function KeepBar({
  bridge,
  total,
  kept,
  alive,
  showKeptOnly,
  onToggleFilter,
  onRefresh,
}: Props) {
  return (
    <div className="keepbar">
      <button className="stat stat-btn" onClick={onToggleFilter}>
        <span className="stat-num">{showKeptOnly ? kept : total}</span>
        <span className="stat-label">{showKeptOnly ? "на keep" : "приложений"}</span>
      </button>
      <div className="stat">
        <span className="stat-num off">{alive}</span>
        <span className="stat-label">живых процессов</span>
      </div>
      <button className="stat stat-btn" onClick={onRefresh}>
        <span className="stat-num">{bridge ? "⟳" : "×"}</span>
        <span className="stat-label">обновить</span>
      </button>
    </div>
  );
}