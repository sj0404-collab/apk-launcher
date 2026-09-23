import type { AppInfo } from "../types";
import { iconUrl } from "../types";

interface Props {
  app: AppInfo;
  kept: boolean;
  running: boolean;
  onLaunch: () => void;
  onToggleKeep: () => void;
}

export function AppCard({ app, kept, running, onLaunch, onToggleKeep }: Props) {
  return (
    <div className={`card ${running ? "card-running" : ""}`}>
      <button className="card-main" onClick={onLaunch} title={app.packageName}>
        <img
          className="card-icon"
          src={iconUrl(app.packageName)}
          alt=""
          onError={e => {
            (e.currentTarget as HTMLImageElement).style.visibility = "hidden";
          }}
        />
        <span className="card-label">{app.label}</span>
        <span className="card-ver">{app.versionName}</span>
      </button>
      <div className="card-row">
        <span className={`card-state ${running ? "on" : "off"}`}>
          {running ? "работает" : "спит"}
        </span>
        <button
          className={`keep-btn ${kept ? "active" : ""}`}
          onClick={onToggleKeep}
        >
          {kept ? "держать" : "не держать"}
        </button>
      </div>
    </div>
  );
}