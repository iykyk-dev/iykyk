# IYKYK

VPN-клиент «для своих» для Android и iOS.

## Устройство

| Часть | Что делает |
|---|---|
| Ядро Xray (XTLS/Xray-core) | VLESS (REALITY, XTLS-Vision, XHTTP), VMess, Trojan, Shadowsocks |
| sing-box + Mieru | TUIC, Hysteria2, NaiveProxy, ShadowTLS, AnyTLS, WireGuard / AmneziaWG, Mieru |
| Приложение | Интерфейс, подписка, кабинет, пинг, обходы, уведомления |

## Структура репозитория

```
android/   приложение для Android (Kotlin, Jetpack Compose)
core/      общее Go-ядро для Android и iOS (появится на шаге 2)
ios/       приложение для iOS (позже)
```

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
- [ ] Шаг 3б: остальные протоколы (VMess, TUIC, AnyTLS, ShadowTLS, Naive, WireGuard, AmneziaWG, Mieru, SOCKS5, HTTP)
- [ ] Шаг 4: подписка и кабинет
- [ ] Шаг 5: пинг, автовыбор, обходы
- [ ] Шаг 6: выпуск и автообновление

## Лицензия

GPL-3.0. Проект использует компоненты под GPL-3.0 (sing-box, Mieru), поэтому исходный код открыт.
