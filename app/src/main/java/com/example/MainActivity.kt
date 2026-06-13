package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Legion VPN Connection States.
 */
enum class VpnState {
  DISCONNECTED,
  CONNECTING,
  CONNECTED
}

/**
 * Legion APP Navigation Tabs.
 */
enum class AppTab {
  RELAY,
  LOGS,
  RESILIENCE,
  ACCOUNT
}

/**
 * Model representing a Resilience DNS Tunnel Proxy Node.
 */
data class DnsNode(
  val name: String,
  val ip: String,
  val ping: Int,
  val load: Int,
  val isRecommended: Boolean = false
)

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      MyApplicationTheme(dynamicColor = false) {
        LegionVpnApp()
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegionVpnApp() {
  var vpnState by remember { mutableStateOf(VpnState.DISCONNECTED) }
  var currentTab by remember { mutableStateOf(AppTab.RELAY) }
  
  // App Config States
  var activeProxy by remember { mutableStateOf("127.0.0.1:1080") }
  var selectedNodeIndex by remember { mutableStateOf(0) }
  var multipathStabilityEnabled by remember { mutableStateOf(true) }
  var bypassLocalTraffic by remember { mutableStateOf(true) }
  var selectedProtocol by remember { mutableStateOf("LGN-v2") }
  
  // Simulated Dynamic Metrics
  var latency by remember { mutableStateOf(0) }
  var activeNodes by remember { mutableStateOf("0/12") }
  var packetLoss by remember { mutableStateOf(0.0f) }
  
  // Monospaced logs state list
  val logsList = remember { mutableStateListOf<String>() }
  val scope = rememberCoroutineScope()
  
  // Available Node Rosters for Resilience Screen
  val dnsNodes = remember {
    listOf(
      DnsNode("US-East Transit", "142.250.190.46", 45, 34, true),
      DnsNode("EU-West Core Gateway", "172.217.16.142", 82, 12),
      DnsNode("SG-Direct Secure Route", "216.58.200.41", 120, 56),
      DnsNode("Cloudflare DNS Sec", "1.1.1.1", 14, 88),
      DnsNode("Google Public Primary", "8.8.8.8", 18, 92)
    )
  }

  // Initial logs populate
  LaunchedEffect(Unit) {
    if (logsList.isEmpty()) {
      logsList.add("[SYS] Legion VPN secure stack initialized.")
      logsList.add("[SYS] Secure DNS Tunnel daemon is sleeping.")
      logsList.add("[SYS] Ready for Tunnel Connection.")
    }
  }

  // Ticker for logs and real-time DNS telemetry stream
  LaunchedEffect(vpnState, selectedNodeIndex) {
    if (vpnState == VpnState.CONNECTED) {
      latency = dnsNodes[selectedNodeIndex].ping + (-4..6).random()
      activeNodes = "8/12"
      packetLoss = 0.0f

      // Periodically append live logs & vary metrics
      while (true) {
        delay((1500..3500).random().toLong())
        val randomDomains = listOf(
          "dns.google", "api.github.com", "android.clients.google.com",
          "discordapp.com", "identity-tunnel.legion.net", "connectivitycheck.gstatic.com"
        )
        val domain = randomDomains.random()
        val queryType = listOf("A", "AAAA", "TXT", "CNAME").random()
        val resolveTime = (5..25).random()
        
        logsList.add("[DNS] Query root domain: $domain ($queryType)")
        logsList.add("[DNS] Resolved via Proxy ${dnsNodes[selectedNodeIndex].ip} in ${resolveTime}ms [SUCCESS]")
        
        latency = (dnsNodes[selectedNodeIndex].ping + (-6..6).random()).coerceIn(4, 300)
        packetLoss = if (Math.random() < 0.1) 0.1f else 0.0f
        
        // Keep logs list clean (only last 60 lines)
        if (logsList.size > 80) {
          logsList.removeRange(0, logsList.size - 60)
        }
      }
    } else {
      latency = 0
      activeNodes = "0/12"
      packetLoss = 0.0f
    }
  }

  // Transition Engine
  val triggerConnect: () -> Unit = {
    scope.launch {
      if (vpnState == VpnState.CONNECTED) {
        vpnState = VpnState.DISCONNECTED
        logsList.add("[SYS] User initialized disconnect.")
        logsList.add("[TUN] Safe shutdown signal dispatched.")
        logsList.add("[SYS] Connection Terminated. Local routes restored.")
      } else if (vpnState == VpnState.DISCONNECTED) {
        vpnState = VpnState.CONNECTING
        logsList.add("[SYS] Spawning Legion Tunnel subprocess...")
        logsList.add("[TUN] Binding proxy interface at IPv4 $activeProxy...")
        delay(600)
        logsList.add("[DNS] Attempting handshake with active resolver: ${dnsNodes[selectedNodeIndex].ip}")
        delay(800)
        logsList.add("[TUN] Authenticated successfully. Certificate verified.")
        vpnState = VpnState.CONNECTED
        logsList.add("[SYS] DNS Tunnel securely bridged. Multipath Active.")
        logsList.add("[SYS] SECURE TUNNEL ACTIVE (LGN-v2 Tunnel)")
      }
    }
  }

  Scaffold(
    modifier = Modifier.fillMaxSize(),
    containerColor = Slate50,
    bottomBar = {
      LegionBottomNavigation(
        currentTab = currentTab,
        onTabSelected = { currentTab = it }
      )
    }
  ) { paddingValues ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(paddingValues)
    ) {
      Column(
        modifier = Modifier.fillMaxSize()
      ) {
        // App Header
        AppHeader(
          vpnState = vpnState,
          onSettingsClicked = {
            // Simply navigates to resilience for server config
            currentTab = AppTab.RESILIENCE
          }
        )

        // Contents Based on Tabs
        Box(
          modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
        ) {
          when (currentTab) {
            AppTab.RELAY -> {
              RelayTabContent(
                vpnState = vpnState,
                triggerConnect = triggerConnect,
                activeProxy = activeProxy,
                latency = latency,
                activeNodes = activeNodes,
                packetLoss = packetLoss,
                selectedProtocol = selectedProtocol,
                onProxyConfigClick = {
                  // Cycle IP port
                  activeProxy = if (activeProxy == "127.0.0.1:1080") "127.0.0.1:8080" else "127.0.0.1:1080"
                }
              )
            }
            AppTab.LOGS -> {
              LogsTabContent(
                logsList = logsList,
                vpnState = vpnState,
                onClearClick = {
                  logsList.clear()
                  logsList.add("[SYS] Terminal logs cleared.")
                }
              )
            }
            AppTab.RESILIENCE -> {
              ResilienceTabContent(
                nodes = dnsNodes,
                selectedIndex = selectedNodeIndex,
                onNodeSelected = {
                  selectedNodeIndex = it
                  // If connected, simulated reconnect or update IP logs
                  if (vpnState == VpnState.CONNECTED) {
                    logsList.add("[SYS] Switch route to node: ${dnsNodes[it].name} (${dnsNodes[it].ip})")
                  }
                },
                multipathStability = multipathStabilityEnabled,
                onMultipathToggle = { multipathStabilityEnabled = it },
                bypassLocal = bypassLocalTraffic,
                onBypassToggle = { bypassLocalTraffic = it },
                protocol = selectedProtocol,
                onProtocolSelected = { selectedProtocol = it }
              )
            }
            AppTab.ACCOUNT -> {
              AccountTabContent(
                vpnState = vpnState,
                dnsNodes = dnsNodes,
                selectedNodeIndex = selectedNodeIndex
              )
            }
          }
        }
      }
    }
  }
}

