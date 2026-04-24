# seaflow-peer

WebRTC bridge that terminates client DataChannels and pipes their payload into the local `aivpn-server` UDP listener. Connects to [`seaflow-signal`](../../signaling/) as `role=server` and waits for SDP offers.

## Build

```
cd server/peer
go build -o seaflow-peer
```

Requires Go 1.22+.

## Configure

Copy `config.example.json` to `config.json`, fill in:

- `signaling_url` — base URL of the signaling worker (e.g. `https://seaflow-signal.example.workers.dev`). `ws://`/`wss://` is derived automatically.
- `room_id` — opaque shared secret, 16–128 chars. Clients must know it to connect.
- `upstream_udp` — where the local `aivpn-server` is listening. Default `127.0.0.1:443`.
- `ice_servers` — STUN + TURN endpoints. Use Metered.ca's "ICE Servers Array" output verbatim.

`config.json` is gitignored.

## Run manually

```
./seaflow-peer -config config.json
```

## Run as systemd service

```
install -m 0755 seaflow-peer /opt/seaflow-peer/
install -m 0600 config.json  /opt/seaflow-peer/
install -m 0644 deploy/seaflow-peer.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now seaflow-peer
journalctl -u seaflow-peer -f
```

## Behaviour

- On startup, opens WebSocket to the signaling worker and waits. Reconnects
  with exponential backoff on failure (capped at 30s).
- Each incoming SDP offer spawns a new session: PeerConnection + DataChannel
  + freshly-dialed UDP socket to `upstream_udp`. Each session is independent;
  a new offer with the same room tears down the previous one.
- The existing `aivpn-server` container does not need any changes — it sees
  traffic on its UDP socket just like from a direct client, one source
  address per WebRTC session.
