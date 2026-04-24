// seaflow-testclient — minimal WebRTC client for end-to-end verification.
//
// Opens a DataChannel via the signaling worker, sends a burst of random
// bytes, reports RX/TX totals and the selected ICE candidate pair. Does
// NOT speak aivpn protocol (no crypto) — purpose is only to prove the
// signaling+TURN+bridge plumbing works to the server's UDP upstream.

package main

import (
	"context"
	"crypto/rand"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net/url"
	"os"
	"os/signal"
	"sync/atomic"
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
	ICEServers   []ICEServer `json:"ice_servers"`
}

type sigMessage struct {
	Type      string          `json:"type"`
	SDP       string          `json:"sdp,omitempty"`
	Candidate json.RawMessage `json:"candidate,omitempty"`
}

func loadConfig(path string) (*Config, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var c Config
	if err := json.Unmarshal(data, &c); err != nil {
		return nil, err
	}
	return &c, nil
}

func toPion(in []ICEServer) []webrtc.ICEServer {
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

func main() {
	cfgPath := flag.String("config", "testclient.json", "config file")
	bytesPerSec := flag.Int("rate", 8192, "bytes/s to send while DC is open")
	duration := flag.Duration("duration", 30*time.Second, "test duration after DC opens")
	flag.Parse()

	cfg, err := loadConfig(*cfgPath)
	if err != nil {
		log.Fatalf("load config: %v", err)
	}

	u, err := url.Parse(cfg.SignalingURL)
	if err != nil {
		log.Fatalf("parse signaling url: %v", err)
	}
	switch u.Scheme {
	case "http":
		u.Scheme = "ws"
	case "https":
		u.Scheme = "wss"
	}
	u.Path = "/ws"
	q := u.Query()
	q.Set("role", "client")
	q.Set("room", cfg.RoomID)
	u.RawQuery = q.Encode()

	log.Printf("dialing signaling: %s", u.String())
	ctx, cancel := context.WithCancel(context.Background())
	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
	go func() { <-sigCh; cancel() }()

	ws, _, err := websocket.DefaultDialer.DialContext(ctx, u.String(), nil)
	if err != nil {
		log.Fatalf("ws dial: %v", err)
	}
	defer ws.Close()
	log.Printf("signaling: connected")

	writeCh := make(chan []byte, 64)
	go func() {
		for msg := range writeCh {
			ws.SetWriteDeadline(time.Now().Add(10 * time.Second))
			if err := ws.WriteMessage(websocket.TextMessage, msg); err != nil {
				log.Printf("ws write: %v", err)
				return
			}
		}
	}()

	pc, err := webrtc.NewPeerConnection(webrtc.Configuration{ICEServers: toPion(cfg.ICEServers)})
	if err != nil {
		log.Fatalf("pc new: %v", err)
	}
	defer pc.Close()

	pc.OnICECandidate(func(c *webrtc.ICECandidate) {
		if c == nil {
			return
		}
		b, _ := json.Marshal(sigMessage{Type: "ice", Candidate: mustJSON(c.ToJSON())})
		writeCh <- b
	})

	pc.OnICEConnectionStateChange(func(s webrtc.ICEConnectionState) {
		log.Printf("ice state: %s", s)
	})
	pc.OnConnectionStateChange(func(s webrtc.PeerConnectionState) {
		log.Printf("pc state: %s", s)
	})

	dc, err := pc.CreateDataChannel("seaflow", nil)
	if err != nil {
		log.Fatalf("create dc: %v", err)
	}

	var rx, tx atomic.Uint64
	openCh := make(chan struct{})
	doneCh := make(chan struct{})

	dc.OnOpen(func() {
		log.Printf("datachannel: OPEN")
		close(openCh)
	})
	dc.OnClose(func() {
		log.Printf("datachannel: CLOSED (rx=%d tx=%d)", rx.Load(), tx.Load())
		select {
		case <-doneCh:
		default:
			close(doneCh)
		}
	})
	dc.OnMessage(func(msg webrtc.DataChannelMessage) {
		rx.Add(uint64(len(msg.Data)))
	})

	offer, err := pc.CreateOffer(nil)
	if err != nil {
		log.Fatalf("create offer: %v", err)
	}
	if err := pc.SetLocalDescription(offer); err != nil {
		log.Fatalf("set local: %v", err)
	}
	b, _ := json.Marshal(sigMessage{Type: "offer", SDP: offer.SDP})
	writeCh <- b
	log.Printf("offer sent")

	// Read signaling messages until DC open or timeout
	ansCh := make(chan struct{})
	go func() {
		for {
			_, raw, err := ws.ReadMessage()
			if err != nil {
				return
			}
			var m sigMessage
			if err := json.Unmarshal(raw, &m); err != nil {
				continue
			}
			switch m.Type {
			case "answer":
				log.Printf("answer received (%d bytes SDP)", len(m.SDP))
				if err := pc.SetRemoteDescription(webrtc.SessionDescription{Type: webrtc.SDPTypeAnswer, SDP: m.SDP}); err != nil {
					log.Printf("set remote: %v", err)
				}
				close(ansCh)
			case "ice":
				var cand webrtc.ICECandidateInit
				if err := json.Unmarshal(m.Candidate, &cand); err != nil {
					continue
				}
				if err := pc.AddICECandidate(cand); err != nil {
					log.Printf("add ice: %v", err)
				}
			}
		}
	}()

	select {
	case <-ansCh:
	case <-time.After(15 * time.Second):
		log.Fatalf("no answer within 15s — peer server probably not connected to signaling")
	case <-ctx.Done():
		return
	}

	select {
	case <-openCh:
	case <-time.After(30 * time.Second):
		log.Fatalf("datachannel did not open within 30s — ICE failed")
	case <-ctx.Done():
		return
	}

	// Log the selected candidate pair (diagnostic)
	if stats := pc.GetStats(); stats != nil {
		for _, s := range stats {
			if pair, ok := s.(webrtc.ICECandidatePairStats); ok && pair.Nominated {
				log.Printf("ICE pair nominated: local=%s remote=%s", pair.LocalCandidateID, pair.RemoteCandidateID)
			}
		}
	}

	log.Printf("sending %d bytes/s for %s", *bytesPerSec, *duration)
	deadline := time.Now().Add(*duration)
	chunk := make([]byte, 256)
	statsTick := time.NewTicker(2 * time.Second)
	defer statsTick.Stop()

	for time.Now().Before(deadline) {
		select {
		case <-ctx.Done():
			return
		case <-doneCh:
			log.Fatalf("datachannel closed early")
		case <-statsTick.C:
			log.Printf("stats: rx=%d tx=%d", rx.Load(), tx.Load())
		default:
		}
		if _, err := rand.Read(chunk); err == nil {
			if err := dc.Send(chunk); err != nil {
				log.Printf("dc send: %v", err)
				return
			}
			tx.Add(uint64(len(chunk)))
		}
		time.Sleep(time.Duration(float64(time.Second) * float64(len(chunk)) / float64(*bytesPerSec)))
	}

	log.Printf("done. final rx=%d tx=%d", rx.Load(), tx.Load())
	if rx.Load() == 0 {
		log.Printf("note: aivpn-server silently drops invalid packets, so rx=0 is expected for this test — what matters is whether dc→upstream worked, check seaflow-peer logs")
	}

	byeB, _ := json.Marshal(sigMessage{Type: "bye"})
	writeCh <- byeB
	time.Sleep(500 * time.Millisecond)
}

func mustJSON(v interface{}) json.RawMessage {
	b, _ := json.Marshal(v)
	return b
}

func init() {
	log.SetFlags(log.LstdFlags | log.Lmicroseconds)
	_ = fmt.Sprintf
}