/**
 * Top App Bar following Geometric Balance layout patterns.
 */
@Composable
fun AppHeader(
  vpnState: VpnState,
  onSettingsClicked: () -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .statusBarsPadding()
      .background(Color.White)
      .border(1.dp, Slate100)
      .padding(horizontal = 24.dp, vertical = 18.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      // Dynamic Glowing Logo Indicator
      val logoBgColor by animateColorAsState(
        targetValue = when (vpnState) {
          VpnState.CONNECTED -> Emerald500
          VpnState.CONNECTING -> Amber600
          VpnState.DISCONNECTED -> Indigo600
        },
        animationSpec = tween(500)
      )
      
      Box(
        modifier = Modifier
          .size(44.dp)
          .shadow(8.dp, shape = RoundedCornerShape(12.dp), ambientColor = Indigo200, spotColor = Indigo200)
          .clip(RoundedCornerShape(12.dp))
          .background(logoBgColor)
          .testTag("app_logo")
          .padding(8.dp),
        contentAlignment = Alignment.Center
      ) {
        Icon(
          imageVector = if (vpnState == VpnState.CONNECTED) Icons.Default.Lock else Icons.Default.Shield,
          contentDescription = "Shield Guard",
          tint = Color.White,
          modifier = Modifier.size(24.dp)
        )
      }

      Column {
        Text(
          text = "Legion VPN",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          color = Slate800,
          letterSpacing = 0.2.sp
        )
        Text(
          text = "SECURE DNS TUNNEL",
          style = MaterialTheme.typography.labelSmall,
          fontWeight = FontWeight.SemiBold,
          color = Slate400,
          letterSpacing = 1.5.sp
        )
      }
    }

    // Settings Action Button
    IconButton(
      onClick = onSettingsClicked,
      modifier = Modifier
        .size(44.dp)
        .shadow(1.dp, CircleShape)
        .clip(CircleShape)
        .background(Slate100)
        .testTag("settings_button")
    ) {
      Icon(
        imageVector = Icons.Default.Settings,
        contentDescription = "Tunnel Settings",
        tint = Slate500,
        modifier = Modifier.size(20.dp)
      )
    }
  }
}

