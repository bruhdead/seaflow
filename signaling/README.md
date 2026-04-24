# seaflow signaling worker

Minimal WebSocket rendezvous for WebRTC signaling, deployed as a Cloudflare Worker with Durable Objects. Matches two WebSockets (server + client) in the same "room" and forwards messages between them.

## Deploy via dashboard (no CLI, no Node install)

1. Go to <https://dash.cloudflare.com> → **Workers & Pages** → **Create** → **Create Worker**.
2. Name: `seaflow-signal`. Click **Deploy** (creates a default worker).
3. Click **Edit code**. Delete everything, paste the contents of [`worker.js`](worker.js). Click **Save and deploy**.
4. Go to the worker's **Settings** → **Variables** → **Durable Object Bindings** → **Add binding**:
   - Variable name: `ROOM`
   - Durable Object class name: `RoomObject`
   - Click **Deploy**.
5. Copy the URL (e.g., `https://seaflow-signal.<your-handle>.workers.dev`). Test:
   ```
   curl https://seaflow-signal.<your-handle>.workers.dev/
   → seaflow-signal ok
   ```

That's it. No domain required.

## Deploy via CLI (if you prefer)

```
npm install -g wrangler
wrangler login
cd signaling
wrangler deploy
```

## Protocol

Two WebSocket clients connect to the same `room` ID:

```
wss://<worker>.workers.dev/ws?role=server&room=<ROOM_ID>
wss://<worker>.workers.dev/ws?role=client&room=<ROOM_ID>
```

Room ID: 16–128 characters, opaque shared secret between the VPN server and its
clients. Anyone with the ID can impersonate either side, but all real VPN
crypto (X25519 + ChaCha20-Poly1305 + PFS ratchet) happens inside the
DataChannel, so the Worker only sees opaque signaling exchange.

Messages are JSON, forwarded verbatim between the two peers:

- `{"type":"offer","sdp":"<SDP>"}`
- `{"type":"answer","sdp":"<SDP>"}`
- `{"type":"ice","candidate":{...}}`
- `{"type":"bye"}`

A room holds at most one server + one client at a time. If a new connection
joins with the same role, the previous one is evicted (supports reconnects).

## Free tier

Cloudflare Workers free tier at the time of writing: 100k requests/day, 1M
Durable Object requests/month. Each signaling exchange is 6–10 messages. Even
with aggressive reconnects this stays in single-digit thousands of requests
per day — comfortable.
