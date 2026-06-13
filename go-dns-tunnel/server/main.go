package main

import (
	"encoding/base32"
	"flag"
	"fmt"
	"log"
	"net"
	"strings"
	"sync"
	"time"
)

// ServerSession handles single virtual DNS-to-TCP socket stream
type ServerSession struct {
	ID         uint32
	TCPConn    net.Conn
	DataBuffer []byte
	BufferLock sync.Mutex
	LastPoke   time.Time
}

type TunnelServer struct {
	ListenAddr   string
	TargetDomain string
	Sessions     map[uint32]*ServerSession
	SessionLock  sync.Mutex
}

var b32 = base32.HexEncoding.WithPadding(base32.NoPadding)

func main() {
	listenAddr := flag.String("listen", "0.0.0.0:53", "Authoritative UDP DNS bind port")
	domainSuffix := flag.String("domain", "tunnel.legion.net", "Authoritative namespace root suffix")
	flag.Parse()

	log.Printf("[SYS] Starting Legion VPN Server Daemon...")
	server := &TunnelServer{
		ListenAddr:   *listenAddr,
		TargetDomain: *domainSuffix,
		Sessions:     make(map[uint32]*ServerSession),
	}

	// Start background scavenger to sweep dead/inactive TCP socket proxies
	go server.sessionsScavenger()

	server.startDnsListener()
}

func (ts *TunnelServer) sessionsScavenger() {
	ticker := time.NewTicker(30 * time.Second)
	for range ticker.C {
		ts.SessionLock.Lock()
		now := time.Now()
		for id, sess := range ts.Sessions {
			if now.Sub(sess.LastPoke) > 90*time.Second {
				log.Printf("[GC] Sweeping idle session identifier: s%d", id)
				if sess.TCPConn != nil {
					sess.TCPConn.Close()
				}
				delete(ts.Sessions, id)
			}
		}
		ts.SessionLock.Unlock()
	}
}

func (ts *TunnelServer) startDnsListener() {
	conn, err := net.ListenPacket("udp", ts.ListenAddr)
	if err != nil {
		log.Fatalf("[CRIT] Impossible to bind UDP server port 53 listener: %v", err)
	}
	defer conn.Close()

	log.Printf("[DNS] DNS service daemon listening for queries on: %s", ts.ListenAddr)
	buf := make([]byte, 2048)

	for {
		n, addr, err := conn.ReadFrom(buf)
		if err != nil {
			log.Printf("[WARN] Error reading UDP channel package: %v", err)
			continue
		}

		go ts.processDnsPacket(conn, addr, buf[:n])
	}
}

func (ts *TunnelServer) processDnsPacket(conn net.PacketConn, clientAddr net.Addr, val []byte) {
	if len(val) < 12 {
		return
	}

	// Read Question Section to resolve requested subdomain
	subdomain, ok := ts.extractSubdomain(val)
	if !ok || !strings.HasSuffix(subdomain, ts.TargetDomain) {
		return
	}

	// Split labels: s[id].q[seq].[b32payload].tunnel.legion.net
	parts := strings.Split(subdomain, ".")
	if len(parts) < 4 {
		return
	}

	var sessID uint32
	var seqID uint32
	fmt.Sscanf(parts[0], "s%d", &sessID)
	fmt.Sscanf(parts[1], "q%d", &seqID)

	encodedPayload := parts[2]
	payloadBytes, err := b32.DecodeString(encodedPayload)
	if err != nil {
		ts.respondWithRaw(conn, clientAddr, val, []byte("ERROR_BAD_ENCODING"))
		return
	}

	// Health checking mock request
	if sessID == 0xFFFF {
		ts.respondWithRaw(conn, clientAddr, val, []byte("HEALTH_OK"))
		return
	}

	ts.SessionLock.Lock()
	sess, exists := ts.Sessions[sessID]
	if !exists {
		sess = &ServerSession{
			ID:       sessID,
			LastPoke: time.Now(),
		}
		ts.Sessions[sessID] = sess
	}
	ts.SessionLock.Unlock()

	sess.LastPoke = time.Now()

	// Parse Command or Stream Routing payload
	cmd := string(payloadBytes)
	var responseData []byte

	switch {
	case strings.HasPrefix(cmd, "CONNECT:"):
		dest := strings.TrimPrefix(cmd, "CONNECT:")
		log.Printf("[VPN] Connecting tunnel proxy thread to destination: %s", dest)
		
		// Close existing connections
		if sess.TCPConn != nil {
			sess.TCPConn.Close()
		}

		dialerConn, err := net.DialTimeout("tcp", dest, 6*time.Second)
		if err != nil {
			log.Printf("[ERR] Failed connection forward: %v", err)
			responseData = []byte("CONNECT_FAILED")
		} else {
			sess.TCPConn = dialerConn
			responseData = []byte("CONNECT_SUCCESS")
			
			// Launch copy listener thread
			go ts.readTcpToSessionBuffer(sess)
		}

	case cmd == "DISCONNECT":
		log.Printf("[VPN] Virtual disconnect signal for session s%d", sessID)
		if sess.TCPConn != nil {
			sess.TCPConn.Close()
		}
		responseData = []byte("DISCONNECTED")

	case cmd == "POLL":
		// Flush current TCP buffer out to DNS reply
		sess.BufferLock.Lock()
		if len(sess.DataBuffer) > 0 {
			chunkSize := 80
			if len(sess.DataBuffer) < chunkSize {
				chunkSize = len(sess.DataBuffer)
			}
			responseData = sess.DataBuffer[:chunkSize]
			sess.DataBuffer = sess.DataBuffer[chunkSize:]
		} else {
			responseData = []byte("NOOP")
		}
		sess.BufferLock.Unlock()

	default:
		// Push client stream data into real connection socket
		if sess.TCPConn != nil {
			_, err = sess.TCPConn.Write(payloadBytes)
			if err != nil {
				responseData = []byte("ERR_WRITE_FAIL")
			} else {
				// Flush any incoming target TCP back buffers
				sess.BufferLock.Lock()
				if len(sess.DataBuffer) > 0 {
					chunkSize := 80
					if len(sess.DataBuffer) < chunkSize {
						chunkSize = len(sess.DataBuffer)
					}
					responseData = sess.DataBuffer[:chunkSize]
					sess.DataBuffer = sess.DataBuffer[chunkSize:]
				} else {
					responseData = []byte("ACK")
				}
				sess.BufferLock.Unlock()
			}
		} else {
			responseData = []byte("ERR_NO_SOCKET")
		}
	}

	ts.respondWithRaw(conn, clientAddr, val, responseData)
}