/**
 * RELAY TAB: Connection status card, concentric balance Orb button, and metrics grid.
 */
@Composable
fun RelayTabContent(
  vpnState: VpnState,
  triggerConnect: () -> Unit,
  activeProxy: String,
  latency: Int,
  activeNodes: String,
  packetLoss: Float,
  selectedProtocol: String,
  onProxyConfigClick: () -> Unit
) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.SpaceBetween
  ) {
    
    // Status Card Container
    Card(
      modifier = Modifier
        .fillMaxWidth()
        .border(1.dp, Slate100, RoundedCornerShape(28.dp))
        .shadow(
          elevation = 4.dp,
          shape = RoundedCornerShape(28.dp),
          ambientColor = Slate100,
          spotColor = Slate100
        ),
      colors = CardDefaults.cardColors(containerColor = Color.White),
      shape = RoundedCornerShape(28.dp)
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(vertical = 24.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        // Status Pill Badge
        val (badgeBg, badgeText, badgeLabel) = when (vpnState) {
          VpnState.CONNECTED -> Triple(Emerald50, Emerald500, "SECURE TUNNEL ACTIVE")
          VpnState.CONNECTING -> Triple(Amber50, Amber600, "NEGOTIATING HANDSHAKE")
          VpnState.DISCONNECTED -> Triple(Slate100, Slate500, "READY TO CONNECT")
        }

        Box(
          modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(badgeBg)
            .border(1.dp, badgeText.copy(alpha = 0.15f), RoundedCornerShape(24.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
          Text(
            text = badgeLabel,
            color = badgeText,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            letterSpacing = 1.sp
          )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Symmetrical Connection Orb
        SymmetricalConnectionOrb(
          vpnState = vpnState,
          onConnectToggle = triggerConnect
        )

        Spacer(modifier = Modifier.height(28.dp))

        // IP Proxy text with tap interaction to cycle proxy
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onProxyConfigClick)
            .padding(6.dp)
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            Text(
              text = "Proxy Destination:",
              style = MaterialTheme.typography.bodySmall,
              color = Slate500,
              fontWeight = FontWeight.Medium
            )
            Text(
              text = activeProxy,
              style = MaterialTheme.typography.bodySmall,
              color = Indigo600,
              fontWeight = FontWeight.Bold
            )
          }
          Text(
            text = "Multipath Channel Stability: High",
            style = MaterialTheme.typography.labelSmall,
            color = Slate400,
            fontSize = 10.sp
          )
        }
      }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Symmetric Metrics Grid (2x2 Structure)
    MetricsGrid(
      latency = latency,
      activeNodes = activeNodes,
      packetLoss = packetLoss,
      protocol = selectedProtocol
    )
  }
}

/**
 * Concentric animated Connection Orb drawn dynamically with Canvas and infinite transition specs.
 */
