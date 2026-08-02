# Debug keystore (временная подпись для CI)

Файл `aoi-elks-debug.jks.b64` — base64 от JKS.

- **alias:** aoielks
- **storePassword / keyPassword:** aoielksdebug

Это **не** production-ключ. Нужен только чтобы все сборки с GitHub Actions
имели одну и ту же подпись и обновлялись поверх друг друга.

При первом переходе на эту подпись нужно **один раз удалить** старое
приложение с телефона (подписи разные).
