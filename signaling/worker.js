// seaflow-signal — WebRTC signaling rendezvous on Cloudflare Workers
//
// Durable Object routes messages between peers that joined the same "room".
// A room has at most 2 WebSocket connections: the VPN server (persistent,
// joined first) and the current client (transient, joined when establishing
// a tunnel). Messages (SDP offer/answer, ICE candidates) are forwarded
// verbatim between the two.
//
// Deployment: paste this whole file into Cloudflare Workers as a single
// Worker using Durable Object binding named "ROOM".
//
// Protocol (client side):
//   ws wss://<worker>.workers.dev/ws?role=server&room=<ROOM_ID>
//   ws wss://<worker>.workers.dev/ws?role=client&room=<ROOM_ID>
//
//   Both endpoints send/receive JSON messages:
//     { "type": "offer",  "sdp":  "<sdp-text>" }
//     { "type": "answer", "sdp":  "<sdp-text>" }
//     { "type": "ice",    "candidate": {...} }
//     { "type": "bye" }
//
// Room ID is an opaque shared secret between the VPN server and its clients.
// Anyone who knows the ID can impersonate either side, but all actual VPN
// crypto happens inside the DataChannel, so Worker never sees plaintext.

export class RoomObject {
  constructor(state) {
    this.state = state
    this.server = null
    this.client = null
  }

  async fetch(request) {
    const url = new URL(request.url)
    const role = url.searchParams.get("role")

    if (role !== "server" && role !== "client") {
      return new Response("bad role", { status: 400 })
    }

    const upgradeHeader = request.headers.get("Upgrade")
    if (upgradeHeader !== "websocket") {
      return new Response("expected websocket", { status: 426 })
    }

    const pair = new WebSocketPair()
    const [inside, outside] = [pair[0], pair[1]]
    this.handleSession(inside, role)
    return new Response(null, { status: 101, webSocket: outside })
  }

  handleSession(ws, role) {
    ws.accept()

    // Evict previous session with the same role (reconnects).
    const old = role === "server" ? this.server : this.client
    if (old) {
      try { old.close(1000, "replaced") } catch (_) {}
    }
    if (role === "server") this.server = ws
    else this.client = ws

    ws.addEventListener("message", (event) => {
      const other = role === "server" ? this.client : this.server
      if (!other) return // peer not connected yet; client will retry
      try {
        other.send(event.data)
      } catch (_) {
        // other side gone; drop
      }
    })

    const drop = () => {
      if (role === "server" && this.server === ws) this.server = null
      if (role === "client" && this.client === ws) this.client = null
    }

    ws.addEventListener("close", drop)
    ws.addEventListener("error", drop)
  }
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url)

    if (url.pathname === "/") {
      return new Response("seaflow-signal ok\n", {
        headers: { "content-type": "text/plain; charset=utf-8" },
      })
    }

    if (url.pathname !== "/ws") {
      return new Response("not found", { status: 404 })
    }

    const room = url.searchParams.get("room")
    if (!room || room.length < 16 || room.length > 128) {
      return new Response("bad room id (16..128 chars)", { status: 400 })
    }

    const id = env.ROOM.idFromName(room)
    const stub = env.ROOM.get(id)
    return stub.fetch(request)
  },
}
