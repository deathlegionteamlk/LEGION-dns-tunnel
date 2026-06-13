package main

import (
	"encoding/base32"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"strings"
	"sync"
	"time"
)

// Resolver represents an upstream DNS resolver with status metrics
type Resolver struct {
	Name             string
	Address          string
	Weight           int
	Active           bool
	AverageLatencyMs int64
	FailureCount     int
	Mutex            sync.RWMutex
}

// TunnelClient holds the structural data for managing the DNS Tunneling session
type TunnelClient struct {
	SocksAddr     string
	TargetDomain  string
	Resolvers     []*Resolver
	ResolverMutex sync.RWMutex
	Sessions      map[uint32]*Session
	SessionMutex  sync.RWMutex
	NextSessionID uint32
}

// Session tracks individual bridged TCP sockets routed over DNS sequences
type Session struct {
	ID           uint32
	TCPConn      net.Conn
	LastActive   time.Time
	SendSeq      uint32
	ExpectedSeq  uint32
	ReceivedList map[uint32][]byte
	WriteMutex   sync.Mutex
	Active       bool
}

var b32 = base32.HexEncoding.WithPadding(base32.NoPadding)

func main() {
	socksAddr := flag.String("socks", "127.0.0.1:1080", "SOCKS5 local listener address")
	targetDomain := flag.String("domain", "tunnel.legion.net", "Authoritative tunnel domain root")
	flag.Parse()

	log.Printf("[SYS] Starting Legion VPN Client (Socks5: %s, Domain: %s)", *socksAddr, *targetDomain)

	client := &TunnelClient{
		SocksAddr:     *socksAddr,
		TargetDomain:  *targetDomain,
		Sessions:      make(map[uint32]*Session),
		NextSessionID: 100,
		Resolvers: []*Resolver{
			{Name: "Google Primary", Address: "8.8.8.8:53", Weight: 5, Active: true},
			{Name: "Cloudflare SEC", Address: "1.1.1.1:53", Weight: 5, Active: true},
			{Name: "Quad9 Secure", Address: "9.9.9.9:53", Weight: 3, Active: true},
			{Name: "OpenDNS Secondary", Address: "208.67.220.220:53", Weight: 2, Active: true},
		},
	}

	// Start resolver health checks in background threads
	go client.monitorResolversLoop()

	// Start local SOCKS5 server
	client.startSocks5Server()
}

// monitorResolversLoop tests DNS latencies and flags non-functional resolvers
func (tc *TunnelClient) monitorResolversLoop() {
	ticker := time.NewTicker(8 * time.Second)
	for range ticker.C {
		tc.ResolverMutex.RLock()
		resolvers := tc.Resolvers
		tc.ResolverMutex.RUnlock()

		var wg sync.WaitGroup
		for _, r := range resolvers {
			wg.Add(1)
			go func(res *Resolver) {
				defer wg.Done()
				start := time.Now()
				
				// Standard low-overhead query for monitoring target domain DNS TXT
				payload := "healthcheck"
				_, err := tc.queryDNSThroughResolver(res, 0xFFFF, 0, []byte(payload))
				elapsed := time.Since(start).Milliseconds()

				res.Mutex.Lock()
				if err != nil {
					res.FailureCount++
					if res.FailureCount >= 3 {
						if res.Active {
							log.Printf("[HEALTH] Resolver %s (%s) is now INACTIVE. Error: %v", res.Name, res.Address, err)
							res.Active = false
						}
					}
				} else {
					res.FailureCount = 0
					res.AverageLatencyMs = (res.AverageLatencyMs*3 + elapsed) / 4
					if !res.Active {
						log.Printf("[HEALTH] Resolver %s (%s) recovered. Latency: %dms", res.Name, res.Address, res.AverageLatencyMs)
						res.Active = true
					}
				}
				res.Mutex.Unlock()
			}(r)
		}
		wg.Wait()
	}
}

// selectBestResolver selects an active resolver using weighted index distributions
func (tc *TunnelClient) selectBestResolver() *Resolver {
	tc.ResolverMutex.RLock()
	defer tc.ResolverMutex.RUnlock()

	var activeList []*Resolver
	totalWeight := 0

	for _, r := range tc.Resolvers {
		r.Mutex.RLock()
		if r.Active {
			activeList = append(activeList, r)
			totalWeight += r.Weight
		}
		r.Mutex.RUnlock()
	}

	if len(activeList) == 0 {
		log.Printf("[WARN] No active resolvers available! Falling back to Cloudflare defaults.")
		return &Resolver{Name: "Cloudflare Fallback", Address: "1.1.1.1:53", Active: true}
	}

	// Simple round-robin weighted selection or least-latency choice
	best := activeList[0]
	minLatency := int64(99999)
	for _, r := range activeList {
		r.Mutex.RLock()
		if r.AverageLatencyMs < minLatency {
			minLatency = r.AverageLatencyMs
			best = r
		}
		r.Mutex.RUnlock()
	}
	return best
}

