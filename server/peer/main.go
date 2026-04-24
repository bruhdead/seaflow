// seaflow-peer — WebRTC bridge between a Cloudflare-signaled DataChannel
// and a local UDP-based aivpn-server.
//
// For every incoming client we:
//   1. Open a WebSocket to the signaling worker as role=server,room=<ID>.
//   2. Wait for SDP offer, answer it, exchange ICE candidates.
//   3. When the DataChannel opens, pipe binary frames to/from a fresh UDP
//      socket connected to the local aivpn-server (default 127.0.0.1:443).
//      Each client gets its own UDP source port so aivpn-server treats
//      them as distinct sessions — no changes needed upstream.
//
// The UDP upstream is the unmodified existing aivpn-server listening on
// 127.0.0.1:443. Our docker-compose'd container already binds to 0.0.0.0:443,
// so 127.0.0.1:443 is reachable from the host namespace.

package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net"
	"net/url"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"

	"github.com/gorilla/websocket"
	"github.com/pion/webrtc/v4"
)

type ICEServer struct {
	URLs       []string `json:"urls"`
	Username   string   `json:"username,omitempty"`
	Credential string   `json:"credential,omitempty"`
}

type Config struct {
	SignalingURL string      `json:"signaling_url"`
	RoomID       string      `json:"room_id"`
	UpstreamUDP  string      `json:"upstream_udp"`
	ICEServers   []ICEServer `json:"ice_servers"`
}

type sigMessage struct {
	Type      string          `json:"type"`
	SDP       string          `json:"sdp,omitempty"`
	Candidate json.RawMessage `json:"candidate,omitempty"`
}

type session struct {
	pc       *webrtc.PeerConnection
	wsWrite  chan<- []byte
	upstream *net.UDPConn
	done     chan struct{}
	once     sync.Once
}

func (s *session) close() {
	s.once.Do(func() {
		close(s.done)
		if s.upstream != nil {
			s.upstream.Close()
		}
		if s.pc != nil {
			_ = s.pc.Close()
		}
	})
}

func loadConfig(path string) (*Config, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var cfg Config
	if err := json.Unmarshal(data, &cfg); err != nil {
		return nil, err
	}
	if cfg.SignalingURL == "" {
		return nil, fmt.Errorf("signaling_url is required")
	}
	if cfg.RoomID == "" {
		return nil, fmt.Errorf("room_id is required")
	}
	if cfg.UpstreamUDP == "" {
		cfg.UpstreamUDP = "127.0.0.1:443"
	}
	return &cfg, nil
}

func toPionICEServers(in []ICEServer) []webrtc.ICEServer {
	out := make([]webrtc.ICEServer, 0, len(in))
	for _, s := range in {
		out = append(out, webrtc.ICEServer{
			URLs:       s.URLs,
			Username:   s.Username,
			Credential: s.Credential,
		})
	}
	return out
}

func signalingWSURL(base, room string) (string, error) {
	u, err := url.Parse(base)
	if err != nil {
		return "", err
	}
	switch u.Scheme {
	case "http":
		u.Scheme = "ws"
	case "https":
		u.Scheme = "wss"
	}
	u.Path = "/ws"
	q := u.Query()
	q.Set("role", "server")
	q.Set("room", room)
	u.RawQuery = q.Encode()
	return u.String(), nil
}

func runOnce(ctx context.Context, cfg *Config) error {
	wsURL, err := signalingWSURL(cfg.SignalingURL, cfg.RoomID)
	if err != nil {
		return fmt.Errorf("build ws url: %w", err)
	}

	log.Printf("signaling: dialing %s", wsURL)
	conn, _, err := websocket.DefaultDialer.DialContext(ctx, wsURL, nil)
	if err != nil {
		return fmt.Errorf("ws dial: %w", err)
	}
	defer conn.Close()
	log.Printf("signaling: connected")

	writeCh := make(chan []byte, 64)
	writeDone := make(chan struct{})
	go func() {
		defer close(writeDone)
		for msg := range writeCh {
			conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
			if err := conn.WriteMessage(websocket.TextMessage, msg); err != nil {
				log.Printf("signaling write: %v", err)
				return
			}
		}
	}()
	defer close(writeCh)

	// Ping to keep WS alive through any intermediary NATs.
	pingTick := time.NewTicker(30 * time.Second)
	defer pingTick.Stop()
	go func() {
		for {
			select {
			case <-pingTick.C:
				conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
				if err := conn.WriteMessage(websocket.PingMessage, nil); err != nil {
					return
				}
			case <-ctx.Done():
				return
			}
		}
	}()

	iceServers := toPionICEServers(cfg.ICEServers)
	var curSession *session
	var sessMu sync.Mutex

	closeCur := func(reason string) {
		sessMu.Lock()
		if curSession != nil {
			log.Printf("session: closing (%s)", reason)
			curSession.close()
			curSession = nil
		}
		sessMu.Unlock()
	}

	for {
		_, raw, err := conn.ReadMessage()
		if err != nil {
			closeCur("ws read error")
			return fmt.Errorf("ws read: %w", err)
		}
		var msg sigMessage
		if err := json.Unmarshal(raw, &msg); err != nil {
			log.Printf("signaling: bad json: %v", err)
			continue
		}

		switch msg.Type {
		case "offer":
			closeCur("new offer")
			log.Printf("signaling: offer received (%d bytes SDP)", len(msg.SDP))
			s, err := newSession(cfg, iceServers, writeCh, msg.SDP)
			if err != nil {
				log.Printf("session create: %v", err)
				continue
			}
			sessMu.Lock()
			curSession = s
			sessMu.Unlock()

		case "ice":
			sessMu.Lock()
			s := curSession
			sessMu.Unlock()
			if s == nil {
				continue
			}
			var cand webrtc.ICECandidateInit
			if err := json.Unmarshal(msg.Candidate, &cand); err != nil {
				log.Printf("signaling: bad ice candidate: %v", err)
				continue
			}
			if err := s.pc.AddICECandidate(cand); err != nil {
				log.Printf("pc: add ice candidate: %v", err)
			}

		case "bye":
			closeCur("bye")

		default:
			log.Printf("signaling: ignoring type=%s", msg.Type)
		}
	}
}