@Composable
fun SymmetricalConnectionOrb(
  vpnState: VpnState,
  onConnectToggle: () -> Unit
) {
  val infiniteTransition = rememberInfiniteTransition(label = "orb_rotation")
  
  // Rotating dashed line animation when active or connecting
  val angleTransition by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = 360f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = if (vpnState == VpnState.DISCONNECTED) 25000 else 6000, easing = LinearEasing),
      repeatMode = RepeatMode.Restart
    ),
    label = "dashed_spin"
  )

  // Pulsing scale for nested circles
  val pulseScale by infiniteTransition.animateFloat(
    initialValue = 1.0f,
    targetValue = if (vpnState == VpnState.CONNECTING) 1.08f else if (vpnState == VpnState.CONNECTED) 1.04f else 1.0f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Reverse
    ),
    label = "glowing_pulse"
  )

  Box(
    modifier = Modifier
      .size(196.dp)
      .testTag("connection_orb_container"),
    contentAlignment = Alignment.Center
  ) {
    // Dynamic orbiting system
    Canvas(
      modifier = Modifier
        .fillMaxSize()
        .rotate(angleTransition)
    ) {
      // Draw outer dashed circular border
      drawCircle(
        color = when (vpnState) {
          VpnState.CONNECTED -> Emerald500.copy(alpha = 0.4f)
          VpnState.CONNECTING -> Amber600.copy(alpha = 0.4f)
          VpnState.DISCONNECTED -> Slate300
        },
        radius = size.minDimension / 2.0f,
        style = Stroke(
          width = 2.dp.toPx(),
          pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 15f), 0f)
        )
      )
    }

    // Outer soft pulse shadow for high-fidelity response
    Box(
      modifier = Modifier
        .size(164.dp)
        .scale(pulseScale)
        .shadow(
          elevation = if (vpnState == VpnState.CONNECTED) 16.dp else 0.dp,
          shape = CircleShape,
          ambientColor = if (vpnState == VpnState.CONNECTED) Emerald500 else Color.Transparent,
          spotColor = if (vpnState == VpnState.CONNECTED) Emerald500 else Color.Transparent
        )
        .clip(CircleShape)
        .background(
          when (vpnState) {
            VpnState.CONNECTED -> Emerald50.copy(alpha = 0.9f)
            VpnState.CONNECTING -> Amber50.copy(alpha = 0.9f)
            VpnState.DISCONNECTED -> Slate100
          }
        ),
      contentAlignment = Alignment.Center
    ) {
      // Center balance physical button
      IconButton(
        onClick = onConnectToggle,
        modifier = Modifier
          .size(132.dp)
          .shadow(6.dp, CircleShape, ambientColor = Slate300, spotColor = Slate300)
          .clip(CircleShape)
          .background(Color.White)
          .testTag("power_connect_button")
      ) {
        val iconTint by animateColorAsState(
          targetValue = when (vpnState) {
            VpnState.CONNECTED -> Emerald500
            VpnState.CONNECTING -> Amber600
            VpnState.DISCONNECTED -> Slate300
          },
          animationSpec = tween(400)
        )

        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center
        ) {
          Icon(
            imageVector = Icons.Rounded.PowerSettingsNew,
            contentDescription = "Toggle DNS Tunnel Connection",
            tint = iconTint,
            modifier = Modifier.size(54.dp)
          )
        }
      }
    }
  }
}

/**
 * Clean grid arrangement for metrics.
 */
@Composable
fun MetricsGrid(
  latency: Int,
  activeNodes: String,
  packetLoss: Float,
  protocol: String
) {
  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      MetricCard(
        modifier = Modifier.weight(1f),
        icon = Icons.Rounded.Speed,
        iconTint = Indigo500,
        title = "Latency",
        value = if (latency > 0) "${latency}ms" else "0ms",
        tag = "latency_metric"
      )
      MetricCard(
        modifier = Modifier.weight(1f),
        icon = Icons.Rounded.Dns,
        iconTint = Emerald500,
        title = "Active Nodes",
        value = activeNodes,
        tag = "nodes_metric"
      )
    }
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      MetricCard(
        modifier = Modifier.weight(1f),
        icon = Icons.Rounded.Warning,
        iconTint = Rose500,
        title = "Packet Loss",
        value = String.format("%.1f%%", packetLoss),
        tag = "loss_metric"
      )
      MetricCard(
        modifier = Modifier.weight(1f),
        icon = Icons.Rounded.Share,
        iconTint = Blue500,
        title = "Protocol",
        value = protocol,
        tag = "protocol_metric"
      )
    }
  }
}

@Composable
fun MetricCard(
  modifier: Modifier = Modifier,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  iconTint: Color,
  title: String,
  value: String,
  tag: String
) {
  Card(
    modifier = modifier
      .border(1.dp, Slate100, RoundedCornerShape(18.dp))
      .testTag(tag),
    colors = CardDefaults.cardColors(containerColor = Color.White),
    shape = RoundedCornerShape(18.dp)
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalArrangement = Arrangement.SpaceBetween
    ) {
      Icon(
        imageVector = icon,
        contentDescription = title,
        tint = iconTint,
        modifier = Modifier.size(22.dp)
      )
      Spacer(modifier = Modifier.height(18.dp))
      Column {
        Text(
          text = title.uppercase(),
          style = MaterialTheme.typography.labelSmall,
          fontSize = 9.sp,
          fontWeight = FontWeight.Bold,
          color = Slate400,
          letterSpacing = 1.2.sp
        )
        Text(
          text = value,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          color = Slate800,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
    }
  }
}

/**
 * LOGS TAB CONTENT: DNS Tunnel log terminal.
 */
@Composable
fun LogsTabContent(
  logsList: List<String>,
  vpnState: VpnState,
  onClearClick: () -> Unit
) {
  val terminalListState = rememberLazyListState()

  // Auto-scroll logs as new entries append
  LaunchedEffect(logsList.size) {
    if (logsList.isNotEmpty()) {
      terminalListState.animateScrollToItem(logsList.size - 1)
    }
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(24.dp)
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(bottom = 12.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column {
        Text(
          text = "DNS TUNNEL TRACE LOGS",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
          color = Slate800
        )
        Text(
          text = "Local routing daemon output updates in real-time.",
          style = MaterialTheme.typography.bodySmall,
          color = Slate400
        )
      }
      
      TextButton(
        onClick = onClearClick,
        colors = ButtonDefaults.textButtonColors(contentColor = Indigo600)
      ) {
        Text("Clear Terminal", fontWeight = FontWeight.Bold, fontSize = 12.sp)
      }
    }

    // Terminal Container
    Box(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .clip(RoundedCornerShape(16.dp))
        .background(Slate900)
        .border(1.dp, Slate800, RoundedCornerShape(16.dp))
        .padding(16.dp)
    ) {
      LazyColumn(
        state = terminalListState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        items(logsList) { log ->
          Text(
            text = log,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = when {
              log.contains("[SUCCESS]") -> Color(0xFF10B981)
              log.contains("[SYS]") -> Color(0xFFF59E0B)
              log.contains("[TUN]") -> Color(0xFF3B82F6)
              else -> Color(0xFFE2E8F0)
            },
            lineHeight = 16.sp
          )
        }
      }

      // Small blinking dot simulation when VPN is active
      if (vpnState == VpnState.CONNECTED) {
        val infiniteTransition = rememberInfiniteTransition(label = "blinky")
        val alphaDot by infiniteTransition.animateFloat(
          initialValue = 0.2f,
          targetValue = 1.0f,
          animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
          ),
          label = "alpha_blink"
        )
        
        Row(
          modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(4.dp)
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          Box(
            modifier = Modifier
              .size(6.dp)
              .scale(alphaDot)
              .clip(CircleShape)
              .background(Emerald500)
          )
          Text(
            text = "LIVE",
            fontFamily = FontFamily.Monospace,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            color = Emerald500
          )
        }
      }
    }
  }
}