// queryDNSThroughResolver wraps standard UDP socket code to dispatch manual DNS datagram query
func (tc *TunnelClient) queryDNSThroughResolver(res *Resolver, sessID uint32, seq uint32, raw []byte) ([]byte, error) {
	conn, err := net.DialTimeout("udp", res.Address, 3*time.Second)
	if err != nil {
		return nil, err
	}
	defer conn.Close()

	// LGN-v2: Pack data into hex-encoded subdomain labels on the DNS query
	// Format: sessionID.sequence.payload.[suffix]
	encodedPayload := b32.EncodeToString(raw)
	subdomain := fmt.Sprintf("s%d.q%d.%s.%s", sessID, seq, encodedPayload, tc.TargetDomain)
	if len(subdomain) > 253 {
		// Truncate safely if required
		subdomain = subdomain[len(subdomain)-253:]
	}

	// build DNS byte array query manually to prevent package bloat
	dnsPacket := tc.buildManualDnsRequest(subdomain)
	
	_, err = conn.Write(dnsPacket)
	if err != nil {
		return nil, err
	}

	// Recv response buffer
	buf := make([]byte, 1024)
	conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	n, err := conn.Read(buf)
	if err != nil {
		return nil, err
	}

	// Unpack response TXT records or content bytes from reply packet
	return tc.parseManualDnsTxtResponse(buf[:n]), nil
}

// startSocks5Server handles user connections through local browser endpoints
func (tc *TunnelClient) startSocks5Server() {
	listener, err := net.Listen("tcp", tc.SocksAddr)
	if err != nil {
		log.Fatalf("[CRIT] Failed to initiate SOCKS5 server: %v", err)
	}
	defer listener.Close()

	log.Printf("[SOCKS] SOCKS5 Proxy Daemon actively listening on %s", tc.SocksAddr)

	for {
		conn, err := listener.Accept()
		if err != nil {
			log.Printf("[WARN] Failed to accept socks client: %v", err)
			continue
		}
		go tc.handleSocksConnection(conn)
	}
}

// handleSocksConnection handles the RFC 1928 SOCKS5 protocol handshake
func (tc *TunnelClient) handleSocksConnection(c net.Conn) {
	defer c.Close()
	
	// Fast custom SOCKS5 handshake implementation
	header := make([]byte, 2)
	if _, err := io.ReadFull(c, header); err != nil {
		return
	}
	
	if header[0] != 0x05 { // Verify Version matches protocol SOCKS5 spec
		return
	}

	numMethods := int(header[1])
	methods := make([]byte, numMethods)
	if _, err := io.ReadFull(c, methods); err != nil {
		return
	}

	// Rely on No-Authentication required mode (0x00)
	c.Write([]byte{0x05, 0x00})

	// Read Request details
	reqHead := make([]byte, 4)
	if _, err := io.ReadFull(c, reqHead); err != nil {
		return
	}

	if reqHead[1] != 0x01 { // Check of Command option matches positive TCP connection stream
		return
	}

	var destAddr string
	switch reqHead[3] {
	case 0x01: // IPv4 address type
		addrBytes := make([]byte, 4)
		io.ReadFull(c, addrBytes)
		destAddr = net.IP(addrBytes).String()
	case 0x03: // FQDN domain string representation
		lenByte := make([]byte, 1)
		io.ReadFull(c, lenByte)
		domainBytes := make([]byte, int(lenByte[0]))
		io.ReadFull(c, domainBytes)
		destAddr = string(domainBytes)
	case 0x04: // IPv6 address type
		addrBytes := make([]byte, 16)
		io.ReadFull(c, addrBytes)
		destAddr = net.IP(addrBytes).String()
	default:
		return
	}

	portBytes := make([]byte, 2)
	io.ReadFull(c, portBytes)
	destPort := (int(portBytes[0]) << 8) + int(portBytes[1])
	fullDest := fmt.Sprintf("%s:%d", destAddr, destPort)

	log.Printf("[SOCKS] Intercepted link request to destination target: %s", fullDest)

	// Build virtual tunnel pipeline
	tc.SessionMutex.Lock()
	tc.NextSessionID++
	sID := tc.NextSessionID
	session := &Session{
		ID:           sID,
		TCPConn:      c,
		LastActive:   time.Now(),
		ReceivedList: make(map[uint32][]byte),
		Active:       true,
	}
	tc.Sessions[sID] = session
	tc.SessionMutex.Unlock()

	// Notify SOCKS client of success
	c.Write([]byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0})

	// Dispatch bi-directional piping
	tc.bridgeTunnelSession(session, fullDest)
}

