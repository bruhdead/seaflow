# seaflow-pc — Windows-клиент для AIVPN/seaflow

Лёгкий клиент-обёртка вокруг бинарника AIVPN для Windows. Автоматически:
- определяет ваш домашний шлюз
- читает connection-key из `connection.key`
- читает список bypass-IP/доменов из `bypass.txt`
- поднимает VPN с full-tunnel-маршрутизацией через Wintun
- очищает за собой Wintun-адаптер и маршруты при закрытии (Ctrl+C / закрытие окна / краш)

## Установка

1. **Распаковать архив** в любую папку, например `C:\seaflow\`. Папка должна быть постоянной (на Рабочем столе или в `Program Files` тоже работает).
2. **Открыть файл `connection.key`** любым текстовым редактором (Блокнот сойдёт).
3. **Заменить `aivpn://PASTE-YOUR-CONNECTION-KEY-HERE`** на ваш реальный connection-key, который вам выдали (одной строкой, начинается с `aivpn://`). Сохранить.
4. (Опционально) **Открыть `bypass.txt`** и добавить IP/домены, которые должны ходить мимо VPN — по одному на строку. Например, локальный банк, RustDesk, или сервисы с гео-фильтром.

## Запуск

Двойной клик по **`start-seaflow.bat`**.

При первом запуске Windows попросит подтверждения админских прав — соглашайтесь, без них не получится создать Wintun-адаптер и переписать таблицу маршрутов.

В консоли увидите:
```
=== seaflow-pc start ===
Loaded connection key (198 chars)
Auto-detected default gateway: 192.168.1.1
Launching C:\seaflow\seaflow-client.exe
Client started (PID 12345)
Wintun IF=42
Routing all traffic through wintun (IF=42)
External IP via VPN: 194.76.137.25
VPN is running. Press Ctrl+C to disconnect.
```

Если так — VPN поднят, весь интернет на ПК идёт через сервер. Можно проверить на [ifconfig.me](https://ifconfig.me) — должен показать IP сервера (`194.76.137.25`).

## Отключение

В окне консоли нажать **Ctrl+C** или **закрыть окно**. Скрипт сам:
- остановит клиент
- удалит свои маршруты `0.0.0.0/1` и `128.0.0.0/1`
- удалит bypass-маршруты
- удалит Wintun-адаптер из системы

## Логи

- `seaflow.log` — основной лог скрипта
- `client.log` — stdout клиента (handshake, ratchet, статистика)
- `client.log.err` — stderr клиента
- При следующем запуске старые логи переименовываются в `*.prev`

При проблемах — смотри в `client.log` после неудачного запуска. Признаки рабочего тоннеля:
```
Sent init handshake (NN bytes)
Connected to server at A.B.C.D:443
TUN device: wintun
Routing traffic through AIVPN tunnel...
PFS ratchet complete — forward secrecy established
```

## Файлы пакета

| Файл | Что делает | Когда менять |
|---|---|---|
| `seaflow-client.exe` | бинарь aivpn-client | не трогать |
| `wintun.dll` | TUN-драйвер от WireGuard | не трогать |
| `start-seaflow.bat` | лаунчер с авто-elevate | не трогать |
| `start-seaflow.ps1` | основной PowerShell-скрипт | можно править если знаете что делаете |
| `connection.key` | **ваш** ключ от сервера | **обязательно** заменить на свой |
| `bypass.txt` | IP/домены мимо VPN | по необходимости |

## Гарантии и каверзы

- **При зависшем выходе** (например, ноут заснул с поднятым VPN) Wintun-адаптер может остаться в системе. При следующем запуске скрипт сам его удалит — но если очень хочется вручную, в Диспетчере устройств → «Сетевые адаптеры» → удалить адаптер «WireGuard Tunnel #N».
- **DNS-leak**: full-tunnel прописывает DNS клиента (10.0.0.1 на сервере или 8.8.4.4/1.0.0.1 если сервер не делает DNS). Все запросы идут через VPN. Если в `bypass.txt` есть домены — их IP резолвятся в момент запуска и потом не обновляются (если IP домена сменится, пере-запусти).
- **Только IPv4**: клиент работает только с IPv4. IPv6 на интерфейсе не трогается. Если у вашего провайдера есть IPv6 и сайт умеет в обе версии — IPv6 пойдёт мимо VPN. Чтобы этого избежать, отключите IPv6 на сетевой карте до запуска.
- **`bypass.txt` для имён** делает один резолв при старте. Если нужно постоянное обновление — впишите статичные IP.

## Происхождение

`seaflow-client.exe` — это бинарь `aivpn-client.exe` из upstream-проекта AIVPN ([github.com/infosave2007/aivpn](https://github.com/infosave2007/aivpn)), переименованный для удобства брендинга. Никаких модификаций бинаря в этом пакете нет; обновления вытаскиваются из upstream-релизов.

`wintun.dll` — TUN-драйвер от [WireGuard](https://www.wintun.net/). Распространяется под BSD-стилевой лицензией.

PowerShell-обёртка (`start-seaflow.ps1`) — наша; она оборачивает запуск, делает auto-detect шлюза, читает `connection.key` и `bypass.txt`, чистит за собой Wintun-адаптер при выходе.

## Связанные проекты

- Android-клиент seaflow — `seaflow-vX.Y.Z.apk` в [Releases](https://github.com/bruhdead/seaflow/releases). Поддерживает обычный UDP и WebRTC-транспорт для обхода корпоративных DPI.
- Серверная часть AIVPN — оригинал на [github.com/infosave2007/aivpn](https://github.com/infosave2007/aivpn).
