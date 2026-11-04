# iykyk-devices

Маленький сервис рядом с панелью Remnawave. Показывает в приложении IYKYK,
сколько устройств подключено к подписке («2 из 3»), и позволяет клиенту
удалить старое устройство, чтобы освободить место.

Ключ API Remnawave хранится только в этом сервисе на вашем сервере.
Приложение передаёт лишь short UUID своей подписки — тот же секрет, что уже
даёт доступ к самой подписке. HWID чужих устройств наружу не отдаются,
вместо них хэши.

## Установка

1. В панели Remnawave создайте API-токен (Настройки → API Tokens).
2. Добавьте сервис в `docker-compose.yml` панели (в ту же сеть, что и `remnawave`):

```yaml
  iykyk-devices:
    image: ghcr.io/iykyk-dev/iykyk-devices:latest
    container_name: iykyk-devices
    restart: always
    environment:
      REMNAWAVE_URL: http://remnawave:3000
      REMNAWAVE_TOKEN: "вставьте-токен"
      # Лимит устройств по умолчанию (HWID_FALLBACK_DEVICE_LIMIT панели),
      # для пользователей без собственного лимита. 0 — без лимита.
      FALLBACK_DEVICE_LIMIT: "3"
    networks:
      - remnawave-network
```

3. Направьте `https://sub.dlyasvoih.xyz/iykyk/api/` на сервис.

Caddy (в блоке `sub.dlyasvoih.xyz`):

```
handle /iykyk/api/* {
    reverse_proxy iykyk-devices:8080
}
```

nginx (в `server` для `sub.dlyasvoih.xyz`):

```
location /iykyk/api/ {
    proxy_pass http://iykyk-devices:8080;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
}
```

4. Проверьте: `https://sub.dlyasvoih.xyz/iykyk/api/health` отвечает `ok`.

Приложение само обращается к `/iykyk/api` на домене подписки. Если сервис
на другом адресе, укажите его в заголовке ответа подписки
`Iykyk-Devices-Api` (Remnawave → Подписка → Заголовки ответа).