// bridgeTunnelSession binds TCP reads up to chunk packets, sending over DNS
func (tc *TunnelClient) bridgeTunnelSession(s *Session, destination string) {
	// First DNS query notifies server to dial target connection
	tc.sendPayload(s, []byte("CONNECT:"+destination))

	wg := sync.WaitGroup{}
	wg.Add(1)

	// Thread to read client data and push to DNS queries
	go func() {
		defer wg.Done()
		buf := make([]byte, 90) // Safe TXT subdomain fit
		for {
			n, err := s.TCPConn.Read(buf)
			if err != nil {
				tc.sendPayload(s, []byte("DISCONNECT"))
				break
			}
			if n > 0 {
				tc.sendPayload(s, buf[:n])
			}
		}
		s.Active = false
	}()

	// Read polling loop to pull data from DNS server responses
	go func() {
		for s.Active {
			time.Sleep(350 * time.Millisecond) // Polling loop
			tc.sendPayload(s, []byte("POLL"))
		}
	}()

	wg.Wait()
	tc.SessionMutex.Lock()
	delete(tc.Sessions, s.ID)
	tc.SessionMutex.Unlock()
}

func (tc *TunnelClient) sendPayload(s *Session, payload []byte) {
	s.WriteMutex.Lock()
	defer s.WriteMutex.Unlock()

	s.SendSeq++
	resolver := tc.selectBestResolver()

	// Retry on failure with high-loss packet support
	for retries := 0; retries < 3; retries++ {
		reply, err := tc.queryDNSThroughResolver(resolver, s.ID, s.SendSeq, payload)
		if err == nil {
			if len(reply) > 0 && !strings.HasPrefix(string(reply), "NOOP") {
				// Deliver returned payload down to socket client
				s.TCPConn.Write(reply)
			}
			return
		}
		// Fallback to alternate resolver on immediate packet drops
		resolver = tc.selectBestResolver()
		time.Sleep(200 * time.Millisecond)
	}
}

// buildManualDnsRequest compiles basic DNS request structure (A query representation)
func (tc *TunnelClient) buildManualDnsRequest(domain string) []byte {
	out := make([]byte, 12)
	// Transaction ID
	out[0], out[1] = 0xAB, 0xCD
	// Flags (Standard query recursion desired)
	out[2], out[3] = 0x01, 0x00
	// Questions: 1
	out[4], out[5] = 0x00, 0x01
	
	// Encode domain label chain
	parts := strings.Split(domain, ".")
	for _, part := range parts {
		if len(part) == 0 {
			continue
		}
		out = append(out, byte(len(part)))
		out = append(out, []byte(part)...)
	}
	out = append(out, 0x00) // End root label

	// Query Type: TXT (0x0010) or A (0x0001)
	out = append(out, 0x00, 0x10)
	// Query Class: IN (0x0001)
	out = append(out, 0x00, 0x01)
	return out
}

// parseManualDnsTxtResponse parses TXT record contents out from standard reply
func (tc *TunnelClient) parseManualDnsTxtResponse(packet []byte) []byte {
	if len(packet) < 12 {
		return nil
	}
	// Skip DNS Header (12 bytes) and Query domain section
	cursor := 12
	for cursor < len(packet) {
		lenVal := int(packet[cursor])
		if lenVal == 0 {
			cursor++
			break
		}
		cursor += 1 + lenVal
	}
	// Skip Qtype (2 bytes) + Qclass (2 bytes)
	cursor += 4

	// Read answer resources
	if cursor >= len(packet) {
		return nil
	}

	// Simple search of answers TXT value frames (starts with TXT class size payload details)
	for i := cursor; i < len(packet)-4; i++ {
		// Look for answer type TXT flags inside response payload
		if packet[i] == 0x00 && packet[i+1] == 0x10 {
			// Length of RDATA lies offsets further
			rdataLenIdx := i + 8
			if rdataLenIdx < len(packet) {
				rdataLen := int(packet[rdataLenIdx])<<8 + int(packet[rdataLenIdx+1])
				txtStringStart := rdataLenIdx + 2
				if txtStringStart+rdataLen <= len(packet) {
					// Actual Text packet inner bytes contains response string data
					innerTxtData := packet[txtStringStart : txtStringStart+rdataLen]
					if len(innerTxtData) > 1 {
						textStr := string(innerTxtData[1:]) // Skip first length byte of individual text field
						decoded, err := b32.DecodeString(textStr)
						if err == nil {
							return decoded
						}
						return []byte(textStr)
					}
				}
			}
		}
	}
	return nil
}