func (ts *TunnelServer) readTcpToSessionBuffer(sess *ServerSession) {
	buf := make([]byte, 1024)
	for {
		n, err := sess.TCPConn.Read(buf)
		if err != nil {
			break
		}
		if n > 0 {
			sess.BufferLock.Lock()
			// Append network slice
			sess.DataBuffer = append(sess.DataBuffer, buf[:n]...)
			if len(sess.DataBuffer) > 65536 { // safeguard buffer length
				sess.DataBuffer = sess.DataBuffer[len(sess.DataBuffer)-65536:]
			}
			sess.BufferLock.Unlock()
		}
	}
}

func (ts *TunnelServer) extractSubdomain(packet []byte) (string, bool) {
	cursor := 12
	var labels []string
	for {
		if cursor >= len(packet) {
			return "", false
		}
		length := int(packet[cursor])
		if length == 0 {
			break
		}
		if cursor+1+length > len(packet) {
			return "", false
		}
		labels = append(labels, string(packet[cursor+1:cursor+1+length]))
		cursor += 1 + length
	}
	return strings.Join(labels, "."), true
}

// respondWithRaw packages custom byte responses inside DNS authoritative response header
func (ts *TunnelServer) respondWithRaw(conn net.PacketConn, clientAddr net.Addr, queryPacket []byte, content []byte) {
	// Echo Transaction ID
	out := make([]byte, 12)
	out[0], out[1] = queryPacket[0], queryPacket[1]
	// Flags: Authoritative, No error, Response (0x8500)
	out[2], out[3] = 0x85, 0x00
	// Questions count: 1
	out[4], out[5] = 0x00, 0x01
	// Answers count: 1
	out[6], out[7] = 0x00, 0x01

	// Append requested query question block directly
	cursor := 12
	for {
		length := int(queryPacket[cursor])
		if length == 0 {
			out = append(out, 0x00)
			cursor++
			break
		}
		out = append(out, queryPacket[cursor:cursor+1+length]...)
		cursor += 1 + length
	}
	// Append QType (2 bytes) + QClass (2 bytes)
	out = append(out, queryPacket[cursor:cursor+4]...)

	// Answer RR Structure starting with compressed reference back to question domain (0xC00C)
	out = append(out, 0xC0, 0x0C)
	// Type: TXT (0x0010)
	out = append(out, 0x00, 0x10)
	// Class: IN (0x0001)
	out = append(out, 0x00, 0x01)
	// TTL: 5 seconds (0x00000005)
	out = append(out, 0x00, 0x00, 0x00, 0x05)

	// Format response bytes via clean, low-overhead B32 format
	b32Response := b32.EncodeToString(content)
	rdataLen := len(b32Response) + 1 // Plus length byte for individual string section

	// RData Length in bytes
	out = append(out, byte(rdataLen>>8), byte(rdataLen&0xFF))
	// TXT entry characters list (length prefix + actual string)
	out = append(out, byte(len(b32Response)))
	out = append(out, []byte(b32Response)...)

	conn.WriteTo(out, clientAddr)
}
