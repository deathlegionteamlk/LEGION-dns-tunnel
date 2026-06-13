# Legion DNS Tunnel (LGN-v2)
Custom, low-overhead SOCKS5 proxy tunnel designed to bypass extreme network firewalls by bridging raw TCP streams over authoritative DNS UDP packets.

## Key Architectural Advantages
1. **Low-Overhead Framing**: Serializes binary stream frames into tight Base32 domain label fragments to minimize standard UDP query sizing and bypass packet content inspection.
2. **Dynamic Multi-Resolver Multipathing**: The client periodically tests, tracks, and weights latency/packet checks across various public resolvers (Cloudflare, Quad9, Google, etc.), dynamically switching routes when primary tunnels drop.
3. **High-Loss Resilience**: Designed with localized thread state windows, implementing sequential packet retry loops to bridge continuous flow even during high localized UDP packet loss.
4. **Authoritative Network Bridging**: Redirects tunneled query handshakes seamlessly to real target sockets through standard, un-scraped domain namespace routing.

---

## Workspace Structure
- `client/main.go` - Local SOCKS5 server handshakes and DNS frame packer/dispatcher.
- `server/main.go` - Linux-compatible authoritative query listener, TCP socket proxy router, and TXT response coder.
- `config-client.toml` - Client resolvers parameters and local ports definitions.
- `config-server.toml` - Binding interfaces, suffix matches, and session parameters.

---

## 🚀 Deployment Instructions

### 1. Prerequisite: Authoritative DNS Domain Configuration
Since the client queries public recursive resolvers, you must own a registered domain name (e.g., `legion.net`) and point its sub-delegation to the static IP address of your Linux Server:

- On your domain registrar panel, configure an **A Record** pointing your nameserver hostname to your Linux VPS:
  ```txt
  ns1.legion.net   IN  A   <Your_Linux_VPS_Static_IP>
  ```
- Configure a **Name Server (NS) Record** pointing the sub-domain suffix to the delegated server hostname:
  ```txt
  tunnel.legion.net  IN  NS  ns1.legion.net
  ```

Now, any queries for `*.tunnel.legion.net` received by Google or Cloudflare DNS will automatically escalate to your Linux server on UDP Port 53.

### 2. Compilation
Compile both components via any terminal with Go installed:

```bash
# Build the local SOCKS5 client binary
cd go-dns-tunnel/client
go build -o vpn-client main.go

# Build the server daemon binary
cd ../server
go build -o vpn-server main.go
```

### 3. Server Startup (Linux Host)
Make sure port `53/udp` is free (e.g. systemd-resolved turned off or configured appropriately) and start the authoritative server:

```bash
sudo ./vpn-server -listen "0.0.0.0:53" -domain "tunnel.legion.net"
```

### 4. Client Startup (Local Workspace)
Launch the client with target settings:

```bash
./vpn-client -socks "127.0.0.1:1080" -domain "tunnel.legion.net"
```

### 5. Routing Browser/System Traffic
Configure your favorite web browser or system controller to route requests through SOCKS5:
- **Server / SOCKS Host**: `127.0.0.1`
- **Port**: `1080`
- Ensure "Proxy DNS requests through SOCKS5" is **ENABLED** in your browser config so leaks are completely isolated.