/**
 * RESILIENCE TAB CONTENT: Server choosing & settings.
 */
@Composable
fun ResilienceTabContent(
  nodes: List<DnsNode>,
  selectedIndex: Int,
  onNodeSelected: (Int) -> Unit,
  multipathStability: Boolean,
  onMultipathToggle: (Boolean) -> Unit,
  bypassLocal: Boolean,
  onBypassToggle: (Boolean) -> Unit,
  protocol: String,
  onProtocolSelected: (String) -> Unit
) {
  var showProtocolDialog by remember { mutableStateOf(false) }

  if (showProtocolDialog) {
    AlertDialog(
      onDismissRequest = { showProtocolDialog = false },
      title = { Text("Select Tunnel Protocol", fontWeight = FontWeight.Bold) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Spacer(modifier = Modifier.height(8.dp))
          listOf("LGN-v2 (Optimized)", "DNS-Over-HTTPS", "DNS-Over-TLS", "Legacy UDP Tunnel").forEach { prot ->
            val simpleVal = if (prot.contains("LGN")) "LGN-v2" else if (prot.contains("HTTPS")) "DoH" else if (prot.contains("TLS")) "DoT" else "UDP"
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                  onProtocolSelected(simpleVal)
                  showProtocolDialog = false
                }
                .padding(14.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(text = prot, color = Slate800, fontWeight = FontWeight.Medium)
              RadioButton(
                selected = (protocol == simpleVal),
                onClick = {
                  onProtocolSelected(simpleVal)
                  showProtocolDialog = false
                }
              )
            }
          }
        }
      },
      confirmButton = {
        TextButton(onClick = { showProtocolDialog = false }) {
          Text("Cancel", fontWeight = FontWeight.Bold)
        }
      },
      containerColor = Color.White
    )
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(24.dp)
  ) {
    Text(
      text = "TUNNEL RESILIENCE ENGINE",
      style = MaterialTheme.typography.titleSmall,
      fontWeight = FontWeight.Bold,
      color = Slate800
    )
    Text(
      text = "Select active target DNS server. Recommended endpoints optimize latency.",
      style = MaterialTheme.typography.bodySmall,
      color = Slate400,
      modifier = Modifier.padding(bottom = 16.dp)
    )

    // Scrollable Nodes List
    Text(
      text = "ACTIVE RESOLVING NODES",
      style = MaterialTheme.typography.labelSmall,
      color = Slate500,
      fontWeight = FontWeight.Bold,
      letterSpacing = 1.sp,
      modifier = Modifier.padding(bottom = 8.dp)
    )
    
    LazyColumn(
      modifier = Modifier
        .weight(1.5f)
        .fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      items(nodes.size) { index ->
        val node = nodes[index]
        val isSelected = selectedIndex == index
        
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (isSelected) Indigo50 else Color.White)
            .border(
              width = if (isSelected) 1.5.dp else 1.dp,
              color = if (isSelected) Indigo600 else Slate100,
              shape = RoundedCornerShape(16.dp)
            )
            .clickable { onNodeSelected(index) }
            .padding(14.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
          ) {
            Box(
              modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (isSelected) Indigo100 else Slate100),
              contentAlignment = Alignment.Center
            ) {
              Icon(
                imageVector = Icons.Rounded.Dns,
                contentDescription = null,
                tint = if (isSelected) Indigo600 else Slate400,
                modifier = Modifier.size(18.dp)
              )
            }

            Column {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                  text = node.name,
                  fontWeight = FontWeight.Bold,
                  fontSize = 13.sp,
                  color = Slate800
                )
                if (node.isRecommended) {
                  Box(
                    modifier = Modifier
                      .background(Emerald50, RoundedCornerShape(8.dp))
                      .padding(horizontal = 6.dp, vertical = 2.dp)
                  ) {
                    Text("FASTEST", color = Emerald500, fontSize = 7.sp, fontWeight = FontWeight.Bold)
                  }
                }
              }
              Text(
                text = "Proxy IP: ${node.ip}",
                fontSize = 11.sp,
                color = Slate400
              )
            }
          }

          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
          ) {
            Column(horizontalAlignment = Alignment.End) {
              Text(
                text = "${node.ping}ms",
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = if (node.ping < 50) Emerald500 else Slate700
              )
              Text(
                text = "Load: ${node.load}%",
                fontSize = 10.sp,
                color = Slate400
              )
            }
            RadioButton(
              selected = isSelected,
              onClick = { onNodeSelected(index) }
            )
          }
        }
      }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Settings Toggle list
    Text(
      text = "TUNNEL CONFIGURATION",
      style = MaterialTheme.typography.labelSmall,
      color = Slate500,
      fontWeight = FontWeight.Bold,
      letterSpacing = 1.sp,
      modifier = Modifier.padding(bottom = 8.dp)
    )

    Column(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .clip(RoundedCornerShape(16.dp))
        .background(Color.White)
        .border(1.dp, Slate100, RoundedCornerShape(16.dp))
        .padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      // Switch 1: Multipath
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text("Multipath Stability Engine", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Slate800)
          Text("Failsafe backup routing when connections slip.", fontSize = 10.sp, color = Slate400)
        }
        Switch(
          checked = multipathStability,
          onCheckedChange = onMultipathToggle,
          colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Indigo600)
        )
      }

      Divider(color = Slate100)

      // Switch 2: Bypass Local
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text("Bypass Local Traffic", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Slate800)
          Text("Do not proxy requests to localized devices.", fontSize = 10.sp, color = Slate400)
        }
        Switch(
          checked = bypassLocal,
          onCheckedChange = onBypassToggle,
          colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Indigo600)
        )
      }

      Divider(color = Slate100)

      // Protocol Option Click
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clickable { showProtocolDialog = true }
          .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column {
          Text("Current Protocol Mode", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Slate800)
          Text("LGN-v2 offers full geometrical multipath framing.", fontSize = 10.sp, color = Slate400)
        }
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          Text(
            text = protocol,
            fontWeight = FontWeight.Bold,
            color = Indigo600,
            fontSize = 13.sp
          )
          Icon(
            imageVector = Icons.Default.ArrowForward,
            contentDescription = "Select Protocol",
            tint = Slate400,
            modifier = Modifier.size(16.dp)
          )
        }
      }
    }
  }
}