func newSession(cfg *Config, iceServers []webrtc.ICEServer, wsWrite chan<- []byte, offerSDP string) (*session, error) {
	pcConfig := webrtc.Configuration{ICEServers: iceServers}
	pc, err := webrtc.NewPeerConnection(pcConfig)
	if err != nil {
		return nil, fmt.Errorf("new peer connection: %w", err)
	}

	sess := &session{
		pc:      pc,
		wsWrite: wsWrite,
		done:    make(chan struct{}),
	}

	pc.OnICECandidate(func(c *webrtc.ICECandidate) {
		if c == nil {
			return
		}
		b, _ := json.Marshal(sigMessage{
			Type:      "ice",
			Candidate: mustJSON(c.ToJSON()),
		})
		select {
		case wsWrite <- b:
		case <-sess.done:
		}
	})

	pc.OnConnectionStateChange(func(state webrtc.PeerConnectionState) {
		log.Printf("pc state: %s", state)
		if state == webrtc.PeerConnectionStateFailed ||
			state == webrtc.PeerConnectionStateClosed ||
			state == webrtc.PeerConnectionStateDisconnected {
			sess.close()
		}
	})

	pc.OnDataChannel(func(dc *webrtc.DataChannel) {
		log.Printf("datachannel: opened label=%s id=%d", dc.Label(), safeID(dc.ID()))
		sess.bridge(dc, cfg.UpstreamUDP)
	})

	offer := webrtc.SessionDescription{Type: webrtc.SDPTypeOffer, SDP: offerSDP}
	if err := pc.SetRemoteDescription(offer); err != nil {
		sess.close()
		return nil, fmt.Errorf("set remote: %w", err)
	}
	answer, err := pc.CreateAnswer(nil)
	if err != nil {
		sess.close()
		return nil, fmt.Errorf("create answer: %w", err)
	}
	if err := pc.SetLocalDescription(answer); err != nil {
		sess.close()
		return nil, fmt.Errorf("set local: %w", err)
	}

	b, _ := json.Marshal(sigMessage{Type: "answer", SDP: answer.SDP})
	select {
	case wsWrite <- b:
	case <-sess.done:
		return nil, fmt.Errorf("session closed before answer sent")
	}
	log.Printf("pc: answer sent")
	return sess, nil
}

func (s *session) bridge(dc *webrtc.DataChannel, upstreamAddr string) {
	addr, err := net.ResolveUDPAddr("udp", upstreamAddr)
	if err != nil {
		log.Printf("bridge: resolve %s: %v", upstreamAddr, err)
		s.close()
		return
	}
	upstream, err := net.DialUDP("udp", nil, addr)
	if err != nil {
		log.Printf("bridge: dial %s: %v", upstreamAddr, err)
		s.close()
		return
	}
	s.upstream = upstream

	// DC → UDP
	dc.OnMessage(func(msg webrtc.DataChannelMessage) {
		if _, err := upstream.Write(msg.Data); err != nil {
			log.Printf("bridge: upstream write: %v", err)
			s.close()
		}
	})
	dc.OnClose(func() { s.close() })
	dc.OnError(func(err error) {
		log.Printf("bridge: dc error: %v", err)
		s.close()
	})

	// UDP → DC
	go func() {
		buf := make([]byte, 65536)
		for {
			select {
			case <-s.done:
				return
			default:
			}
			upstream.SetReadDeadline(time.Now().Add(30 * time.Second))
			n, err := upstream.Read(buf)
			if err != nil {
				if ne, ok := err.(net.Error); ok && ne.Timeout() {
					continue
				}
				log.Printf("bridge: upstream read: %v", err)
				s.close()
				return
			}
			data := make([]byte, n)
			copy(data, buf[:n])
			if err := dc.Send(data); err != nil {
				log.Printf("bridge: dc send: %v", err)
				s.close()
				return
			}
		}
	}()

	log.Printf("bridge: active dc ↔ udp %s", upstreamAddr)
}

func mustJSON(v interface{}) json.RawMessage {
	b, _ := json.Marshal(v)
	return b
}

func safeID(id *uint16) uint16 {
	if id == nil {
		return 0
	}
	return *id
}

func main() {
	configPath := flag.String("config", "config.json", "path to config JSON")
	flag.Parse()

	cfg, err := loadConfig(*configPath)
	if err != nil {
		log.Fatalf("load config: %v", err)
	}

	log.Printf("seaflow-peer starting")
	log.Printf("  signaling: %s", cfg.SignalingURL)
	log.Printf("  room:      %s", cfg.RoomID)
	log.Printf("  upstream:  udp %s", cfg.UpstreamUDP)
	log.Printf("  ice servers: %d", len(cfg.ICEServers))

	ctx, cancel := context.WithCancel(context.Background())
	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-sigCh
		log.Printf("shutdown requested")
		cancel()
	}()

	backoff := time.Second
	for {
		select {
		case <-ctx.Done():
			log.Printf("seaflow-peer stopped")
			return
		default:
		}
		if err := runOnce(ctx, cfg); err != nil {
			log.Printf("session failed: %v (reconnect in %s)", err, backoff)
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(backoff):
		}
		if backoff < 30*time.Second {
			backoff *= 2
		}
	}
}
