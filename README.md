# IYKYK

Клиент защищённого подключения «Для своих» для Android и Windows. Версия для iPhone и Mac позже.

Сайт: https://app.dlyasvoih.xyz

## Устройство

| Часть | Что делает |
|---|---|
| Ядро Xray (XTLS/Xray-core) | VLESS (REALITY, XTLS-Vision, XHTTP), VMess, Trojan, Shadowsocks |
| sing-box, Mieru, AmneziaWG | TUIC, Hysteria2, NaiveProxy, ShadowTLS, AnyTLS, WireGuard, AmneziaWG, Mieru |
| Приложение | Интерфейс, подписка, устройства, пинг, маршрутизация, уведомления, обновления |

## Структура репозитория

```
android/   приложения: app (Android), desktop (Windows), shared (общий интерфейс)
core/      общее Go-ядро и служба для Windows
server/    сервис устройств рядом с панелью
site/      страница проекта (GitHub Pages)
```

## Выпуск версии

Поменять номер в `VERSION` и добавить раздел в `RELEASE_NOTES.md`. Сборка подпишет
приложения, создаст тег и опубликует релиз с `version.json` для автообновления.
Версии с `-beta` выходят как пред-релиз.

## Сборка

APK собирается автоматически в GitHub Actions при каждом изменении в `android/` или `core/`:
вкладка **Actions** → последний запуск **Build** → раздел **Artifacts**.

Локально (нужны Go 1.27, JDK 21, Android SDK и NDK):

```sh
cd core && ./build.sh android     # ядро → android/app/libs/iykyk-core.aar
cd ../android && ./gradlew :app:assembleDebug
```

## Статус

- [x] Шаг 1: скелет приложения, VPN-сервис с уведомлением, плитка в быстрых настройках, сборка в CI
- [x] Шаг 2: Go-ядро (sing-box 1.14.4 + Xray 26.10.31), разбор ссылок, проверка конфигов движками в CI
- [x] Шаг 3: настоящий туннель (VLESS, Trojan, Shadowsocks через Xray; Hysteria2 через sing-box)
- [x] Шаг 3б: VMess, TUIC, AnyTLS, ShadowTLS, NaiveProxy, WireGuard, SOCKS5, HTTP; Mieru и AmneziaWG (TCP)
- [x] Шаг 4 (основа): подписка Remnawave, список серверов, трафик и срок
- [ ] Шаг 4: подписка и кабинет
- [ ] Шаг 5: пинг, автовыбор, обходы
- [ ] Шаг 6: выпуск и автообновление

## Лицензия

GPL-3.0. Проект использует компоненты под GPL-3.0 (sing-box, Mieru), поэтому исходный код открыт.