/**
 * ACCOUNT TAB CONTENT: Dynamic Go-based DNS tunnel parameters and config TOML code generator.
 */
@Composable
fun AccountTabContent(
  vpnState: VpnState,
  dnsNodes: List<DnsNode>,
  selectedNodeIndex: Int
) {
  var isClientConfig by remember { mutableStateOf(true) }
  var listenPort by remember { mutableStateOf("1080") }
  var targetSuffix by remember { mutableStateOf("tunnel.legion.net") }
  var isCopiedNotification by remember { mutableStateOf(false) }

  // Autogenerated Go client-server TOML dynamic payload
  val clientTomlText = """
# Legion DNS Tunnel - Client Configuration
# Autogenerated SOCKS5 routing parameters

[socks5]
listen_addr = "127.0.0.1:$listenPort"
timeout_seconds = 30

[protocol]
mode = "txt"
max_payload_bytes = 110
session_timeout_ms = 5000
sliding_window_size = 16
retransmit_interval_ms = 400

[tunnel]
target_domain = "$targetSuffix"
encryption_key = "ecdhe-curve25519-aes-gcm-sha256-token"

[[resolvers]]
name = "Google Primary"
address = "8.8.8.8:53"
weight = 5
health_check_interval_seconds = 10

[[resolvers]]
name = "Cloudflare Secure"
address = "1.1.1.1:53"
weight = 5
health_check_interval_seconds = 10
""".trimIndent()

  val serverTomlText = """
# Legion DNS Tunnel - Server Configuration
# Autogenerated authoritative bridging configuration

[dns]
listen_addr = "0.0.0.0:53"
target_domain = "$targetSuffix"

[socks5]
socks5_bridge_addr = "127.0.0.1:$listenPort"

[server]
encryption_key = "ecdhe-curve25519-aes-gcm-sha256-token"
max_connections = 1000
idle_timeout_seconds = 120
enable_multipath_aggregation = true

[logging]
level = "info"
log_traffic_statistics = true
stat_interval_seconds = 60
""".trimIndent()

  // Clear copied feedback automatically
  LaunchedEffect(isCopiedNotification) {
    if (isCopiedNotification) {
      delay(2000)
      isCopiedNotification = false
    }
  }

  LazyColumn(
    modifier = Modifier
      .fillMaxSize()
      .padding(24.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    item {
      Text(
        text = "GO TUNNEL DEPLOYMENT HUB",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = Slate800
      )
      Text(
        text = "Tune proxy ports or DNS zones to generate matching TOML script configurations.",
        style = MaterialTheme.typography.bodySmall,
        color = Slate400,
        modifier = Modifier.padding(bottom = 8.dp)
      )
    }

    // Parameters Customizer Card
    item {
      Card(
        modifier = Modifier
          .fillMaxWidth()
          .border(1.dp, Slate200, RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(20.dp)
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
          Text(
            text = "TUNNEL CONFIGURATION TUNING",
            style = MaterialTheme.typography.labelSmall,
            color = Slate500,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
          )

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
          ) {
            // SOCKS Port input field
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "Proxy Port",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Slate700
              )
              Spacer(modifier = Modifier.height(4.dp))
              OutlinedTextField(
                value = listenPort,
                onValueChange = { listenPort = it.filter { char -> char.isDigit() }.take(5) },
                singleLine = true,
                placeholder = { Text("1080") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                  fontFamily = FontFamily.Monospace,
                  fontWeight = FontWeight.Bold,
                  color = Indigo600
                ),
                colors = OutlinedTextFieldDefaults.colors(
                  focusedBorderColor = Indigo600,
                  unfocusedBorderColor = Slate200
                ),
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("socks_port_input")
              )
            }

            // DNS Domain Zone
            Column(modifier = Modifier.weight(1.5f)) {
              Text(
                text = "Authoritative Domain Zone",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Slate700
              )
              Spacer(modifier = Modifier.height(4.dp))
              OutlinedTextField(
                value = targetSuffix,
                onValueChange = { targetSuffix = it },
                singleLine = true,
                placeholder = { Text("tunnel.legion.net") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                  fontFamily = FontFamily.Monospace,
                  color = Slate800
                ),
                colors = OutlinedTextFieldDefaults.colors(
                  focusedBorderColor = Indigo600,
                  unfocusedBorderColor = Slate200
                ),
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("domain_zone_input")
              )
            }
          }
        }
      }
    }

    // Symmetrical Config Preview Block
    item {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .background(Slate100)
          .padding(4.dp)
      ) {
        Button(
          onClick = { isClientConfig = true },
          colors = ButtonDefaults.buttonColors(
            containerColor = if (isClientConfig) Color.White else Color.Transparent,
            contentColor = if (isClientConfig) Indigo600 else Slate500
          ),
          elevation = ButtonDefaults.buttonColors(),
          shape = RoundedCornerShape(8.dp),
          modifier = Modifier
            .weight(1f)
            .testTag("client_toml_toggle")
        ) {
          Text("Client TOML", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }

        Button(
          onClick = { isClientConfig = false },
          colors = ButtonDefaults.buttonColors(
            containerColor = if (!isClientConfig) Color.White else Color.Transparent,
            contentColor = if (!isClientConfig) Indigo600 else Slate500
          ),
          elevation = ButtonDefaults.buttonColors(),
          shape = RoundedCornerShape(8.dp),
          modifier = Modifier
            .weight(1f)
            .testTag("server_toml_toggle")
        ) {
          Text("Server TOML", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
      }
    }

    // Code Container Box with Syntax Highlighter Accent
    item {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(16.dp))
          .background(Slate900)
          .border(1.dp, Slate800, RoundedCornerShape(16.dp))
          .padding(16.dp)
      ) {
        Column {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
              Box(
                modifier = Modifier
                  .size(8.dp)
                  .clip(CircleShape)
                  .background(if (isClientConfig) Indigo500 else Emerald500)
              )
              Text(
                text = if (isClientConfig) "config-client.toml" else "config-server.toml",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = Slate300,
                fontWeight = FontWeight.Bold
              )
            }

            // Copy Trigger
            IconButton(
              onClick = { isCopiedNotification = true },
              modifier = Modifier
                .size(32.dp)
                .background(Slate800, CircleShape)
                .testTag("copy_config_button")
            ) {
              Icon(
                imageVector = if (isCopiedNotification) Icons.Default.Check else Icons.Default.ContentCopy,
                contentDescription = "Copy code",
                tint = if (isCopiedNotification) Emerald500 else Slate3sp,
                modifier = Modifier.size(14.dp)
              )
            }
          }

          // Monospaced syntax viewer
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .height(200.dp)
              .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
              .padding(12.dp)
          ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
              val linesList = if (isClientConfig) clientTomlText.lines() else serverTomlText.lines()
              items(linesList) { line ->
                Text(
                  text = line,
                  fontFamily = FontFamily.Monospace,
                  fontSize = 11.sp,
                  color = when {
                    line.startsWith("#") -> Slate500
                    line.contains("[") -> Teal40
                    line.contains("=") -> Indigo200
                    else -> Color.White
                  },
                  lineHeight = 16.sp
                )
              }
            }
          }

          // Copied floating banner notification
          AnimatedVisibility(visible = isCopiedNotification) {
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Emerald50)
                .border(1.dp, Emerald500.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                .padding(8.dp),
              contentAlignment = Alignment.Center
            ) {
              Text(
                text = "Markup successfully copied to local clipboard!",
                color = Emerald500,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
              )
            }
          }
        }
      }
    }

    // Go-execution deployment command tips card
    item {
      Card(
        modifier = Modifier
          .fillMaxWidth()
          .border(1.dp, Slate100, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = Slate50),
        shape = RoundedCornerShape(16.dp)
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Icon(
              imageVector = Icons.Rounded.Terminal,
              contentDescription = null,
              tint = Indigo600,
              modifier = Modifier.size(18.dp)
            )
            Text(
              text = "BUILD & LAUNCH CHEATSHEET",
              style = MaterialTheme.typography.labelSmall,
              fontWeight = FontWeight.Bold,
              color = Slate800,
              letterSpacing = 0.5.sp
            )
          }

          Text(
            text = "Navigate to " + if (isClientConfig) "go-dns-tunnel/client" else "go-dns-tunnel/server" + " folder and execute command lines:",
            fontSize = 10.sp,
            color = Slate500
          )

          Box(
            modifier = Modifier
              .fillMaxWidth()
              .background(Slate900, RoundedCornerShape(8.dp))
              .padding(10.dp)
          ) {
            Text(
              text = if (isClientConfig) {
                "go build -o dns-client\n./dns-client -socks \"127.0.0.1:$listenPort\" -domain \"$targetSuffix\""
              } else {
                "go build -o dns-server\nsudo ./dns-server -listen \"0.0.0.0:53\" -domain \"$targetSuffix\""
              },
              fontFamily = FontFamily.Monospace,
              fontSize = 10.sp,
              color = Emerald500,
              lineHeight = 14.sp
            )
          }
        }
      }
    }
  }
}


