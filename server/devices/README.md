# iykyk-devices

Маленький сервис рядом с панелью Remnawave. Показывает в приложении IYKYK,
сколько устройств подключено к подписке («2 из 3»), позволяет клиенту
удалить старое устройство, чтобы освободить место, и дать устройствам названия
(«Мамин телефон»). Удалять и называть устройства можно только с устройства,
которое уже есть в этой подписке.

Названия хранятся в `/data/names.json` внутри контейнера — подключите к `/data`
том, чтобы они не пропадали при обновлении сервиса. В файле только хэши и
названия: ни HWID, ни ID пользователей там нет.

## Обновление

```
docker pull ghcr.io/iykyk-dev/iykyk-devices:latest
docker rm -f iykyk-devices
docker run -d --name iykyk-devices --restart always \
  --env-file /opt/iykyk-devices/.env \
  -e REMNAWAVE_URL=https://vpn.example.com \
  -v iykyk-devices-data:/data \
  --network <сеть Caddy> \
  ghcr.io/iykyk-dev/iykyk-devices:latest
```

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
    volumes:
      - iykyk-devices-data:/data
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
