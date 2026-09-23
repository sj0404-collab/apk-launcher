# APK Launcher

HTML/TSX-лаунчер, живущий внутри APK-обёртки на WebView. Список установленных
приложений, запуск одной кнопкой и keep-alive: выбранные приложения держатся
живыми foreground-сервисом, который их перезапускает, если процесс умирает.

## Структура

- `web/` — React + TypeScript лаунчер (Vite). Собирается прямо в assets APK.
- `android/` — APK: WebView-обёртка, мост `ZenBridge`, сервис-держатель процессов.

## Веб-часть

```
cd web
npm install
npm run build      # кладёт index.html+js в ../android/app/src/main/assets/panel
npm run dev        # разработочный сервер (без моста: покажет заглушку)
```

Мост `ZenBridge` появляется только внутри WebView обёртки. В браузере лаунчер
понимает, что моста нет, и показывает подсказку.

## APK-обёртка

```
cd android
gradle :app:assembleDebug   # нужен SDK, адрес в local.properties
```

Что делает приложение:

- `MainActivity` — WebView, отдаёт страницу из assets, обслуживает `icon://<pkg>`
  через `IconServer` (иконки приложений без сети).
- `LauncherBridge` — JS-интерфейс: `listApps()`, `launchApp(pkg)`,
  `keepApp(pkg, bool)`, `getKeeps()`, `listProcesses()`.
- `AppKeeper` — хранит список «держимых» пакетов и перезапускает
  foreground-сервис.
- `KeepAliveService` — foreground-сервис с ваклоком и ватчдогом: каждые 2 с
  проверяет `runningAppProcesses` и поднимает умерший процесс заново.

Ограничения Android (workarounds): нужен `QUERY_ALL_PACKAGES` для списка
приложений; на Android 10+ фоновый запуск сервисами ограничен, поэтому
обёртка использует foreground-сервис типа `specialUse` с удержанием wake lock —
полноценная борьба с фоном всё равно возможна только при видимом уведомлении.

## Мост (сторона JS)

Типы и обёртки в `web/src/bridge.ts` и `web/src/types.ts`. Любой вызов
проверяет наличие `window.ZenBridge`, чтобы UI не падал вне обёртки.