/**
 * Beautifully balanced custom bottom navigation bar as required by design criteria.
 */
@Composable
fun LegionBottomNavigation(
  currentTab: AppTab,
  onTabSelected: (AppTab) -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .navigationBarsPadding() // Auto-reserves space for gesture pills as requested in Safe Areas constraints
      .background(Color.White)
      .border(1.dp, Slate100)
      .padding(vertical = 12.dp, horizontal = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceAround
  ) {
    AppTab.values().forEach { tab ->
      val isSelected = currentTab == tab
      val tintColor by animateColorAsState(
        targetValue = if (isSelected) Indigo600 else Slate400,
        animationSpec = tween(300)
      )

      Column(
        modifier = Modifier
          .clip(RoundedCornerShape(12.dp))
          .clickable(
            onClick = { onTabSelected(tab) },
            interactionSource = remember { MutableInteractionSource() },
            indication = rememberRipple(bounded = true, color = Indigo600.copy(alpha = 0.12f))
          )
          .padding(vertical = 8.dp, horizontal = 12.dp)
          .testTag("nav_tab_${tab.name.lowercase()}"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
      ) {
        Icon(
          imageVector = when (tab) {
            AppTab.RELAY -> Icons.Rounded.Dashboard
            AppTab.LOGS -> Icons.Rounded.Terminal
            AppTab.RESILIENCE -> Icons.Rounded.CloudSync
            AppTab.ACCOUNT -> Icons.Rounded.PersonPin
          },
          contentDescription = tab.name,
          tint = tintColor,
          modifier = Modifier.size(24.dp)
        )
        Text(
          text = tab.name.capitalize(),
          style = MaterialTheme.typography.labelSmall,
          fontWeight = FontWeight.Bold,
          color = tintColor,
          letterSpacing = 0.5.sp,
          fontSize = 10.sp
        )
      }
    }
  }
}
