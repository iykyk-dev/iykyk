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

APK собирается автоматически в GitHub Actions при каждом изменении в `android/`:
вкладка **Actions** → последний запуск **Android** → раздел **Artifacts**.

Локально (нужны JDK 21 и Android SDK):

```sh
cd android
./gradlew :app:assembleDebug
```

## Статус

- [x] Шаг 1: скелет приложения, VPN-сервис с уведомлением, плитка в быстрых настройках, сборка в CI
- [ ] Шаг 2: Go-ядро (Xray + sing-box + Mieru)
- [ ] Шаг 3: настоящий туннель
- [ ] Шаг 4: подписка и кабинет
- [ ] Шаг 5: пинг, автовыбор, обходы
- [ ] Шаг 6: выпуск и автообновление

## Лицензия

GPL-3.0. Проект использует компоненты под GPL-3.0 (sing-box, Mieru), поэтому исходный код открыт.
