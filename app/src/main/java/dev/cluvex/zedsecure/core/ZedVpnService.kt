package dev.cluvex.zedsecure.core

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.Looper
import android.os.ParcelFileDescriptor
import dev.cluvex.zedsecure.core.AppLog as Log
import dev.amirzr.flutter_v2ray_client.v2ray.core.HevTunCore
import dev.cluvex.zedsecure.R
import dev.cluvex.zedsecure.data.settings.SettingsRepository
import dev.cluvex.zedsecure.domain.config.LocalPorts
import dev.cluvex.zedsecure.domain.config.AetherPeer
import dev.cluvex.zedsecure.domain.config.RoutingMigration
import dev.cluvex.zedsecure.domain.model.AppSettings
import dev.cluvex.zedsecure.domain.model.RulesetItem
import dev.cluvex.zedsecure.domain.model.VpnBypassLan
import dev.cluvex.zedsecure.domain.model.VpnInterfaceAddress
import dev.cluvex.zedsecure.domain.config.XrayJsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

class ZedVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tun: ParcelFileDescriptor? = null
    private var statsJob: Job? = null
    private var remark: String = ""
    private val notifications by lazy { VpnNotifications(this) }

    private var stage: VpnNotifications.Stage = VpnNotifications.Stage.Preparing

    private var connectWhen: Long = 0L

    private var showSpeed: Boolean = true
    private var livePromotion: Boolean = true
    private var notifChip: dev.cluvex.zedsecure.domain.model.NotifChip =
        dev.cluvex.zedsecure.domain.model.NotifChip.Speed
    private var psiphon: PsiphonController? = null
    private var dnsTunnel: DnsTunnelController? = null
    private var masterDns: MasterDnsController? = null
    private var tor: dev.cluvex.zedsecure.core.tor.TorController? = null
    private var ssh: SshController? = null
    private var sniSpoof: SniSpoofController? = null

    @Volatile
    private var sniSpoofEdge: String? = null
    private var openConnect: OpenConnectController? = null
    private var singBox: SingBoxEngine? = null
    private var aetherProcess: Process? = null
    private var aetherMonitorJob: Job? = null
    private var aetherGeneration = 0
    private var aetherArgs: List<String> = emptyList()
    private var aetherWorkDir: java.io.File? = null
    /** Profile the running session was built from: the monitor rebuilds
     * connect args from it (with the h2/h3 flip) after an unexpected
     * death. Cleared by stopAether. */
    private var aetherProfile: dev.cluvex.zedsecure.domain.config.AetherProfile? = null
    /** H2/H3 flip state for the running session: null = as stored,
     * true = force HTTP/2, false = force HTTP/3. Only set by the
     * monitor after a real drop, and only for MASQUE-family profiles. */
    private var aetherFlipH2: Boolean? = null
    /** Set by the network-change handler just before it stops the
     * process, so the monitor knows the exit was expected and
     * relaunches without a scan and without a flip. Expected exits
     * neither consume nor refill the restart budget: only a relaunch
     * that verifiably carries traffic resets it. Always cleared by the
     * monitor on read. */
    @Volatile
    private var aetherExpectExit = false
    /** Default-network callback for instant reconnect on a network
     * switch (Wi-Fi <-> mobile). Registered by the monitor, removed by
     * stopAether. */
    private var aetherNetworkCallback: ConnectivityManager.NetworkCallback? = null
    /** Last network-change restart, for the 15s anti-flap interval. */
    private var aetherLastNetworkRestartMs = 0L
    /** Default network seen when the callback was registered: the first
     * onAvailable for it is the registration echo, not a switch, and is
     * ignored. Cleared when that network is lost, so the replacement's
     * onAvailable counts as a switch. */
    @Volatile
    private var aetherBaselineNetwork: Network? = null
    /** True once the first callback after registration has been seen. */
    @Volatile
    private var aetherBaselineSet = false

    /** Background-optimizer job for ladder-connected Aether sessions:
     *  probes other ladder methods and, rarely, swaps the core behind
     *  the live TUN. Cancelled by stopAether and by the network-change
     *  reconnect (that path owns the session from then on). */
    private var aetherOptimizerJob: Job? = null

    /** True while the optimizer is swapping the core behind the live
     *  TUN. The monitor treats the retired process's exit as expected
     *  while this is set, instead of spending restart budget on it. */
    @Volatile
    private var aetherSwapPending = false

    /** True once the optimizer has switched this session. At most one
     *  switch per session, ever. */
    private var aetherOptimizerSwitched = false

    /** True when the running session connected without a usable pinned
     *  peer (the pinned value failed validation and no fixed peer
     *  replaced it), so the connect fell back to --quick-reconnect
     *  alone. finishConnected turns this into the one-line fallback
     *  notice; cleared by stopAether. */
    private var aetherPeerFallback = false

    private var singBoxLastUp = 0L
    private var singBoxLastDown = 0L
    private var socksShim: SocksTunBridge? = null
    private var kind: String = VpnManager.KIND_XRAY

    private var outerPsiphon: PsiphonController? = null
    private var outerTor: dev.cluvex.zedsecure.core.tor.TorController? = null
    private var outerSsh: SshController? = null
    private var outerDnsTunnel: DnsTunnelController? = null
    private var crossOuterKind: String? = null
    private var crossInnerKind: String? = null

    @Volatile private var userStop = false
    private var killSwitchEnabled = false

    private var killSwitchActive = false

    // True only while this service is foregrounded for a live session
    // (set by startInForeground, cleared by stopEverything). notify()
    // refuses to post once it is false, so no job or racing callback can
    // resurrect the notification after the final stop.
    @Volatile private var notificationLive = false

    @Volatile private var reconnecting = false
    private var geoReloadJob: Job? = null
    private var holdWatch: android.net.ConnectivityManager.NetworkCallback? = null
    private val holdNetworks = java.util.Collections.synchronizedSet(HashSet<android.net.Network>())
    private var retryJob: Job? = null
    private var retryDelayMs = RETRY_FIRST_MS

    private var connected = false

    private var proxyOnly = false

    private var autoSelectSession = false
    private var networkWatch: android.net.ConnectivityManager.NetworkCallback? = null
    private var networkTypeWatch: android.net.ConnectivityManager.NetworkCallback? = null
    private var networkTypeJob: Job? = null
    @Volatile private var builtNetworkType: String? = null
    @Volatile private var swappingCore = false

    private var hevIpv4: String = VpnInterfaceAddress.Option2.ipv4Client
    private var hevIpv6: String? = null
    private var hevLogLevel: String = HevTunCore.DEFAULT_LOG_LEVEL
    private var hevRwTimeout: String = HevTunCore.DEFAULT_RW_TIMEOUT

    private var useZepTun: Boolean = true

    override fun attachBaseContext(base: Context) {
        val tag = SettingsRepository.readLanguageTag(base)
        super.attachBaseContext(dev.cluvex.zedsecure.core.platform.LocaleManager.wrap(base, tag))
    }

    override fun onCreate() {
        super.onCreate()
        notifications.createChannel()

        XrayController.warmUp(this)
        XrayController.onCoreShutdown = { onTunnelFailed(null) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = intent?.getStringExtra(VpnManager.EXTRA_COMMAND)

        // Enter the foreground only for intents that will actually run a
        // session (or restore one). Anything else falls through to
        // stopEverything() below, which removes the notification; posting
        // first would flash (and, if teardown stalled, strand) a
        // notification for a session that never runs — e.g. a sticky
        // restart with a null intent on a non-Always-on install.
        val willRunSession = command == VpnManager.CMD_START ||
            command == VpnManager.CMD_START_ACTIVE ||
            intent?.action == VpnService.SERVICE_INTERFACE ||
            (intent == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isAlwaysOn)
        if (willRunSession) {
            val wentForeground = runCatching {
                startInForeground(
                    if (connected) connectedNotification(0, 0, 0, 0) else connectingNotification(),
                )
            }.isSuccess
            if (!wentForeground) {
                Log.w(TAG, "could not enter the foreground; standing down")
                VpnManager.onError(getString(R.string.engine_missing_config))
                stopSelf()
                return START_NOT_STICKY
            }
        }
        when {
            command == VpnManager.CMD_START -> {
                val config = intent.getStringExtra(VpnManager.EXTRA_CONFIG)
                    ?: intent.getStringExtra(VpnManager.EXTRA_CONFIG_PATH)?.let { path ->

                        runCatching { java.io.File(path).let { f -> f.readText().also { f.delete() } } }
                            .getOrNull()
                    }
                remark = intent.getStringExtra(VpnManager.EXTRA_REMARK).orEmpty()
                val socksPort = intent.getIntExtra(VpnManager.EXTRA_SOCKS_PORT, LocalPorts.XRAY_SOCKS)
                kind = intent.getStringExtra(VpnManager.EXTRA_KIND) ?: VpnManager.KIND_XRAY
                proxyOnly = intent.getBooleanExtra(VpnManager.EXTRA_PROXY_ONLY, false)
                if (config.isNullOrBlank()) {
                    VpnManager.onError(getString(R.string.engine_missing_config))
                    notificationLive = false
                    stopForeground(Service.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }

                if (Ikev2Controller.isActive) Ikev2Controller.stop(this)

                resetSessionState()
                stage = VpnNotifications.Stage.Preparing
                startInForeground(connectingNotification())
                scope.launch { startTunnel(config, socksPort, kind) }
            }
            command == VpnManager.CMD_STOP -> { userStop = true; stopEverything() }

            command == VpnManager.CMD_START_ACTIVE -> startActiveProfile(allowProxyOnly = true)

            intent?.action == VpnService.SERVICE_INTERFACE -> startActiveProfile(allowProxyOnly = false)

            intent == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isAlwaysOn ->
                startActiveProfile(allowProxyOnly = false)
            else -> stopEverything()
        }
        return START_STICKY
    }

    private fun startActiveProfile(allowProxyOnly: Boolean) {
        val state = VpnManager.status.value.state
        if (state.isActive || state.isTransitioning) {
            startInForeground(if (connected) connectedNotification(0, 0, 0, 0) else connectingNotification())
            return
        }
        if (Ikev2Controller.isActive) Ikev2Controller.stop(this)
        resetSessionState()
        val container = (application as dev.cluvex.zedsecure.ZedSecureApp).container
        remark = container.configRepository.activeProfile()?.name.orEmpty()
        kind = VpnManager.KIND_XRAY
        proxyOnly = false
        stage = VpnNotifications.Stage.Preparing

        startInForeground(connectingNotification())
        scope.launch {
            when (val plan = StartPlanner(this@ZedVpnService).planActive(allowProxyOnly)) {
                is StartPlanner.Plan.Start -> {
                    VpnManager.onStarting(plan.remark)
                    remark = plan.remark
                    kind = plan.kind
                    proxyOnly = plan.proxyOnly
                    startTunnel(plan.configJson, plan.socksPort, plan.kind)
                }
                is StartPlanner.Plan.Ikev2 -> {
                    VpnManager.onError(getString(R.string.engine_ikev2_needs_app))
                    stopEverything()
                }
                is StartPlanner.Plan.Foxy -> {
                    stopEverything()
                    VpnManager.onStarting(plan.remark)
                    dev.cluvex.zedsecure.foxy.vpn.FoxyVpnService.start(this@ZedVpnService, plan.remark)
                }
                is StartPlanner.Plan.Sstp -> {
                    stopEverything()
                    VpnManager.onStarting(plan.remark)
                    dev.cluvex.zedsecure.core.SstpBridge.start(this@ZedVpnService, plan.profile)
                    dev.cluvex.zedsecure.core.SstpBridge.watch(
                        kotlinx.coroutines.MainScope(),
                        this@ZedVpnService,
                        plan.remark,
                    )
                }
                is StartPlanner.Plan.Failure -> {
                    VpnManager.onError(plan.message)
                    stopEverything()
                }
            }
        }
    }

    private fun startTunnel(configJson: String, socksPort: Int, kind: String) {
        val settings = SettingsRepository(this).settings.value
        killSwitchEnabled = settings.killSwitch
        userStop = false
        killSwitchActive = false
        connected = false
        showSpeed = settings.speedInNotification
        livePromotion = settings.liveNotification
        notifChip = settings.notifChip
        val iface = settings.vpnInterfaceAddress
        hevIpv4 = iface.ipv4Client
        hevIpv6 = if (settings.enableIpv6) iface.ipv6Client else null
        useZepTun = settings.useZepTun
        hevLogLevel = settings.hevTunLogLevel.value
        hevRwTimeout = settings.hevTunRwTimeout

        if (proxyOnly) {
            if (kind == VpnManager.KIND_XRAY) { startProxyOnly(configJson, socksPort); return }
            proxyOnly = false
        }

        if (kind == VpnManager.KIND_OPENCONNECT) { startOpenConnect(configJson, settings); return }

        if (kind == VpnManager.KIND_SINGBOX) { startSingBox(configJson, settings); return }

        val dnsInChain = kind == VpnManager.KIND_DNS_TUNNEL || kind == VpnManager.KIND_MASTERDNS ||
            (kind == VpnManager.KIND_CROSS_CHAIN && crossChainHasDnsTunnel(configJson))
        val tunMtu = if (dnsInChain) 1280
        else settings.vpnMtu.coerceIn(1000, 9000)

        val bypassLan = shouldBypassLan(settings, configJson, kind)

        val spoofBypassIp =
            if (kind == VpnManager.KIND_SNISPOOF) sniSpoofEdgeIp(configJson) else null

        sniSpoofEdge = spoofBypassIp
        val descriptor = try {
            Builder().apply {
                setSession(remark.ifBlank { "Rdx VPN" })
                setMtu(tunMtu)

                addAddress(iface.ipv4Router, 30)
                if (settings.enableIpv6) addAddress(iface.ipv6Router, 126)
                addLanAwareRoutes(bypassLan, settings.enableIpv6, excludeIp = spoofBypassIp)
                applyAppRules(settings)

                if (kind == VpnManager.KIND_XRAY) {
                    val vpnDns = if (settings.localDnsEnabled) emptyList()
                    else vpnDnsServers(settings.vpnDns, settings.enableIpv6)
                    if (vpnDns.isNotEmpty()) vpnDns.forEach { runCatching { addDnsServer(it) } }
                    else addDnsServersFrom(configJson)
                } else {
                    val primary = if (settings.remoteDnsMode == "custom") {
                        settings.remoteDnsPrimary.ifBlank { "8.8.8.8" }
                    } else "8.8.8.8"
                    val fallback = if (settings.remoteDnsMode == "custom") {
                        settings.remoteDnsFallback.ifBlank { "1.1.1.1" }
                    } else "1.1.1.1"
                    runCatching { addDnsServer(primary) }
                    runCatching { addDnsServer(fallback) }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setMetered(false)
            }.establish()
        } catch (e: Exception) {
            Log.e(TAG, "establish failed", e)
            null
        }
        if (descriptor == null) {
            VpnManager.onError(getString(R.string.engine_no_interface))
            stopEverything()
            return
        }
        val held = tun
        tun = descriptor
        if (reconnecting && held != null && held !== descriptor) runCatching { held.close() }
        advance(VpnNotifications.Stage.Interface)

        VpnManager.setActiveKind(kind)

        when (kind) {
            VpnManager.KIND_PSIPHON -> startPsiphon(configJson, descriptor, tunMtu)
            VpnManager.KIND_DNS_TUNNEL -> startDnsTunnel(configJson, descriptor, tunMtu, settings)
            VpnManager.KIND_MASTERDNS -> startMasterDns(configJson, descriptor, tunMtu, settings)
            VpnManager.KIND_TOR -> startTor(settings, descriptor, tunMtu)
            VpnManager.KIND_SSH -> startSsh(configJson, descriptor, tunMtu, settings)
            VpnManager.KIND_SNISPOOF -> startSniSpoof(configJson, socksPort, descriptor, tunMtu)
            VpnManager.KIND_AETHER -> startAether(configJson, socksPort, descriptor, tunMtu)
            VpnManager.KIND_CROSS_CHAIN -> startCrossChain(configJson, descriptor, tunMtu, settings)
            else -> startXray(configJson, socksPort, descriptor, tunMtu)
        }
    }

    private fun startSniSpoof(configJson: String, socksPort: Int, descriptor: ParcelFileDescriptor, tunMtu: Int) {
        scope.launch {
            try {
                val wrapper = JSONObject(configJson)
                val xray = wrapper.getJSONObject("xray")
                val sni = wrapper.getJSONObject("sni")
                val fakeSni = sni.optString("fakeSni")
                val cleanIp = sni.optString("cleanIp")

                if (!SniSpoofController.rootAvailable()) {
                    VpnManager.onError(getString(R.string.snispoof_needs_root))
                    stopEverything(); return@launch
                }

                val orig = readProxyOutbound(xray)
                if (orig == null) {
                    VpnManager.onError(getString(R.string.engine_snispoof_no_outbound))
                    stopEverything(); return@launch
                }
                val (realHost, realPort) = orig

                val connectIp = sniSpoofEdge ?: cleanIp.ifBlank {
                    runCatching { java.net.InetAddress.getByName(realHost).hostAddress }.getOrNull() ?: realHost
                }
                val listenPort = SNISPOOF_PORT
                val controller = SniSpoofController(this@ZedVpnService, "$connectIp:$realPort", fakeSni, listenPort)
                sniSpoof = controller
                if (!controller.start()) {
                    VpnManager.onError(getString(R.string.snispoof_needs_root))
                    stopEverything(); return@launch
                }
                writeProxyOutbound(xray, "127.0.0.1", listenPort)

                if (!XrayController.start(xray.toString())) {
                    VpnManager.onError(coreStartError())
                    stopEverything(); return@launch
                }

                if (!bridge(descriptor, socksPort, tunMtu, pipeline = true)) return@launch

                if (!TunnelReadiness.verify(socksPort, timeoutMs = 7_000, attempts = 4)) {
                    val reason = if ((sniSpoof?.ackTimeouts ?: 0) > 0) {
                        R.string.snispoof_no_ack
                    } else {
                        R.string.snispoof_no_traffic
                    }
                    VpnManager.onError(getString(reason))
                    stopEverything(); return@launch
                }
                VpnManager.activeSocksPort = socksPort
                finishConnected()
            } catch (e: Exception) {
                Log.e(TAG, "sni-spoof start failed", e)
                VpnManager.onError(engineFailed(R.string.engine_failed_snispoof, e.message))
                stopEverything()
            }
        }
    }

    private fun readProxyOutbound(xray: JSONObject): Pair<String, Int>? {
        val ob = proxyOutbound(xray) ?: return null
        val settings = ob.optJSONObject("settings") ?: return null
        val node = settings.optJSONArray("vnext")?.optJSONObject(0)
            ?: settings.optJSONArray("servers")?.optJSONObject(0) ?: return null
        val host = node.optString("address").ifBlank { return null }
        val port = node.optInt("port", 0).takeIf { it > 0 } ?: return null
        return host to port
    }

    private fun writeProxyOutbound(xray: JSONObject, host: String, port: Int) {
        val ob = proxyOutbound(xray) ?: return
        val settings = ob.optJSONObject("settings") ?: return
        val node = settings.optJSONArray("vnext")?.optJSONObject(0)
            ?: settings.optJSONArray("servers")?.optJSONObject(0) ?: return
        node.put("address", host); node.put("port", port)
    }

    private fun proxyOutbound(xray: JSONObject): JSONObject? {
        val arr = xray.optJSONArray("outbounds") ?: return null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("tag") == "proxy") return o
        }
        return arr.optJSONObject(0)
    }

    private fun startSsh(
        configJson: String,
        descriptor: ParcelFileDescriptor,
        tunMtu: Int,
        appSettings: AppSettings,
    ) {
        val sshProfile = try {
            kotlinx.serialization.json.Json.decodeFromString(
                dev.cluvex.zedsecure.domain.config.SshProfile.serializer(), configJson,
            )
        } catch (e: Exception) {
            Log.e(TAG, "bad SSH config", e)
            VpnManager.onError(getString(R.string.engine_invalid_config_ssh))
            stopEverything(); return
        }
        scope.launch {
            val controller = SshController(
                profile = sshProfile,
                cipher = appSettings.sshCipher,
                compression = appSettings.sshCompression,
                listenPort = LocalPorts.SSH,
                maxListenPort = LocalPorts.SSH_MAX,
            )
            ssh = controller
            val port = controller.start()
            if (port < 0) {
                VpnManager.onError(engineFailed(R.string.engine_failed_ssh, controller.lastError))
                stopEverything(); return@launch
            }
            val dns = if (appSettings.remoteDnsMode == "custom") appSettings.remoteDnsPrimary.ifBlank { "8.8.8.8" } else "8.8.8.8"
            val shim = SocksTunBridge("127.0.0.1", SHIM_PORT, "127.0.0.1", port, dnsHost = dns)
            socksShim = shim
            if (shim.start() && bridge(descriptor, SHIM_PORT, tunMtu, udpOverTcp = true)) {
                finishConnected()
            } else {
                stopEverything()
            }
        }
    }

    private fun startTor(settings: AppSettings, descriptor: ParcelFileDescriptor, tunMtu: Int) {
        val bridged = java.util.concurrent.atomic.AtomicBoolean(false)
        val dns = if (settings.remoteDnsMode == "custom") settings.remoteDnsPrimary.ifBlank { "8.8.8.8" } else "8.8.8.8"
        val controller = dev.cluvex.zedsecure.core.tor.TorController(
            context = this,
            settings = settings,
            onBootstrapped = {
                if (bridged.compareAndSet(false, true)) {
                    val shim = SocksTunBridge(
                        listenHost = "127.0.0.1", listenPort = SHIM_PORT,
                        upstreamHost = "127.0.0.1",
                        upstreamPort = dev.cluvex.zedsecure.core.tor.TorConfigBuilder.SOCKS_PORT,
                        dnsHost = dns,
                    )
                    socksShim = shim
                    if (shim.start() && bridge(descriptor, SHIM_PORT, tunMtu, udpOverTcp = true)) {
                        finishConnected()
                    } else {
                        stopEverything()
                    }
                }
            },
            onProgress = { pct -> LogBus.append("I/Tor bootstrap $pct%") },
            onStopped = { reason ->
                onTunnelFailed(reason)
            },
        )
        tor = controller
        if (!controller.start()) stopEverything() else armTorBootstrapDeadline(bridged)
    }

    private fun startDnsTunnel(
        configJson: String,
        descriptor: ParcelFileDescriptor,
        tunMtu: Int,
        appSettings: AppSettings,
    ) {
        val dnsProfile = try {
            kotlinx.serialization.json.Json.decodeFromString(
                dev.cluvex.zedsecure.domain.config.DnsTunnelProfile.serializer(), configJson,
            )
        } catch (e: Exception) {
            Log.e(TAG, "bad DNS-tunnel config", e)
            VpnManager.onError(getString(R.string.engine_invalid_config_dnstunnel))
            stopEverything(); return
        }
        val pool = if (appSettings.dnsPoolEnabled) {
            appSettings.dnsPoolText.split('\n', ',').map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
        } else emptyList()
        val controller = DnsTunnelController(
            dnsProfile,
            listenPort = LocalPorts.DNS_TUNNEL,
            maxListenPort = LocalPorts.DNS_TUNNEL_MAX,
            pool = pool,
            poolFullVerification = appSettings.dnsPoolFullVerification,
        )
        dnsTunnel = controller
        val port = controller.start()
        if (port < 0) {
            VpnManager.onError(engineFailed(R.string.engine_failed_dnstunnel, controller.lastError))
            stopEverything(); return
        }

        val upstreamPort = if (dnsProfile.sshEnabled && dnsProfile.sshHost.isNotBlank()) {
            val sshProfile = dev.cluvex.zedsecure.domain.config.SshProfile(
                host = dnsProfile.sshHost,
                port = dnsProfile.sshPort,
                username = dnsProfile.sshUsername,
                authType = dnsProfile.sshAuthType,
                password = dnsProfile.sshPassword,
                privateKey = dnsProfile.sshPrivateKey,
                keyPassphrase = dnsProfile.sshKeyPassphrase,
            )
            val sshController = SshController(
                profile = sshProfile,
                cipher = appSettings.sshCipher,
                compression = appSettings.sshCompression,
                listenPort = LocalPorts.SSH_OVER_DNS,
                maxListenPort = LocalPorts.SSH_OVER_DNS_MAX,
                proxySocksHost = "127.0.0.1",
                proxySocksPort = port,
                proxySocksUser = dnsProfile.socksUser,
                proxySocksPass = dnsProfile.socksPass,
            )
            ssh = sshController
            val sshPort = sshController.start()
            if (sshPort < 0) {
                VpnManager.onError(engineFailed(R.string.engine_failed_ssh_over_dns, sshController.lastError))
                stopEverything(); return
            }
            Log.i(TAG, "DNS+SSH: SSH SOCKS on $sshPort (via DNS tunnel $port)")
            sshPort
        } else {
            port
        }

        val dns = if (appSettings.remoteDnsMode == "custom") appSettings.remoteDnsPrimary.ifBlank { "8.8.8.8" } else "8.8.8.8"
        val shim = SocksTunBridge("127.0.0.1", SHIM_PORT, "127.0.0.1", upstreamPort, dnsHost = dns)
        socksShim = shim
        if (!shim.start() || !bridge(descriptor, SHIM_PORT, tunMtu, udpOverTcp = true)) {
            stopEverything(); return
        }

        if (!TunnelReadiness.verify(SHIM_PORT)) {
            VpnManager.onError(getString(R.string.dns_tunnel_unreachable))
            stopEverything(); return
        }
        finishConnected()
    }

    private fun startMasterDns(
        configJson: String,
        descriptor: ParcelFileDescriptor,
        tunMtu: Int,
        appSettings: AppSettings,
    ) {
        val profile = try {
            kotlinx.serialization.json.Json.decodeFromString(
                dev.cluvex.zedsecure.domain.config.MasterDnsProfile.serializer(), configJson,
            )
        } catch (e: Exception) {
            Log.e(TAG, "bad MasterDNS config", e)
            VpnManager.onError(getString(R.string.engine_invalid_config_masterdns))
            stopEverything(); return
        }

        val effective = if (appSettings.dnsGlobalResolverEnabled && appSettings.dnsGlobalResolvers.isNotBlank()) {
            profile.copy(resolvers = appSettings.dnsGlobalResolvers)
        } else profile
        val pool = if (appSettings.dnsPoolEnabled) {
            appSettings.dnsPoolText.split('\n', ',').map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
        } else emptyList()
        val controller = MasterDnsController(
            effective, filesDir,
            listenPort = LocalPorts.MASTER_DNS,
            maxListenPort = LocalPorts.MASTER_DNS_MAX,
            pool = pool,
            poolFullVerification = appSettings.dnsPoolFullVerification,
        )
        masterDns = controller
        val port = controller.start()
        if (port < 0) {
            if (controller.stopped) return
            VpnManager.onError(masterDnsError(controller))
            stopEverything(); return
        }

        if (!controller.awaitReady(MASTERDNS_IDLE_TIMEOUT_MS, MASTERDNS_MAX_WAIT_MS)) {
            if (controller.stopped) return
            VpnManager.onError(masterDnsError(controller))
            stopEverything(); return
        }
        val dns = if (appSettings.remoteDnsMode == "custom") appSettings.remoteDnsPrimary.ifBlank { "8.8.8.8" } else "8.8.8.8"
        val shim = SocksTunBridge("127.0.0.1", SHIM_PORT, "127.0.0.1", port, dnsHost = dns)
        socksShim = shim
        if (shim.start() && bridge(descriptor, SHIM_PORT, tunMtu, udpOverTcp = true)) {
            finishConnected()
        } else {
            stopEverything()
        }
    }

    private fun masterDnsError(c: MasterDnsController): String = when (val f = c.failure) {
        is MasterDnsController.Failure.NoUsableResolver ->
            getString(R.string.masterdns_no_resolver, f.tried)
        is MasterDnsController.Failure.SessionRefused ->
            getString(R.string.masterdns_session_refused, f.usable, f.reason.ifBlank { "—" })
        is MasterDnsController.Failure.Stalled ->
            getString(R.string.masterdns_stalled, f.checked, f.total, f.usable)
        is MasterDnsController.Failure.Other ->
            getString(R.string.masterdns_failed_reason, f.reason.take(200))
        null -> getString(R.string.masterdns_failed)
    }

    private fun startOpenConnect(configJson: String, appSettings: AppSettings) {
        VpnManager.setActiveKind(kind)
        val profile = try {
            kotlinx.serialization.json.Json.decodeFromString(
                dev.cluvex.zedsecure.domain.config.OpenConnectProfile.serializer(), configJson,
            )
        } catch (e: Exception) {
            Log.e(TAG, "bad OpenConnect config", e)
            VpnManager.onError(getString(R.string.engine_invalid_config_openconnect))
            stopEverything(); return
        }
        val controller = OpenConnectController(
            service = this,
            profile = profile,
            filesDir = filesDir,
            onLog = { level, msg -> Log.println(if (level <= 0) Log.ERROR else Log.INFO, TAG, msg) },

            configureBuilder = { b -> b.applyAppRules(appSettings, selfInTunnel = true) },

            fallbackDns = appSettings.vpnDns.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() },
            onUp = { finishConnected() },
            onDown = { reason -> onTunnelFailed(reason) },
        )
        openConnect = controller
        if (!controller.start()) stopEverything()
    }

    private fun startSingBox(configJson: String, appSettings: AppSettings) {
        VpnManager.setActiveKind(kind)
        advance(VpnNotifications.Stage.Engine)
        val engine = SingBoxEngine(
            service = this,
            session = remark,
            settings = appSettings,
            onLog = { LogBus.append(it) },
            onStopped = { reason -> onTunnelFailed(reason) },
        )
        singBox = engine
        singBoxLastUp = 0L
        singBoxLastDown = 0L
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                engine.start(configJson)
            } catch (e: Exception) {
                Log.e(TAG, "sing-box failed to start", e)
                VpnManager.onError(engineFailed(R.string.engine_singbox_failed, e.message))
                stopEverything()
                return@launch
            }
            VpnManager.activeSocksPort = runCatching {
                dev.cluvex.zedsecure.domain.config.SingBoxConfigs.prepareForDevice(configJson).socksPort
            }.getOrNull()
            advance(VpnNotifications.Stage.Tunnel)
            finishConnected()
        }
    }

    private fun engineFailed(res: Int, reason: String?): String =
        reason?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { getString(R.string.engine_failed_with_reason, getString(res), it.take(240)) }
            ?: getString(res)

    private fun coreStartError(): String {
        val raw = XrayController.lastError ?: return getString(R.string.core_start_failed)

        Regex("failed to (?:check|load) code ([^ ]+) from ([^ :]+)").find(raw)?.let { m ->
            return getString(R.string.core_geo_code_missing, m.groupValues[1], m.groupValues[2])
        }

        Regex("failed to open (geoip[^ :]*\\.dat|geosite[^ :]*\\.dat)").find(raw)?.let { m ->
            return getString(R.string.core_geo_file_missing, m.groupValues[1])
        }
        return getString(R.string.core_start_failed_reason, raw.take(240))
    }

    private fun startProxyOnly(configJson: String, socksPort: Int) {
        killSwitchEnabled = false
        VpnManager.setActiveKind(kind)
        advance(VpnNotifications.Stage.Engine)
        if (!XrayController.start(configJson)) {
            VpnManager.onError(coreStartError())
            stopEverything()
            return
        }
        onXrayStarted()
        VpnManager.activeSocksPort = socksPort
        finishConnected()
    }

    private fun vpnDnsServers(value: String, ipv6: Boolean): List<String> =
        value.split(',', ' ', '\n', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() && XrayJsonBuilder.isIpLiteral(it) }
            .filter { ipv6 || !it.contains(':') }
            .distinct()

    private fun startXray(configJson: String, socksPort: Int, descriptor: ParcelFileDescriptor, tunMtu: Int) {
        if (!XrayController.start(configJson)) {
            VpnManager.onError(coreStartError())
            stopEverything()
            return
        }
        onXrayStarted()

        if (!bridge(descriptor, socksPort, tunMtu, pipeline = true)) return
        VpnManager.activeSocksPort = socksPort
        finishConnected()
    }

    private fun onXrayStarted() {
        autoSelectSession = AutoSelect.activate()
        if (!autoSelectSession) return
        LogBus.append("I/Auto-select: started over ${AutoSelect.session.value?.memberProfiles?.size ?: 0} servers")
        watchNetworkChanges()
    }

    private fun pollAutoSelect() {
        val events = AutoSelect.poll()
        val session = AutoSelect.session.value ?: return
        val container = (application as dev.cluvex.zedsecure.ZedSecureApp).container
        val selectedId = session.selectedProfileId ?: return
        container.configRepository.rememberAutoSelectPick(session.profileId, selectedId)
        val name = container.configRepository.profile(selectedId)?.name ?: return
        val label = getString(R.string.auto_notif_remark, name)
        if (label != remark) {
            remark = label
            VpnManager.onConnected(remark)
        }
        events.forEach { e ->
            val from = session.memberProfiles[e.from]?.let { container.configRepository.profile(it)?.name } ?: e.from
            val to = session.memberProfiles[e.to]?.let { container.configRepository.profile(it)?.name } ?: e.to
            LogBus.append("I/Auto-select: ${e.reason} $from -> $to")
        }
    }

    private fun watchNetworkChanges() {
        if (networkWatch != null) return
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            private var current: android.net.Network? = null
            private var pending: Job? = null

            override fun onAvailable(network: android.net.Network) {
                val previous = current
                current = network

                if (previous == null || previous == network) return
                pending?.cancel()

                pending = scope.launch {
                    delay(NETWORK_SETTLE_MS)
                    LogBus.append("I/Auto-select: network changed, re-measuring every server")
                    AutoSelect.networkChanged()
                }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkWatch = callback }
            .onFailure { Log.w(TAG, "network watch unavailable", it) }
    }

    private fun stopAutoSelectSession() {
        networkWatch?.let { cb ->
            runCatching { getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        }
        networkWatch = null
        if (autoSelectSession) AutoSelect.end()
        autoSelectSession = false
    }

    private fun startPsiphon(configJson: String, descriptor: ParcelFileDescriptor, tunMtu: Int) {
        val socksPort = java.util.concurrent.atomic.AtomicInteger(0)
        val bridged = java.util.concurrent.atomic.AtomicBoolean(false)
        val controller = PsiphonController(
            service = this,
            configJson = configJson,
            onSocksPort = { port -> socksPort.set(port) },
            onEstablished = {
                if (bridged.compareAndSet(false, true) && bridge(descriptor, socksPort.get(), tunMtu)) {
                    VpnManager.activeSocksPort = socksPort.get()
                    finishConnected()
                }
            },
            onStopped = { reason ->
                onTunnelFailed(reason)
            },
        )
        psiphon = controller
        if (!controller.start()) stopEverything()
    }

    /**
     * Protocol words named by these args, whether spelled as their own
     * flags (--masque, --mim, --gool) or as --protocol values (the form
     * the manual editor stores). Both spellings exist in the wild:
     * automatic and link profiles use the flags, manual profiles use
     * --protocol.
     */
    private fun aetherArgProtocols(args: List<String>): Set<String> {
        val found = mutableSetOf<String>()
        var i = 0
        while (i < args.size) {
            val token = args[i]
            val flag = token.substringBefore('=')
            when (flag) {
                "--masque" -> found += "masque"
                "--wg", "--wireguard", "--warp" -> found += "wg"
                "--gool", "--wiw" -> found += "gool"
                "--gool-classic" -> found += "gool-classic"
                "--mim", "--masque-in-masque" -> found += "mim"
                "--protocol" -> {
                    val value = if ('=' in token) {
                        token.substringAfter('=')
                    } else {
                        args.getOrNull(i + 1).orEmpty().also { i++ }
                    }
                    found += value
                }
            }
            i++
        }
        return found
    }

    /** Two-hop profiles: MIM, modern gool, and classic gool. */
    private fun aetherIsTwoHop(args: List<String>): Boolean {
        val protocols = aetherArgProtocols(args)
        if ("mim" in protocols || "gool" in protocols || "gool-classic" in protocols) {
            return true
        }
        // Hand-written args may name the hops without naming the
        // protocol: hop flags alone still mean two hops.
        return args.any {
            it == "--mim-outer" || it == "--mim-inner" || it == "--mim-peers" ||
                it == "--wiw-outer" || it == "--wiw-inner" || it == "--wiw-peers" ||
                it == "--gool-peer"
        }
    }

    /**
     * Whether an h2/h3 flip can change anything for these args: only
     * MASQUE-family profiles (MASQUE, MIM, modern Gool) have a
     * transport the --h2 flag selects. WireGuard-only and classic
     * gool profiles ignore --h2, so flipping them would only churn
     * restarts for nothing.
     */
    private fun aetherFlipEligible(args: List<String>): Boolean {
        val protocols = aetherArgProtocols(args)
        return "masque" in protocols || "mim" in protocols || "gool" in protocols
    }

    /** Connect-time arguments plus whether the pinned peer had to be
     *  dropped (see [buildAetherConnectArgs]). */
    private data class AetherConnectArgs(
        val args: List<String>,
        val peerFallback: Boolean,
    )

    /**
     * Connect-time arguments for an Aether profile.
     *
     * The service must NOT re-scan. The probe already verified a
     * gateway, and a fresh scan here can land on a different,
     * unverified gateway that hangs (port accepts, SOCKS never
     * answers) — the connect-then-drop the user sees, and the exact
     * difference from PattNG: it scans once, pins the found gateway
     * with --peer, and connects with --quick-reconnect, never
     * re-scanning at connect time. So this strips --scan (+ its
     * value), the two-hop scan flags (--mim-scan, --wiw-scan,
     * --gool-scan) and peers lists of "auto" (the core's word for
     * "scan"), drops any --no-quick-reconnect, pins the probe's
     * verified gateway with --peer when one was captured (a fixed
     * --peer the profile already carried, cdn_worker_ff's or an
     * imported link's, is kept when nothing was pinned), and always
     * adds --quick-reconnect. Two-hop profiles (MIM/WIW/Gool) keep
     * their stored --peer: it IS their outer hop, and a pinned
     * gateway must never override it.
     *
     * [flipH2] is the monitor's h2/h3 flip after a real drop (null =
     * as stored): true forces HTTP/2, false forces HTTP/3. Only
     * applied to MASQUE-family profiles.
     *
     * Emit-time normalization (the stored-profile half of the IPv6
     * bracket fix): every peer endpoint this function emits — the
     * pinned peer, a stored --peer, the two-hop hop flags and
     * explicit peers lists — is normalized through AetherPeer, so a
     * profile saved before the fix, carrying an unbracketed IPv6
     * peer, connects without being deleted or recreated. A peer that
     * is still unusable after normalization is dropped instead of
     * killing the connect: when the dropped value was the pinned
     * peer and nothing replaces it, [AetherConnectArgs.peerFallback]
     * is set and the session connects with --quick-reconnect alone.
     *
     * The bind is service-owned: the planner-supplied socksPort
     * (LocalPorts.AETHER_SOCKS) is 1819, and the bind and the bridge
     * use AetherBinary.SOCKS_PORT, the same 1819, so a stale intent
     * cannot split them — except for a Psiphon-chained profile (an
     * imported aether:// link with --psiphon): there the app must dial
     * the Psiphon listener on 1819, so the tunnel itself binds 1820
     * and Psiphon is told to listen on 1819. Readiness, the bridge
     * and activeSocksPort stay on 1819 in both cases.
     */
    private fun buildAetherConnectArgs(
        profile: dev.cluvex.zedsecure.domain.config.AetherProfile,
        flipH2: Boolean?,
    ): AetherConnectArgs {
        val isTwoHop = aetherIsTwoHop(profile.args)
        val eligible = aetherFlipEligible(profile.args)
        val effectiveFlip = if (eligible) flipH2 else null
        var peerFallback = false
        val connectArgs = buildList {
            var i = 0
            var storedPeer: String? = null
            while (i < profile.args.size) {
                val token = profile.args[i]
                val flag = token.substringBefore('=')
                when (flag) {
                    // --scan balanced and --scan=balanced alike: skip
                    // the flag and its value, so connect never re-scans.
                    // A bare --scan never eats a following flag: with
                    // "--scan --h3" the --h3 must survive.
                    "--scan" -> {
                        val next = profile.args.getOrNull(i + 1)
                        i += if ('=' in token) 1 else if (next == null || next.startsWith("--")) 1 else 2
                    }
                    // Two-hop scan flags: a connect must never re-scan
                    // for a hop, pinned or not.
                    "--mim-scan", "--wiw-scan", "--gool-scan" -> i += 1
                    // A peers list of "auto" means "scan" in the core;
                    // an explicit list is a pin and is kept.
                    "--mim-peers", "--wiw-peers", "--gool-peers" -> {
                        val peersValue = if ('=' in token) {
                            token.substringAfter('=')
                        } else {
                            profile.args.getOrNull(i + 1)
                        }
                        if (peersValue?.trim() == "auto") {
                            i += if ('=' in token) 1 else 2
                        } else {
                            // Kept — with each element normalized, so an
                            // unbracketed IPv6 element cannot kill the
                            // connect the way --peer did.
                            val normalized = peersValue
                                ?.split(',')
                                ?.joinToString(",") { AetherPeer.normalize(it.trim()) }
                            if ('=' in token) {
                                add("$flag=$normalized")
                            } else {
                                add(token)
                                normalized?.let { add(it) }
                            }
                            i += if ('=' in token) 1 else 2
                        }
                    }
                    // Two-hop hop endpoints, carried as separate flags:
                    // normalize their values at emit time, exactly like
                    // --peer below, for stored profiles saved before
                    // the bracket fix.
                    "--gool-peer", "--mim-outer", "--mim-inner",
                    "--wiw-outer", "--wiw-inner", "--h2-peer" -> {
                        if ('=' in token) {
                            add("$flag=${AetherPeer.normalize(token.substringAfter('='))}")
                            i += 1
                        } else {
                            add(token)
                            profile.args.getOrNull(i + 1)?.let { add(AetherPeer.normalize(it)) }
                            i += 2
                        }
                    }
                    "--no-quick-reconnect", "--quick-reconnect" -> i += 1
                    "--peer" -> {
                        storedPeer = if ('=' in token) {
                            token.substringAfter('=')
                        } else {
                            profile.args.getOrNull(i + 1)
                        }
                        i += if ('=' in token) 1 else 2
                    }
                    "--h2", "--http2" -> {
                        // Flipping to HTTP/3 drops the H2 flag; every
                        // other case keeps it, canonicalized to --h2.
                        if (effectiveFlip == false) i += 1 else { add("--h2"); i += 1 }
                    }
                    "--h3", "--quic" -> {
                        // Flipping to HTTP/2 drops the H3 flag; every
                        // other case keeps it, canonicalized to --h3.
                        if (effectiveFlip == true) i += 1 else { add("--h3"); i += 1 }
                    }
                    else -> { add(token); i += 1 }
                }
            }
            if (effectiveFlip == true && "--h2" !in this) add("--h2")
            if (effectiveFlip == false && "--h3" !in this) add("--h3")
            // The pinned gateway replaces a stored --peer only on
            // single-hop profiles. Two-hop profiles (gool/mim) carry
            // their outer hop in --peer itself, so the stored value is
            // always kept there — a pin must never override it.
            // Every emitted peer is normalized (AetherPeer) and
            // validated: an unbracketed IPv6 peer stored before the
            // bracket fix must connect, and a peer that is still
            // unusable must not kill the connect.
            val pinned = AetherPeer.normalize(profile.pinnedPeer)
            val stored = storedPeer?.let { AetherPeer.normalize(it) }
            if (!isTwoHop && pinned.isNotBlank()) {
                if (AetherPeer.isUsable(pinned)) {
                    add("--peer"); add(pinned)
                } else if (stored != null && AetherPeer.isUsable(stored)) {
                    // The pinned gateway is unusable, but the profile's
                    // own fixed peer is fine: use it. This is the
                    // normal "kept fixed peer" path, not a fallback.
                    add("--peer"); add(stored)
                } else {
                    // No usable peer at all: connect with
                    // --quick-reconnect alone and let the core use its
                    // last connection. The caller surfaces the
                    // fallback notice.
                    peerFallback = true
                }
            } else if (stored != null) {
                if (AetherPeer.isUsable(stored)) {
                    add("--peer"); add(stored)
                }
                // An unusable stored peer is dropped silently: it is
                // corrupt data, not a pinned gateway, and the next
                // editor save rewrites it normalized. No notice.
            }
            add("--quick-reconnect")
        }
        val chained = profile.args.contains("--psiphon")
        val tunnelPort =
            if (chained) AetherBinary.SOCKS_PORT + 1 else AetherBinary.SOCKS_PORT
        return AetherConnectArgs(
            connectArgs + listOf(
                "--bind", "127.0.0.1:$tunnelPort",
            ) + if (chained && "--psiphon-bind" !in connectArgs) {
                listOf("--psiphon-bind", "127.0.0.1:${AetherBinary.SOCKS_PORT}")
            } else {
                emptyList()
            },
            peerFallback,
        )
    }

    private fun startAether(
        configJson: String,
        socksPort: Int,
        descriptor: ParcelFileDescriptor,
        tunMtu: Int,
    ) {
        // Double-start guard: a previous Aether process must never survive
        // a fresh start with nothing left able to stop it.
        stopAether()
        scope.launch {
            val profile = try {
                kotlinx.serialization.json.Json.decodeFromString(
                    dev.cluvex.zedsecure.domain.config.AetherProfile.serializer(), configJson,
                )
            } catch (e: Exception) {
                Log.e(TAG, "bad Aether config", e)
                VpnManager.onError(getString(R.string.config_invalid))
                stopEverything(); return@launch
            }
            val installed = AetherBinary.ensureInstalled(this@ZedVpnService)
            if (installed.isFailure) {
                Log.w(TAG, "Aether binary missing: ${installed.exceptionOrNull()?.message}")
                VpnManager.onError(getString(R.string.engine_failed_tunnel))
                stopEverything(); return@launch
            }
            if (userStop) return@launch
            // A crashed or force-stopped earlier session can leave an
            // Aether/Psiphon process holding port 1819 or the Psiphon
            // datastore; clear those leftovers before starting, so the
            // new session can bind. Guarded inside; never blocks a start.
            AetherBinary.killOrphans(this@ZedVpnService)
            aetherProfile = profile
            aetherFlipH2 = null
            val dir = AetherBinary.configDir(this@ZedVpnService)
            // Connect args are built by buildAetherConnectArgs (no
            // re-scan, pinned peer, --quick-reconnect, service-owned
            // binds); see that function for the full rationale.
            val built = buildAetherConnectArgs(profile, flipH2 = null)
            aetherPeerFallback = built.peerFallback
            if (built.peerFallback) {
                LogBus.append(
                    "W/Aether: آدرس ذخیره‌شده قابل استفاده نبود — وصل با اتصال سریع، بدون آدرس مشخص",
                )
            }
            val fullArgs = built.args
            val process = try {
                AetherBinary.start(this@ZedVpnService, fullArgs, dir)
            } catch (e: Exception) {
                Log.e(TAG, "Aether failed to start", e)
                VpnManager.onError(getString(R.string.engine_failed_tunnel))
                stopEverything(); return@launch
            }
            if (userStop) {
                runCatching { AetherBinary.stop(process) }
                return@launch
            }
            aetherProcess = process
            aetherArgs = fullArgs
            aetherWorkDir = dir
            // Aether's first scan can be slow: poll with the same full
            // check other engines use (TunnelReadiness.verify does a
            // SOCKS5 CONNECT + HTTP HEAD through the tunnel) for up to
            // 120s, aborting early if the process dies or the user stops.
            if (!waitForAetherReady(AETHER_READY_TIMEOUT_MS)) {
                if (userStop) return@launch
                val exit = runCatching { process.exitValue() }.getOrNull()
                Log.w(TAG, "Aether SOCKS on 127.0.0.1:${AetherBinary.SOCKS_PORT} never became ready; alive=${process.isAlive} exit=$exit; log tail:\n${AetherBinary.logTail(dir)}")
                VpnManager.onError(getString(R.string.engine_failed_tunnel))
                stopEverything(); return@launch
            }
            if (userStop) return@launch
            if (!bridge(descriptor, AetherBinary.SOCKS_PORT, tunMtu)) return@launch
            if (aetherProcess?.isAlive != true) {
                VpnManager.onError(getString(R.string.engine_failed_tunnel))
                stopEverything(); return@launch
            }
            VpnManager.activeSocksPort = AetherBinary.SOCKS_PORT
            finishConnected()
            startAetherMonitor()
            startAetherOptimizer(profile)
        }
    }

    private suspend fun waitForAetherReady(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        // The core announces readiness in its own log: "tunnel
        // validated" once the tunnel carries traffic, and for
        // Psiphon-chained profiles "psiphon is ready" once the chain is
        // up. The bare "socks5 server listening" line is NOT enough on
        // its own — the port can open before traffic flows — so it is
        // deliberately not trusted here. A log line alone declares
        // readiness only for unchained profiles: for a chained profile
        // "tunnel validated" arrives for the WARP tunnel before
        // Psiphon is up, so the chained path needs the "psiphon is
        // ready" line AND the HTTPS verify below. The verify is the
        // final arbiter in every path, and can also declare a profile
        // ready on its own when the log lines never appear.
        val psiphonArgs = aetherProfile?.args.orEmpty()
        val chained = psiphonArgs.contains("--psiphon") ||
            psiphonArgs.contains("--psiphon-reverse") ||
            psiphonArgs.contains("--psiphon-only")
        val dir = aetherWorkDir
        while (System.currentTimeMillis() < deadline) {
            if (userStop || !scope.isActive) return false
            val proc = aetherProcess ?: return false
            if (!proc.isAlive) return false
            val logReady = if (dir != null) {
                if (chained) {
                    AetherBinary.logContains(dir, "psiphon is ready")
                } else {
                    AetherBinary.logContains(dir, "tunnel validated")
                }
            } else {
                false
            }
            if (logReady && !chained) return true
            // One HTTPS verify per round. For a chained profile it only
            // counts once the chain's own "psiphon is ready" line has
            // also appeared.
            if (TunnelReadiness.verify(AetherBinary.SOCKS_PORT, timeoutMs = 10_000, attempts = 1)) {
                if (!chained || logReady) return true
            }
            delay(2_000)
        }
        return false
    }

    private suspend fun waitForAetherPortUp(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (userStop || !scope.isActive) return false
            val up = runCatching {
                java.net.Socket().use { socket ->
                    socket.connect(
                        java.net.InetSocketAddress("127.0.0.1", AetherBinary.SOCKS_PORT),
                        2_000,
                    )
                    true
                }
            }.getOrDefault(false)
            if (up) return true
            delay(1_000)
        }
        return false
    }

    private fun startAetherMonitor() {
        aetherMonitorJob?.cancel()
        val generation = aetherGeneration
        registerAetherNetworkCallback()
        aetherMonitorJob = scope.launch(Dispatchers.IO) {
            var restarts = 0
            while (isActive && generation == aetherGeneration && !userStop) {
                val proc = aetherProcess ?: return@launch
                val exitCode = runCatching { proc.waitFor() }.getOrNull()
                if (!isActive || generation != aetherGeneration || userStop) return@launch
                if (aetherSwapPending) {
                    // The optimizer is swapping the core behind the
                    // live TUN: the retired process's exit is
                    // expected. Wait for the swap to settle, then
                    // watch the new process — without spending
                    // restart budget and without spinning on the
                    // dead process while the swap runs.
                    var waited = 0L
                    while (aetherSwapPending && waited < 10_000L &&
                        isActive && generation == aetherGeneration && !userStop
                    ) {
                        delay(200)
                        waited += 200
                    }
                    continue
                }
                // The process changed under the same generation: only
                // the optimizer swap does that (stopAether bumps the
                // generation, and the check above already returned).
                // Watch the current process instead of ending the
                // monitor while the session still runs.
                if (aetherProcess !== proc) continue
                // Tunnel already torn down (the stop path raced us):
                // nothing to restart.
                if (!connected) return@launch
                // An expected exit is one the network-change handler
                // asked for (a network switch): relaunch with the SAME
                // args — pinned peer, no fresh scan, no transport flip.
                // It neither consumes nor refills the restart budget:
                // the counter is only reset by a relaunch that
                // verifiably carries traffic (below), so a
                // network-change crash loop cannot refill it.
                val expected = aetherExpectExit
                aetherExpectExit = false
                if (expected) {
                    LogBus.append(
                        "I/Aether: شبکه عوض شد — وصل دوباره با همان نقطه، بدون اسکن تازه (کد خروج $exitCode)",
                    )
                    delay(500)
                    if (!isActive || generation != aetherGeneration || userStop) return@launch
                    // The stopped process may have left a Psiphon child
                    // holding the ports or its datastore; clear it
                    // before the new process tries to bind.
                    AetherBinary.killOrphans(this@ZedVpnService)
                    val dir = aetherWorkDir
                    val relaunched = if (dir != null) {
                        runCatching { AetherBinary.start(this@ZedVpnService, aetherArgs, dir) }.getOrNull()
                    } else null
                    if (relaunched == null) {
                        Log.w(TAG, "Aether network-change restart failed to launch")
                        continue
                    }
                    aetherProcess = relaunched
                    waitForAetherPortUp(AETHER_RESTART_READY_TIMEOUT_MS)
                    if (TunnelReadiness.verify(AetherBinary.SOCKS_PORT, timeoutMs = 10_000, attempts = 1)) {
                        restarts = 0
                        clearAetherRetryNotice()
                    }
                    continue
                }
                if (restarts >= AETHER_MAX_RESTARTS) {
                    val tail = aetherWorkDir?.let { AetherBinary.logTail(it) }.orEmpty()
                    Log.w(TAG, "Aether exited unexpectedly; $AETHER_MAX_RESTARTS restarts exhausted; last exit=$exitCode; log tail:\n$tail")
                    LogBus.append(
                        "I/Aether: موتور بسته شد و وصل دوباره هم جواب نداد (کد خروج $exitCode)",
                    )
                    VpnManager.onError(getString(R.string.engine_failed_tunnel))
                    stopEverything(); return@launch
                }
                restarts++
                // Backoff: 3s, then 6s, then 12s. The bound stays 3
                // relaunches — only the spacing changes. The status
                // line names the wait, in Persian, from the real
                // relaunch state: attempt N of 3, after D seconds.
                val backoffMs = AETHER_RESTART_BACKOFF_MS
                    .getOrElse(restarts - 1) { AETHER_RESTART_BACKOFF_MS.last() }
                VpnManager.setNotice(
                    getString(
                        R.string.aether_retry_status,
                        restarts,
                        AETHER_MAX_RESTARTS,
                        (backoffMs / 1000).toInt(),
                    ),
                )
                // A real drop: flip the transport once (HTTP/2 <-> HTTP/3)
                // for MASQUE-family profiles — one side or the other is
                // often what the firewall is throttling right now — and
                // rebuild from the stored profile, pinned peer included.
                // Other profiles relaunch with the same args.
                val profile = aetherProfile
                var newArgs = aetherArgs
                if (profile != null && aetherFlipEligible(profile.args)) {
                    // Flip to the other transport: H3 now (or an H3
                    // alias) means "go H2"; anything else — H2, or no
                    // flag at all, which this app reads as H2 — means
                    // "go H3".
                    aetherFlipH2 = aetherArgs.contains("--h3") || aetherArgs.contains("--quic")
                    val rebuilt = buildAetherConnectArgs(profile, aetherFlipH2)
                    aetherPeerFallback = rebuilt.peerFallback
                    newArgs = rebuilt.args
                }
                Log.w(TAG, "Aether exited unexpectedly; restart $restarts/$AETHER_MAX_RESTARTS; exit=$exitCode")
                LogBus.append(
                    "I/Aether: موتور بسته شد (کد خروج $exitCode) — وصل دوباره با " +
                        if (aetherFlipH2 == true) "HTTP/2" else if (aetherFlipH2 == false) "HTTP/3" else "همان تنظیم",
                )
                delay(backoffMs)
                if (!isActive || generation != aetherGeneration || userStop) return@launch
                // The dead process may have left a Psiphon child holding
                // the ports or its datastore; clear it before relaunch.
                AetherBinary.killOrphans(this@ZedVpnService)
                val dir = aetherWorkDir
                val relaunched = if (dir != null) {
                    runCatching { AetherBinary.start(this@ZedVpnService, newArgs, dir) }.getOrNull()
                } else null
                if (relaunched == null) {
                    Log.w(TAG, "Aether restart $restarts failed to launch")
                    // aetherProcess still points at the dead process, so
                    // the next pass exits waitFor at once and consumes the
                    // next restart; bounded by AETHER_MAX_RESTARTS above.
                    continue
                }
                aetherArgs = newArgs
                aetherProcess = relaunched
                // The TUN bridge is bound to the port, not the process,
                // so no re-bridge is needed; just wait for the listener
                // to come back before watching the new process. A
                // relaunch that verifiably carries traffic refills the
                // restart budget.
                waitForAetherPortUp(AETHER_RESTART_READY_TIMEOUT_MS)
                if (TunnelReadiness.verify(AetherBinary.SOCKS_PORT, timeoutMs = 10_000, attempts = 1)) {
                    restarts = 0
                    clearAetherRetryNotice()
                }
            }
        }
    }

    /**
     * The retry notice served its purpose: restore the session's own
     * notice (the peer-fallback note, if the session has one) now that
     * the relaunched core verifiably carries traffic.
     */
    private fun clearAetherRetryNotice() {
        VpnManager.setNotice(
            if (aetherPeerFallback) getString(R.string.aether_peer_fallback_notice) else null,
        )
    }

    /**
     * Background optimizer, phase 2 of the quick-first algorithm:
     * after the session is stable, probe the OTHER ladder methods in
     * the background and move to a clearly faster one — without the
     * user ever seeing a disconnect.
     *
     * Runs ONLY for sessions connected via the ladder: the gate is
     * the profile's method name, which the auto-config alone sets to
     * a ladder name (imported links use "link", the manual editor
     * uses "manual"), plus no --psiphon and no two-hop flags. Manual
     * and stored profiles keep their exact behavior: this function
     * returns before doing anything for them.
     *
     * Safety, in order: probes use the isolated optimizer dir and
     * port 1822 (never the live 1819, never the chained 1820, never
     * the scan port 1821), so they cannot touch the live tunnel;
     * the incumbent is only challenged when it is slower than 300ms
     * (good enough means no switch); a challenger must beat it by
     * 30% on two CONSECUTIVE samples; at most one switch, only in
     * the first five minutes and at least a minute after connect;
     * ANY optimizer failure is ignored and the live session is
     * untouched; the job is cancelled on disconnect and on a
     * network-change reconnect.
     */
    private fun startAetherOptimizer(
        profile: dev.cluvex.zedsecure.domain.config.AetherProfile,
    ) {
        aetherOptimizerJob?.cancel()
        aetherOptimizerJob = null
        val candidates = AetherAutoConfig.buildCandidates(AetherAutoConfig.ipv6Available(this))
        if (candidates.none { it.method == profile.method }) return
        if (profile.args.contains("--psiphon")) return
        if (aetherIsTwoHop(profile.args)) return
        aetherOptimizerJob = scope.launch(Dispatchers.IO) {
            val generation = aetherGeneration
            // Let the incumbent prove itself stable first.
            delay(AETHER_OPTIMIZER_STABLE_MS)
            if (!isActive || generation != aetherGeneration || userStop || !connected) return@launch
            if (kind != VpnManager.KIND_AETHER) return@launch
            // Judge the incumbent through the LIVE tunnel. If it does
            // not carry traffic, or is already good enough, there is
            // nothing to optimize — leave the session alone.
            val incumbentSamples = measureIncumbentSamples()
            if (incumbentSamples.size < 2) return@launch
            val sortedIncumbent = incumbentSamples.sorted()
            val incumbentMedian = sortedIncumbent[sortedIncumbent.size / 2]
            if (incumbentMedian < AETHER_OPTIMIZER_GOOD_ENOUGH_MS) return@launch
            val failedRecently = AetherRouteMemory.failedMethods(this@ZedVpnService)
            var probed = 0
            for (challenger in candidates) {
                if (probed >= AETHER_OPTIMIZER_MAX_CHALLENGERS) break
                if (challenger.method == profile.method) continue
                if (challenger.method in failedRecently) continue
                if (!isActive || generation != aetherGeneration || userStop || !connected) return@launch
                if (aetherOptimizerSwitched) return@launch
                val ageMs = System.currentTimeMillis() - connectWhen
                if (ageMs < AETHER_OPTIMIZER_MIN_AGE_MS || ageMs > AETHER_OPTIMIZER_WINDOW_MS) return@launch
                probed++
                val outcome = try {
                    AetherAutoConfig.probeCandidate(
                        this@ZedVpnService,
                        challenger,
                        AetherBinary.optimizerDir(this@ZedVpnService),
                        AETHER_OPTIMIZER_PROBE_TIMEOUT_MS,
                        AetherAutoConfig.OPTIMIZER_PORT,
                        extraSamples = 2,
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A failed probe is not a session problem: try
                    // the next challenger, live session untouched.
                    continue
                }
                if (!outcome.success) continue
                // Two CONSECUTIVE samples must each beat the
                // incumbent by 30%: one lucky sample is not
                // stability, and the median alone cannot prove it.
                val beatMs = incumbentMedian *
                    AETHER_OPTIMIZER_BEAT_NUMERATOR / AETHER_OPTIMIZER_BEAT_DENOMINATOR
                val wins = outcome.latencySamples.zipWithNext().any { (a, b) ->
                    a <= beatMs && b <= beatMs
                }
                if (!wins) continue
                val switched = swapAetherCore(profile, challenger, outcome)
                if (switched) {
                    AetherRouteMemory.rememberWin(
                        this@ZedVpnService,
                        challenger.method,
                        outcome.pinnedPeer,
                    )
                    LogBus.append(
                        "I/Aether: روش سریع‌تر پیدا شد (${challenger.displayName}) — بدون قطع شدن عوض شد",
                    )
                }
                // One switch attempt ends the optimizer either way:
                // a failed swap means the session is fragile and is
                // left alone from here.
                return@launch
            }
        }
    }

    /** Trace samples through the live tunnel, for judging the incumbent. */
    private suspend fun measureIncumbentSamples(): List<Long> {
        val samples = mutableListOf<Long>()
        repeat(AETHER_OPTIMIZER_INCUMBENT_SAMPLES) { index ->
            if (index > 0) delay(AETHER_OPTIMIZER_SAMPLE_GAP_MS)
            if (!connected || userStop) return samples
            AetherAutoConfig.traceLatencyMs(AetherBinary.SOCKS_PORT)?.let { samples += it }
        }
        return samples
    }

    /**
     * Switches the running session to [challenger]'s args behind the
     * live TUN, without tearing the TUN down. Mechanism: same-port
     * core swap. The bridge is bound to the SOCKS port, not the
     * process — the monitor has always relaunched exactly this way
     * — so the old core is stopped, the new core binds the same
     * port, and the bridge keeps forwarding with a blink of a
     * second or two. The stats loop and onTunnelFailed are held off
     * by the existing swappingCore flag for the whole blink, and
     * the monitor is told the exit is a swap, not a crash.
     *
     * Returns true only when the new core verifiably carries
     * traffic. On ANY failure the incumbent is restored and false
     * is returned, so the caller leaves the session alone.
     */
    private suspend fun swapAetherCore(
        incumbent: dev.cluvex.zedsecure.domain.config.AetherProfile,
        challenger: AetherAutoConfig.Candidate,
        outcome: AetherAutoConfig.ProbeOutcome,
    ): Boolean {
        val dir = aetherWorkDir ?: return false
        val oldProcess = aetherProcess ?: return false
        val oldArgs = aetherArgs
        val oldFallback = aetherPeerFallback
        val newProfile = incumbent.copy(
            method = challenger.method,
            args = challenger.args,
            displayName = challenger.displayName,
            address = challenger.address,
            ipVersion = challenger.ipVersion,
            fingerprint = challenger.fingerprint,
            cipherSuites = challenger.cipherSuites,
            finalMask = challenger.finalMask,
            echConfigList = challenger.echConfigList,
            alpn = challenger.alpn,
            pinnedPeer = outcome.pinnedPeer,
        )
        val built = buildAetherConnectArgs(newProfile, flipH2 = null)
        aetherSwapPending = true
        swappingCore = true
        try {
            AetherBinary.stop(oldProcess)
            AetherBinary.killOrphans(this)
            val started = runCatching {
                AetherBinary.start(this, built.args, dir)
            }.getOrNull()
            if (started == null) {
                rollbackAetherCore(incumbent, oldArgs, oldFallback, dir)
                return false
            }
            aetherProcess = started
            aetherArgs = built.args
            aetherFlipH2 = null
            aetherPeerFallback = built.peerFallback
            if (!waitForAetherPortUp(AETHER_OPTIMIZER_SWAP_READY_MS)) {
                rollbackAetherCore(incumbent, oldArgs, oldFallback, dir)
                return false
            }
            if (!TunnelReadiness.verify(AetherBinary.SOCKS_PORT, timeoutMs = 10_000, attempts = 1)) {
                rollbackAetherCore(incumbent, oldArgs, oldFallback, dir)
                return false
            }
            aetherProfile = newProfile
            aetherOptimizerSwitched = true
            return true
        } finally {
            swappingCore = false
            aetherSwapPending = false
        }
    }

    /**
     * Restores the incumbent core after a failed swap. Best effort:
     * if the restore itself fails, aetherProcess still points at a
     * stopped process, and the monitor/stats loop end the session
     * cleanly as a dead engine — nothing else can save it then.
     */
    private suspend fun rollbackAetherCore(
        incumbent: dev.cluvex.zedsecure.domain.config.AetherProfile,
        oldArgs: List<String>,
        oldFallback: Boolean,
        dir: java.io.File,
    ) {
        val current = aetherProcess
        if (current != null) runCatching { AetherBinary.stop(current) }
        AetherBinary.killOrphans(this)
        val restored = runCatching { AetherBinary.start(this, oldArgs, dir) }.getOrNull()
        if (restored != null) {
            aetherProcess = restored
            aetherArgs = oldArgs
            aetherProfile = incumbent
            aetherFlipH2 = null
            aetherPeerFallback = oldFallback
            waitForAetherPortUp(AETHER_OPTIMIZER_SWAP_READY_MS)
            TunnelReadiness.verify(AetherBinary.SOCKS_PORT, timeoutMs = 10_000, attempts = 1)
            LogBus.append("W/Aether: عوض کردن روش جواب نداد — برگشت به روش قبلی")
        } else {
            LogBus.append("W/Aether: عوض کردن روش و برگشت به روش قبلی هم جواب نداد")
        }
    }

    /**
     * Watches the default network while an Aether session runs, so a
     * Wi-Fi <-> mobile switch reconnects at once instead of waiting
     * for the tunnel to time out. Only active while the monitor runs
     * (registered by [startAetherMonitor], removed by [stopAether]).
     * Android calls onAvailable immediately on registration with the
     * current network: that first call is the registration echo, not a
     * switch, so the network seen first is recorded as the baseline
     * and ignored, and only a different network (or the baseline
     * network's loss) counts as a change.
     */
    private fun registerAetherNetworkCallback() {
        unregisterAetherNetworkCallback()
        aetherBaselineNetwork = null
        aetherBaselineSet = false
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!aetherBaselineSet) {
                    aetherBaselineNetwork = network
                    aetherBaselineSet = true
                    return
                }
                if (network == aetherBaselineNetwork) return
                aetherBaselineNetwork = network
                onAetherNetworkChanged()
            }

            override fun onLost(network: Network) {
                if (!aetherBaselineSet) return
                if (network != aetherBaselineNetwork) return
                // The network we knew is gone; forget it so the
                // replacement's onAvailable counts as the switch. The
                // 15s gate dedupes the onLost/onAvailable pair.
                aetherBaselineNetwork = null
                onAetherNetworkChanged()
            }
        }
        val registered = runCatching {
            cm.registerDefaultNetworkCallback(callback)
        }.isSuccess
        if (registered) aetherNetworkCallback = callback
    }

    private fun unregisterAetherNetworkCallback() {
        val callback = aetherNetworkCallback ?: return
        aetherNetworkCallback = null
        aetherBaselineNetwork = null
        aetherBaselineSet = false
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        runCatching { cm.unregisterNetworkCallback(callback) }
    }

    /**
     * A network switch while an Aether session is up: stop the process
     * (marked expected) so the monitor relaunches it at once with the
     * pinned peer — no fresh scan, no flip. Debounced (a switch fires
     * several callbacks) and capped at one restart per 15 seconds, so
     * a flapping network cannot restart-loop. Ignored unless the
     * monitor is actually watching a live session. The registration
     * echo is filtered out by the callback itself (baseline network),
     * so this only ever sees real changes.
     */
    private fun onAetherNetworkChanged() {
        if (aetherMonitorJob?.isActive != true) return
        if (aetherProcess == null || userStop) return
        val now = System.currentTimeMillis()
        if (now - aetherLastNetworkRestartMs < AETHER_NETWORK_MIN_INTERVAL_MS) return
        aetherLastNetworkRestartMs = now
        val generation = aetherGeneration
        scope.launch(Dispatchers.IO) {
            delay(AETHER_NETWORK_SETTLE_MS)
            if (userStop || generation != aetherGeneration) return@launch
            if (aetherMonitorJob?.isActive != true) return@launch
            // The reconnect owns the session from here: the optimizer
            // never runs again in this session, so it cannot probe or
            // swap a core the reconnect is about to replace.
            aetherOptimizerJob?.cancel()
            aetherOptimizerJob = null
            val proc = aetherProcess ?: return@launch
            if (!proc.isAlive) return@launch
            aetherExpectExit = true
            runCatching { AetherBinary.stop(proc) }
        }
    }

    private fun stopAether() {
        aetherGeneration++
        aetherMonitorJob?.cancel()
        aetherMonitorJob = null
        aetherOptimizerJob?.cancel()
        aetherOptimizerJob = null
        aetherSwapPending = false
        aetherOptimizerSwitched = false
        aetherPeerFallback = false
        unregisterAetherNetworkCallback()
        aetherExpectExit = false
        aetherProfile = null
        aetherFlipH2 = null
        aetherLastNetworkRestartMs = 0L
        val proc = aetherProcess
        aetherProcess = null
        aetherArgs = emptyList()
        aetherWorkDir = null
        if (proc != null) runCatching { AetherBinary.stop(proc) }
    }

    private fun bridge(
        descriptor: ParcelFileDescriptor,
        socksPort: Int,
        tunMtu: Int,
        udpOverTcp: Boolean = false,
        pipeline: Boolean = false,
    ): Boolean {
        advance(VpnNotifications.Stage.Engine)

        val startHev = {
            HevTunCore.start(
                context = this,
                tun = descriptor,
                socksPort = socksPort,
                mtu = tunMtu,
                ipv4 = hevIpv4,
                ipv6 = hevIpv6,
                preferIpv6 = hevIpv6 != null,
                udpOverTcp = udpOverTcp,
                pipeline = pipeline,
                logLevel = hevLogLevel,
                rwTimeout = hevRwTimeout,
            )
        }
        var onZepTun = false
        val ok = if (useZepTun) {
            onZepTun = ZepTunCore.start(
                service = this,
                tun = descriptor,
                socksPort = socksPort,
                mtu = tunMtu,
                ipv4 = hevIpv4,
                ipv6 = hevIpv6,
                udpOverTcp = udpOverTcp,
                pipeline = pipeline,
                logLevel = hevLogLevel,
                rwTimeout = hevRwTimeout,
            )
            if (onZepTun) {
                LogBus.append("I/tun zeptun ${ZepTunCore.version} carrying the tunnel")
                true
            } else {
                Log.w(TAG, "zeptun did not start; falling back to hev")
                LogBus.append("W/tun zeptun did not start, falling back to hev")
                startHev()
            }
        } else {
            startHev()
        }

        if (ok && onZepTun) {
            scope.launch {
                kotlinx.coroutines.delay(ZEPTUN_PROOF_MS)
                if (!ZepTunCore.carryingTraffic()) {
                    LogBus.append("W/tun zeptun carried nothing (${ZepTunCore.stats()}), switching to hev")
                    Log.w(TAG, "zeptun carried nothing (${ZepTunCore.stats()}); switching to hev")
                    ZepTunCore.stop()
                    if (!startHev()) {
                        VpnManager.onError(getString(R.string.engine_failed_tunnel))
                        stopEverything()
                    }
                }
            }
        }

        if (ok && udpOverTcp) VpnManager.activeSocksPort = socksPort
        if (!ok) {
            VpnManager.onError(getString(R.string.engine_failed_tunnel))
            stopEverything()
        } else {
            advance(VpnNotifications.Stage.Tunnel)
        }
        return ok
    }

    private fun advance(next: VpnNotifications.Stage) {
        if (connected) return
        stage = next
        runCatching { notify(connectingNotification()) }
        ZedWidgetProvider.refresh(this, force = true)
    }

    private fun connectingNotification(): android.app.Notification =
        notifications.build(
            server = remark,
            phase = VpnNotifications.Phase.Connecting(stage),
            protocolLabel = protocolLabel(),
            livePromotion = livePromotion,
        )

    private fun protocolLabel(): String = when (kind) {
        VpnManager.KIND_PSIPHON -> "Psiphon"
        VpnManager.KIND_DNS_TUNNEL -> "DNS"
        VpnManager.KIND_MASTERDNS -> "MasterDNS"
        VpnManager.KIND_TOR -> "Tor"
        VpnManager.KIND_SSH -> "SSH"
        VpnManager.KIND_OPENCONNECT -> "OpenConnect"
        VpnManager.KIND_SINGBOX -> "sing-box"
        VpnManager.KIND_IKEV2 -> "IKEv2"
        VpnManager.KIND_SNISPOOF -> "Xray · SNI"
        VpnManager.KIND_AETHER -> "Aether"

        VpnManager.KIND_CROSS_CHAIN ->
            "${engineLabel(crossInnerKind)} → ${engineLabel(crossOuterKind)}"
        else -> if (proxyOnly) "Xray · Proxy" else "Xray"
    }

    private fun engineLabel(k: String?): String = when (k) {
        VpnManager.KIND_PSIPHON -> "Psiphon"
        VpnManager.KIND_DNS_TUNNEL -> "DNS"
        VpnManager.KIND_MASTERDNS -> "MasterDNS"
        VpnManager.KIND_TOR -> "Tor"
        VpnManager.KIND_SSH -> "SSH"
        else -> "Xray"
    }

    private fun finishConnected() {
        connected = true
        if (reconnecting) LogBus.append("I/Kill switch: reconnected; traffic flows through the VPN again")
        reconnecting = false
        stopHoldWatch()
        retryDelayMs = RETRY_FIRST_MS
        geoReloadJob?.cancel()
        geoReloadJob = scope.launch {
            dev.cluvex.zedsecure.data.assets.GeoAutoFetch.ready.collect { reloadForGeoFiles() }
        }
        stage = VpnNotifications.Stage.Connected
        connectWhen = System.currentTimeMillis()
        // An Aether session that fell back from an unusable pinned peer
        // carries the one-line notice; every other engine, and an
        // Aether session that used its peer, passes null — the same
        // as the previous unconditional call.
        VpnManager.onConnected(
            remark,
            if (kind == VpnManager.KIND_AETHER && aetherPeerFallback) {
                getString(R.string.aether_peer_fallback_notice)
            } else {
                null
            },
        )
        notify(connectedNotification(0, 0, 0, 0))
        ZedWidgetProvider.refresh(this, force = true)
        startStatsLoop()
        watchNetworkType()
    }

    private fun watchNetworkType() {
        if (networkTypeWatch != null || kind != VpnManager.KIND_XRAY || autoSelectSession) return
        val settings = SettingsRepository(this).settings.value
        val dependent = dev.cluvex.zedsecure.domain.config.RoutingMigration.effectiveRulesets(settings)
            .any { it.enabled && it.networkType.isNotEmpty() }
        if (!dependent) return
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return
        builtNetworkType = NetworkKind.current(this)
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) {
                val type = NetworkKind.of(caps) ?: return
                if (type == builtNetworkType) return
                networkTypeJob?.cancel()
                networkTypeJob = scope.launch {
                    delay(NETWORK_SETTLE_MS)
                    rebuildForNetworkType(type)
                }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkTypeWatch = callback }
            .onFailure { Log.w(TAG, "network type watch unavailable", it) }
    }

    private fun stopNetworkTypeWatch() {
        networkTypeJob?.cancel()
        networkTypeJob = null
        networkTypeWatch?.let { cb ->
            runCatching { getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        }
        networkTypeWatch = null
    }

    private fun rebuildForNetworkType(type: String) {
        if (!connected || kind != VpnManager.KIND_XRAY || autoSelectSession) return
        val plan = StartPlanner(this).planActive(allowProxyOnly = proxyOnly)
        val sameSession = plan is StartPlanner.Plan.Start && plan.kind == VpnManager.KIND_XRAY &&
            plan.proxyOnly == proxyOnly && plan.remark == remark &&
            plan.socksPort == (VpnManager.activeSocksPort ?: plan.socksPort)
        if (!sameSession) {
            builtNetworkType = type
            LogBus.append("W/Routing: the network is now $type; reconnect to use the rules for it")
            return
        }
        swappingCore = true
        val started = try {
            XrayController.start((plan as StartPlanner.Plan.Start).configJson)
        } finally {
            swappingCore = false
        }
        if (started) {
            builtNetworkType = type
            LogBus.append("I/Routing: the network is now $type; the rules for it are in use")
        } else {
            VpnManager.onError(coreStartError())
            stopEverything()
        }
    }

    private fun reloadForGeoFiles() {
        if (!connected || kind != VpnManager.KIND_XRAY) return
        if (autoSelectSession) {
            LogBus.append("I/Routing: geo files arrived; reconnect to use the rules that need them")
            return
        }
        val plan = StartPlanner(this).planActive(allowProxyOnly = proxyOnly)
        val sameSession = plan is StartPlanner.Plan.Start && plan.kind == VpnManager.KIND_XRAY &&
            plan.proxyOnly == proxyOnly && plan.remark == remark &&
            plan.socksPort == (VpnManager.activeSocksPort ?: plan.socksPort)
        if (!sameSession) {
            LogBus.append("I/Routing: geo files arrived; reconnect to use the rules that need them")
            return
        }
        swappingCore = true
        val started = try {
            XrayController.start((plan as StartPlanner.Plan.Start).configJson)
        } finally {
            swappingCore = false
        }
        if (started) {
            LogBus.append("I/Routing: geo files loaded; every routing rule is in use now")
        } else {
            VpnManager.onError(coreStartError())
            stopEverything()
        }
    }

    private fun connectedNotification(
        downBps: Long,
        upBps: Long,
        totalDown: Long,
        totalUp: Long,
    ): android.app.Notification = notifications.build(
        server = remark,
        phase = VpnNotifications.Phase.Connected,
        protocolLabel = protocolLabel(),
        downBps = downBps,
        upBps = upBps,
        totalDown = totalDown,
        totalUp = totalUp,
        connectedAt = connectWhen,
        showSpeed = showSpeed,
        livePromotion = livePromotion,
        chip = notifChip,
    )

    private var lastUidRx = -1L
    private var lastUidTx = -1L

    private fun uidTrafficDelta(): Pair<Long, Long> {
        val uid = android.os.Process.myUid()
        val rx = android.net.TrafficStats.getUidRxBytes(uid)
        val tx = android.net.TrafficStats.getUidTxBytes(uid)
        val unsupported = android.net.TrafficStats.UNSUPPORTED.toLong()
        if (rx == unsupported || tx == unsupported) return 0L to 0L
        val delta = if (lastUidRx < 0) {
            0L to 0L
        } else {
            (rx - lastUidRx).coerceAtLeast(0) to (tx - lastUidTx).coerceAtLeast(0)
        }
        lastUidRx = rx
        lastUidTx = tx
        return delta
    }

    private val powerManager by lazy { getSystemService(POWER_SERVICE) as android.os.PowerManager }

    private fun startStatsLoop() {
        statsJob?.cancel()
        statsJob = scope.launch {
            val startedAt = android.os.SystemClock.elapsedRealtime()
            var ticks = 0
            var elapsed: Int
            var totalDown = 0L
            var totalUp = 0L

            var waitMs = dev.cluvex.zedsecure.domain.power.PowerPolicy.cadence(screenOn = true).tickMs
            var lastPollSec = 0
            var lastNotifySec = 0
            while (isActive) {
                val waited = waitMs
                delay(waited)
                val running = when (kind) {
                    VpnManager.KIND_PSIPHON -> psiphon?.isRunning == true
                    VpnManager.KIND_DNS_TUNNEL -> dnsTunnel?.isRunning == true
                    VpnManager.KIND_MASTERDNS -> masterDns?.isRunning == true
                    VpnManager.KIND_TOR -> tor?.isRunning == true
                    VpnManager.KIND_SSH -> ssh?.isRunning == true
                    VpnManager.KIND_OPENCONNECT -> openConnect?.isRunning == true
                    VpnManager.KIND_SINGBOX -> singBox?.isRunning == true
                    // swappingCore covers the optimizer's core swap
                    // blink, exactly as it covers Xray core swaps in
                    // the else branch: without it the stats loop
                    // would break out of the loop on the first tick
                    // after the old process was stopped, freezing
                    // the timer and traffic display for the session.
                    VpnManager.KIND_AETHER -> swappingCore || aetherProcess?.isAlive == true

                    VpnManager.KIND_CROSS_CHAIN ->
                        engineAlive(crossInnerKind, outer = false) && engineAlive(crossOuterKind, outer = true)
                    else -> swappingCore || XrayController.isRunning
                }
                if (!running) { onTunnelFailed(null); break }
                elapsed = ((android.os.SystemClock.elapsedRealtime() - startedAt) / 1000).toInt()
                ticks++
                val awake = runCatching { powerManager.isInteractive }.getOrDefault(true)
                val cadence = dev.cluvex.zedsecure.domain.power.PowerPolicy.cadence(screenOn = awake)
                waitMs = cadence.tickMs

                val pollEvery = cadence.autoPollSeconds
                if (autoSelectSession && elapsed - lastPollSec >= pollEvery) {
                    lastPollSec = elapsed
                    pollAutoSelect()
                }

                val (down, up) = when (kind) {
                    VpnManager.KIND_XRAY, VpnManager.KIND_SNISPOOF -> XrayController.readTrafficDelta()

                    VpnManager.KIND_PSIPHON -> uidTrafficDelta()
                    VpnManager.KIND_OPENCONNECT -> { openConnect?.pokeStats(); openConnect?.trafficDelta() ?: (0L to 0L) }

                    VpnManager.KIND_SINGBOX -> singBox?.let { engine ->
                        val down = (engine.downlinkTotal - singBoxLastDown).coerceAtLeast(0)
                        val up = (engine.uplinkTotal - singBoxLastUp).coerceAtLeast(0)
                        singBoxLastDown = engine.downlinkTotal
                        singBoxLastUp = engine.uplinkTotal
                        down to up
                    } ?: (0L to 0L)

                    else -> when (crossInnerKind) {
                        VpnManager.KIND_PSIPHON -> uidTrafficDelta()
                        VpnManager.KIND_DNS_TUNNEL, VpnManager.KIND_MASTERDNS,
                        VpnManager.KIND_TOR, VpnManager.KIND_SSH ->
                            socksShim?.readDelta() ?: (0L to 0L)
                        else -> XrayController.readTrafficDelta()
                    }
                }
                totalDown += down
                totalUp += up

                val seconds = (waited / 1000L).coerceAtLeast(1L)
                VpnManager.onMetrics(elapsed, down / seconds, up / seconds, totalDown, totalUp)

                val repaint = elapsed - lastNotifySec >= cadence.notifySeconds
                if (repaint) {
                    lastNotifySec = elapsed
                    if (showSpeed) notify(connectedNotification(down / seconds, up / seconds, totalDown, totalUp))
                    ZedWidgetProvider.refresh(this@ZedVpnService)
                }
            }
        }
    }

    private fun notify(notification: android.app.Notification) {
        if (!notificationLive) return
        getSystemService(NotificationManager::class.java)?.notify(VpnNotifications.NOTIFICATION_ID, notification)
    }

    private fun VpnService.Builder.applyAppRules(settings: AppSettings, selfInTunnel: Boolean = false) {
        val self = packageName
        if (!settings.perAppProxyEnabled || settings.perAppPackages.isEmpty()) {
            if (!selfInTunnel) runCatching { addDisallowedApplication(self) }
            return
        }
        if (settings.perAppBypassMode) {
            (settings.perAppPackages + self).forEach { pkg ->
                runCatching { addDisallowedApplication(pkg) }
            }
        } else {
            settings.perAppPackages.forEach { pkg ->
                if (pkg == self && !selfInTunnel) return@forEach
                runCatching { addAllowedApplication(pkg) }
            }
            if (selfInTunnel && self !in settings.perAppPackages) {
                runCatching { addAllowedApplication(self) }
            }
        }
    }

    private fun sniSpoofEdgeIp(configJson: String): String? = sniSpoofEdgeIpOrNull(configJson).also {
        if (it == null) Log.w(TAG, "SNI-spoof: could not resolve the edge IP; it will NOT be excluded")
        else Log.i(TAG, "SNI-spoof edge resolved to $it")
    }

    private fun sniSpoofEdgeIpOrNull(configJson: String): String? = runCatching {
        val wrapper = JSONObject(configJson)
        val sni = wrapper.optJSONObject("sni") ?: return null
        val clean = sni.optString("cleanIp").orEmpty()
        if (clean.isNotBlank()) return clean
        val xray = wrapper.optJSONObject("xray") ?: return null
        val host = readProxyOutbound(xray)?.first ?: return null
        java.net.InetAddress.getByName(host).hostAddress
    }.getOrNull()

    private fun VpnService.Builder.addLanAwareRoutes(
        bypassLan: Boolean,
        ipv6: Boolean,
        excludeIp: String? = null,
    ) {
        if (excludeIp != null && Build.VERSION.SDK_INT >= 33) {
            val excluded = runCatching {
                excludeRoute(android.net.IpPrefix(java.net.InetAddress.getByName(excludeIp), 32))
                true
            }.getOrDefault(false)
            if (excluded) {
                Log.i(TAG, "excludeRoute: SNI-spoof edge $excludeIp kept off the tunnel")
                if (bypassLan) {
                    LAN_BYPASS_ROUTES.forEach { cidr ->
                        val slash = cidr.indexOf('/')
                        runCatching { addRoute(cidr.substring(0, slash), cidr.substring(slash + 1).toInt()) }
                    }
                } else {
                    addRoute("0.0.0.0", 0)
                }
                if (ipv6) runCatching { addRoute("::", 0) }
                return
            }
        }

        if (!bypassLan && excludeIp != null) {
            val split = RouteSplit.defaultExcluding(excludeIp)
            if (split.isNotEmpty()) {
                Log.i(TAG, "routing around SNI-spoof edge $excludeIp (${split.size} routes)")
                split.forEach { (addr, prefix) -> runCatching { addRoute(addr, prefix) } }
                if (ipv6) runCatching { addRoute("::", 0) }
                return
            }
        }
        if (bypassLan) {
            LAN_BYPASS_ROUTES.forEach { cidr ->
                val slash = cidr.indexOf('/')
                runCatching { addRoute(cidr.substring(0, slash), cidr.substring(slash + 1).toInt()) }
            }
        } else {
            addRoute("0.0.0.0", 0)
        }
        if (!ipv6) return
        if (bypassLan) {
            addRoute("2000::", 3)
            addRoute("fc00::", 18)
        } else {
            addRoute("::", 0)
        }
    }

    private fun shouldBypassLan(settings: AppSettings, configJson: String, kind: String): Boolean =
        when (settings.vpnBypassLan) {
            VpnBypassLan.Bypass -> true
            VpnBypassLan.NotBypass -> false
            VpnBypassLan.FollowConfig ->
                if (kind == VpnManager.KIND_XRAY || kind == VpnManager.KIND_SNISPOOF) {
                    configRoutesPrivateDirect(configJson)
                } else {
                    RoutingMigration.effectiveRulesets(settings).any { rule ->
                        rule.enabled && rule.outboundTag == RulesetItem.OUTBOUND_DIRECT &&
                            (rule.domain.any(::isPrivateMatcher) || rule.ip.any(::isPrivateMatcher))
                    }
                }
        }

    private fun configRoutesPrivateDirect(configJson: String): Boolean = runCatching {
        val root = JSONObject(configJson)

        val xray = root.optJSONObject("xray") ?: root
        val rules = xray.optJSONObject("routing")?.optJSONArray("rules") ?: return false
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            if (rule.optString("outboundTag") != "direct") continue
            for (key in arrayOf("domain", "ip")) {
                val arr = rule.optJSONArray(key) ?: continue
                for (j in 0 until arr.length()) {
                    if (isPrivateMatcher(arr.optString(j))) return true
                }
            }
        }
        false
    }.getOrDefault(false)

    private fun isPrivateMatcher(entry: String): Boolean =
        entry == "geoip:private" || entry == "geosite:private" ||
            entry == "192.168.0.0/16" || entry == "10.0.0.0/8" || entry == "172.16.0.0/12"

    private fun VpnService.Builder.addDnsServersFrom(configJson: String) {
        var added = false
        runCatching {
            val dns = JSONObject(configJson).optJSONObject("dns") ?: return@runCatching
            val servers = dns.optJSONArray("servers") ?: return@runCatching
            for (i in 0 until servers.length()) {
                val entry = servers.opt(i)
                val addr = when (entry) {
                    is String -> entry
                    is JSONObject -> entry.optString("address").ifBlank { null }
                    else -> null
                }
                if (addr != null && !addr.startsWith("http")) {
                    runCatching { addDnsServer(addr); added = true }
                }
            }
        }
        if (!added) {
            runCatching { addDnsServer("1.1.1.1") }
            runCatching { addDnsServer("8.8.8.8") }
        }
    }

    private fun stopEngines() {
        geoReloadJob?.cancel()
        geoReloadJob = null
        statsJob?.cancel()
        stopNetworkTypeWatch()
        stopAutoSelectSession()
        psiphon?.stop(); psiphon = null
        dnsTunnel?.stop(); dnsTunnel = null
        masterDns?.stop(); masterDns = null
        tor?.stop(); tor = null
        ssh?.stop(); ssh = null
        sniSpoof?.stop(); sniSpoof = null
        openConnect?.stop(); openConnect = null
        singBox?.stop(); singBox = null
        stopAether()
        socksShim?.stop(); socksShim = null
        outerPsiphon?.stop(); outerPsiphon = null
        outerTor?.stop(); outerTor = null
        outerSsh?.stop(); outerSsh = null
        outerDnsTunnel?.stop(); outerDnsTunnel = null
        ZepTunCore.stop()
        HevTunCore.stop()
        XrayController.stop()
    }

    private fun resetSessionState() {
        // A fresh start replaces any previous session. If one is still up
        // (e.g. a duplicate CMD_START raced the running session), tear it
        // down first with the same helpers the stop path uses: otherwise
        // startTunnel overwrites the controller/tun fields and the old
        // engines keep their ports with nothing left able to stop them,
        // and the old tun leaks (startTunnel only closes the held tun on
        // the reconnect path). On a genuinely fresh service this is a
        // no-op: no tun was ever established and every stop() is null-safe.
        val oldTun = tun
        tun = null
        val hadSession = oldTun != null || connected ||
            psiphon != null || dnsTunnel != null || masterDns != null || tor != null ||
            ssh != null || sniSpoof != null || openConnect != null || singBox != null ||
            socksShim != null || outerPsiphon != null || outerTor != null ||
            outerSsh != null || outerDnsTunnel != null ||
            engineAlive(kind, outer = false)
        if (hadSession) {
            runCatching { stopEngines() }
            runCatching { oldTun?.close() }
        }
        statsJob?.cancel()
        statsJob = null
        connected = false
        connectWhen = 0L
        crossInnerKind = null

        lastUidRx = -1L
        lastUidTx = -1L
    }

    private val tearingDown = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun stopEverything() {
        if (reconnecting && !userStop && killSwitchEnabled && ensureKillSwitchTun()) {
            holdTraffic(null)
            return
        }
        stopHoldWatch()
        reconnecting = false
        // A user stop tears the kill-switch hold down fully: the flag
        // must not survive the stop, or a later failure callback could
        // mistake this dead service for a live hold.
        killSwitchActive = false
        notificationLive = false
        val tunToClose = tun
        tun = null
        connected = false
        VpnManager.onDisconnected(keepError = !userStop)
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        // Belt and braces: stopForeground removes the foreground
        // notification, but a plain notify() update to the same id from
        // a racing callback must not leave an orphan behind either.
        runCatching {
            getSystemService(NotificationManager::class.java)?.cancel(VpnNotifications.NOTIFICATION_ID)
        }
        // The sibling services own their own notifications and tunnels:
        // stopping here must stop them too, or their notification (and
        // tunnel) outlives this service's stop. Both stops are
        // runCatching no-ops when the sibling is not running, and the
        // plan-switch branches start the sibling only after this
        // returns, so a fresh start is never killed by its own stop.
        runCatching { dev.cluvex.zedsecure.foxy.vpn.FoxyVpnService.stop(this) }
        runCatching { SstpBridge.stop(this) }
        ZedWidgetProvider.refresh(this, force = true)

        if (Looper.myLooper() != Looper.getMainLooper()) {
            stopEngines()
            runCatching { tunToClose?.close() }
            stopSelf()
            return
        }
        if (!tearingDown.compareAndSet(false, true)) return

        Thread({
            runCatching { stopEngines() }
            runCatching { tunToClose?.close() }
            tearingDown.set(false)
            stopSelf()
        }, "zed-teardown").apply { isDaemon = false }.start()
    }

    private fun armTorBootstrapDeadline(bridged: java.util.concurrent.atomic.AtomicBoolean) {
        scope.launch {
            kotlinx.coroutines.delay(TOR_BOOTSTRAP_TIMEOUT_MS)
            if (!bridged.get() && VpnManager.status.value.state.isTransitioning) {
                LogBus.append("E/Tor bootstrap timed out after ${TOR_BOOTSTRAP_TIMEOUT_MS / 1000}s")
                onTunnelFailed(getString(R.string.tor_bootstrap_timeout))
            }
        }
    }

    private fun ensureKillSwitchTun(): Boolean {
        if (tun != null) return true
        return runCatching {
            val b = Builder()
                .setSession(getString(R.string.kill_switch))
                .setMtu(1400)
                .addAddress("10.111.222.1", 32)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)

            runCatching { b.addDisallowedApplication(packageName) }
            tun = b.establish()
            tun != null
        }.getOrElse {
            Log.w(TAG, "kill-switch tun failed", it)
            false
        }
    }

    private fun onTunnelFailed(reason: String?) {
        if (userStop || killSwitchActive || swappingCore) return
        if (killSwitchEnabled && (connected || reconnecting) && ensureKillSwitchTun()) {
            holdTraffic(reason)
        } else {
            if (reason != null) VpnManager.onError(reason)
            stopEverything()
        }
    }

    private fun holdTraffic(reason: String?) {
        killSwitchActive = true
        reconnecting = false
        connected = false
        stopEngines()
        ensureKillSwitchTun()
        VpnManager.onError(reason ?: getString(R.string.kill_switch))
        runCatching {
            notify(
                notifications.build(
                    server = remark,
                    phase = VpnNotifications.Phase.Failed(
                        reason = getString(R.string.notif_kill_switch_body),
                    ),
                    protocolLabel = protocolLabel(),
                ),
            )
        }
        ZedWidgetProvider.refresh(this, force = true)
        watchForNetwork()
        scheduleRetry(retryDelayMs)
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(RETRY_MAX_MS)
    }

    private fun watchForNetwork() {
        if (holdWatch != null) return
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val request = android.net.NetworkRequest.Builder()
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                val fresh = holdNetworks.add(network)
                if (fresh) networkCameBack()
            }

            override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) {
                if (caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) && holdNetworks.add(network)) {
                    networkCameBack()
                }
            }

            override fun onLost(network: android.net.Network) {
                holdNetworks.remove(network)
            }
        }
        holdNetworks.clear()
        runCatching { cm.registerNetworkCallback(request, callback) }
            .onSuccess { holdWatch = callback }
            .onFailure { Log.w(TAG, "kill-switch network watch unavailable", it) }
    }

    private fun networkCameBack() {
        if (!killSwitchActive || userStop || reconnecting) return
        retryDelayMs = RETRY_FIRST_MS
        scheduleRetry(NETWORK_SETTLE_MS)
    }

    private fun stopHoldWatch() {
        retryJob?.cancel()
        retryJob = null
        holdWatch?.let { cb ->
            runCatching { getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        }
        holdWatch = null
        holdNetworks.clear()
    }

    private fun scheduleRetry(delayMs: Long) {
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(delayMs)
            retryFromHold()
        }
    }

    private fun retryFromHold() {
        if (userStop || !killSwitchActive || reconnecting) return
        if (holdWatch != null && holdNetworks.isEmpty()) {
            LogBus.append("I/Kill switch: no network yet; reconnecting once one is back")
            return
        }
        LogBus.append("I/Kill switch: reconnecting")
        reconnecting = true
        stage = VpnNotifications.Stage.Preparing
        runCatching { notify(connectingNotification()) }
        when (val plan = StartPlanner(this).planActive(allowProxyOnly = false)) {
            is StartPlanner.Plan.Start -> {
                VpnManager.onStarting(plan.remark)
                remark = plan.remark
                kind = plan.kind
                proxyOnly = plan.proxyOnly
                startTunnel(plan.configJson, plan.socksPort, plan.kind)
            }
            is StartPlanner.Plan.Ikev2 -> holdTraffic(getString(R.string.engine_ikev2_needs_app))
            is StartPlanner.Plan.Foxy -> {
                stopEverything()
                VpnManager.onStarting(plan.remark)
                dev.cluvex.zedsecure.foxy.vpn.FoxyVpnService.start(this@ZedVpnService, plan.remark)
            }
            is StartPlanner.Plan.Sstp -> {
                stopEverything()
                VpnManager.onStarting(plan.remark)
                dev.cluvex.zedsecure.core.SstpBridge.start(this@ZedVpnService, plan.profile)
                dev.cluvex.zedsecure.core.SstpBridge.watch(
                    kotlinx.coroutines.MainScope(),
                    this@ZedVpnService,
                    plan.remark,
                )
            }
            is StartPlanner.Plan.Failure -> holdTraffic(plan.message)
        }
    }

    override fun onRevoke() {
        userStop = true
        stopEverything()
    }

    override fun onDestroy() {
        XrayController.onCoreShutdown = null
        // Run the same teardown as the normal stop path so the engines are
        // stopped and the tun is closed even when the system destroys the
        // service without a CMD_STOP/onRevoke first (kill/restart). Setting
        // userStop keeps the kill-switch hold from intercepting the
        // teardown, and stopEverything() itself guards against running
        // twice when a stop is already in flight.
        userStop = true
        runCatching { stopEverything() }
        // stopEverything() removes the notification synchronously, but a
        // destroy must never leave one behind even if that call raced a
        // teardown already in flight: cancel the id outright.
        notificationLive = false
        runCatching {
            getSystemService(NotificationManager::class.java)?.cancel(VpnNotifications.NOTIFICATION_ID)
        }
        // stopEverything() already reaches stopAether() via stopEngines(),
        // but it may hand teardown to a background thread; stop the Aether
        // process directly here too so a service destroy never leaks it.
        runCatching { stopAether() }
        scope.cancel()
        super.onDestroy()
    }

    override fun dump(fd: java.io.FileDescriptor, writer: java.io.PrintWriter, args: Array<out String>?) {
        writer.println("engine=$kind tun=${tun != null} core=${XrayController.isRunning}")
        writer.println("crypto=" + XrayController.cryptoAcceleration())
        writer.println(ZepTunCore.stats())
        writer.println()
        writer.println(XrayController.runtimeReport(if (args?.contains("all") == true) 40 else 12))
    }

    private fun startInForeground(notification: android.app.Notification) {
        notificationLive = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(VpnNotifications.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(VpnNotifications.NOTIFICATION_ID, notification)
        }
    }

    private fun crossChainHasDnsTunnel(wrapper: String): Boolean = runCatching {
        val o = JSONObject(wrapper)
        val dns = setOf(VpnManager.KIND_DNS_TUNNEL, VpnManager.KIND_MASTERDNS)
        o.optString("innerKind") in dns || o.optString("outerKind") in dns
    }.getOrDefault(false)

    private fun engineAlive(k: String?, outer: Boolean): Boolean = when (k) {
        VpnManager.KIND_PSIPHON -> (if (outer) outerPsiphon else psiphon)?.isRunning == true
        VpnManager.KIND_DNS_TUNNEL -> (if (outer) outerDnsTunnel else dnsTunnel)?.isRunning == true
        VpnManager.KIND_MASTERDNS -> masterDns?.isRunning == true
        VpnManager.KIND_TOR -> (if (outer) outerTor else tor)?.isRunning == true
        VpnManager.KIND_SSH -> (if (outer) outerSsh else ssh)?.isRunning == true
        else -> XrayController.isRunning
    }

    private fun startCrossChain(
        wrapper: String,
        descriptor: ParcelFileDescriptor,
        tunMtu: Int,
        settings: AppSettings,
    ) {
        val parsed = runCatching { JSONObject(wrapper) }.getOrNull()
        if (parsed == null) {
            VpnManager.onError(getString(R.string.config_invalid)); stopEverything(); return
        }
        val innerKind = parsed.optString("innerKind")
        val outerKind = parsed.optString("outerKind")
        val innerConfig = parsed.optString("inner")
        val outerConfig = parsed.optString("outer")
        crossInnerKind = innerKind
        crossOuterKind = outerKind
        Log.i(TAG, "cross chain: $innerKind through $outerKind")

        startCrossOuter(outerKind, outerConfig, settings) { carrierPort ->
            if (carrierPort <= 0) {
                VpnManager.onError(getString(R.string.crosschain_outer_failed))
                stopEverything()
            } else {
                Log.i(TAG, "cross chain: carrier up on $carrierPort")
                startCrossInner(innerKind, innerConfig, carrierPort, descriptor, tunMtu, settings)
            }
        }
    }

    private fun startCrossOuter(
        outerKind: String,
        config: String,
        settings: AppSettings,
        onReady: (Int) -> Unit,
    ) {
        when (outerKind) {
            VpnManager.KIND_PSIPHON -> {
                val bridged = java.util.concurrent.atomic.AtomicBoolean(false)
                val c = PsiphonController(
                    service = this,
                    configJson = config,
                    onSocksPort = { },

                    onEstablished = {
                        if (bridged.compareAndSet(false, true)) onReady(LocalPorts.PSIPHON_SOCKS)
                    },
                    onStopped = { reason -> onTunnelFailed(reason) },
                )
                outerPsiphon = c
                if (!c.start()) { onReady(-1) }
            }
            VpnManager.KIND_TOR -> {
                val bridged = java.util.concurrent.atomic.AtomicBoolean(false)
                val c = dev.cluvex.zedsecure.core.tor.TorController(
                    context = this,
                    settings = settings,
                    onBootstrapped = {
                        if (bridged.compareAndSet(false, true)) {
                            onReady(dev.cluvex.zedsecure.core.tor.TorConfigBuilder.SOCKS_PORT)
                        }
                    },
                    onProgress = { pct -> LogBus.append("I/Tor bootstrap $pct%") },
                    onStopped = { reason -> onTunnelFailed(reason) },
                )
                outerTor = c
                if (!c.start()) onReady(-1) else armTorBootstrapDeadline(bridged)
            }
            VpnManager.KIND_SSH -> {
                val p = runCatching {
                    kotlinx.serialization.json.Json.decodeFromString(
                        dev.cluvex.zedsecure.domain.config.SshProfile.serializer(), config,
                    )
                }.getOrNull() ?: return onReady(-1)
                scope.launch {
                    val c = SshController(
                        profile = p,
                        cipher = settings.sshCipher,
                        compression = settings.sshCompression,
                        listenPort = LocalPorts.SSH,
                        maxListenPort = LocalPorts.SSH_MAX,
                    )
                    outerSsh = c
                    onReady(c.start())
                }
            }
            VpnManager.KIND_DNS_TUNNEL -> {
                val p = runCatching {
                    kotlinx.serialization.json.Json.decodeFromString(
                        dev.cluvex.zedsecure.domain.config.DnsTunnelProfile.serializer(), config,
                    )
                }.getOrNull() ?: return onReady(-1)
                scope.launch {
                    val c = DnsTunnelController(
                        p,
                        listenPort = LocalPorts.DNS_TUNNEL,
                        maxListenPort = LocalPorts.DNS_TUNNEL_MAX,
                        pool = dnsPoolOf(settings),
                        poolFullVerification = settings.dnsPoolFullVerification,
                    )
                    outerDnsTunnel = c
                    onReady(c.start())
                }
            }

            else -> {
                if (!XrayController.start(config)) {
                    VpnManager.onError(coreStartError())
                    onReady(-1)
                } else {
                    onReady(LocalPorts.XRAY_SOCKS)
                }
            }
        }
    }

    private fun startCrossInner(
        innerKind: String,
        config: String,
        carrierPort: Int,
        descriptor: ParcelFileDescriptor,
        tunMtu: Int,
        settings: AppSettings,
    ) {
        val dns = if (settings.remoteDnsMode == "custom") {
            settings.remoteDnsPrimary.ifBlank { "8.8.8.8" }
        } else "8.8.8.8"

        fun bridgeThroughShim(upstreamPort: Int) {
            if (upstreamPort <= 0) {
                VpnManager.onError(getString(R.string.crosschain_inner_failed)); stopEverything(); return
            }
            val shim = SocksTunBridge("127.0.0.1", LocalPorts.SHIM, "127.0.0.1", upstreamPort, dnsHost = dns)
            socksShim = shim
            if (shim.start() && bridge(descriptor, LocalPorts.SHIM, tunMtu, udpOverTcp = true)) {
                finishConnected()
            } else {
                stopEverything()
            }
        }

        when (innerKind) {
            VpnManager.KIND_DNS_TUNNEL -> {
                val p = runCatching {
                    kotlinx.serialization.json.Json.decodeFromString(
                        dev.cluvex.zedsecure.domain.config.DnsTunnelProfile.serializer(), config,
                    )
                }.getOrNull()
                if (p == null) { VpnManager.onError(getString(R.string.config_invalid)); stopEverything(); return }
                scope.launch {
                    val c = DnsTunnelController(
                        p,
                        listenPort = LocalPorts.DNS_TUNNEL,
                        maxListenPort = LocalPorts.DNS_TUNNEL_MAX,
                        pool = dnsPoolOf(settings),
                        poolFullVerification = settings.dnsPoolFullVerification,
                        upstreamSocks = "127.0.0.1:$carrierPort",
                        upstreamSocksUser = "",
                        upstreamSocksPass = "",
                    )
                    dnsTunnel = c
                    bridgeThroughShim(c.start())
                }
            }
            VpnManager.KIND_SSH -> {
                val p = runCatching {
                    kotlinx.serialization.json.Json.decodeFromString(
                        dev.cluvex.zedsecure.domain.config.SshProfile.serializer(), config,
                    )
                }.getOrNull()
                if (p == null) { VpnManager.onError(getString(R.string.config_invalid)); stopEverything(); return }
                scope.launch {
                    val c = SshController(
                        profile = p,
                        cipher = settings.sshCipher,
                        compression = settings.sshCompression,
                        listenPort = LocalPorts.SSH_OVER_DNS,
                        maxListenPort = LocalPorts.SSH_OVER_DNS_MAX,
                        proxySocksHost = "127.0.0.1",
                        proxySocksPort = carrierPort,
                    )
                    ssh = c
                    bridgeThroughShim(c.start())
                }
            }
            VpnManager.KIND_TOR -> {
                val bridged = java.util.concurrent.atomic.AtomicBoolean(false)
                val c = dev.cluvex.zedsecure.core.tor.TorController(
                    context = this,
                    settings = settings,
                    onBootstrapped = {
                        if (bridged.compareAndSet(false, true)) {
                            bridgeThroughShim(dev.cluvex.zedsecure.core.tor.TorConfigBuilder.SOCKS_PORT)
                        }
                    },
                    onProgress = { pct -> LogBus.append("I/Tor bootstrap $pct%") },
                    onStopped = { reason -> onTunnelFailed(reason) },
                    upstreamSocksPort = carrierPort,
                )
                tor = c
                if (!c.start()) stopEverything() else armTorBootstrapDeadline(bridged)
            }
            VpnManager.KIND_PSIPHON -> {
                val patched = runCatching {
                    dev.cluvex.zedsecure.domain.config.PsiphonConfigBuilder
                        .withUpstreamProxy(config, carrierPort)
                }.getOrNull()
                if (patched == null) { VpnManager.onError(getString(R.string.config_invalid)); stopEverything(); return }
                val bridged = java.util.concurrent.atomic.AtomicBoolean(false)
                val c = PsiphonController(
                    service = this,
                    configJson = patched,
                    onSocksPort = { },
                    onEstablished = {
                        if (bridged.compareAndSet(false, true) &&
                            bridge(descriptor, LocalPorts.PSIPHON_SOCKS, tunMtu)
                        ) {
                            VpnManager.activeSocksPort = LocalPorts.PSIPHON_SOCKS
                            finishConnected()
                        }
                    },
                    onStopped = { reason -> onTunnelFailed(reason) },
                )
                psiphon = c
                if (!c.start()) stopEverything()
            }

            else -> {
                val patched = runCatching {
                    dev.cluvex.zedsecure.domain.config.XrayJsonBuilder
                        .withCarrierProxy(config, carrierPort)
                }.getOrNull()
                if (patched == null) {
                    VpnManager.onError(getString(R.string.config_invalid)); stopEverything(); return
                }
                if (!XrayController.start(patched)) {
                    VpnManager.onError(coreStartError()); stopEverything(); return
                }

                if (bridge(descriptor, LocalPorts.XRAY_SOCKS, tunMtu, pipeline = true)) {
                    VpnManager.activeSocksPort = LocalPorts.XRAY_SOCKS
                    finishConnected()
                } else {
                    stopEverything()
                }
            }
        }
    }

    private fun dnsPoolOf(settings: AppSettings): List<String> =
        if (settings.dnsPoolEnabled) {
            settings.dnsPoolText.split('\n', ',').map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
        } else emptyList()

    companion object {
        private const val TAG = "ZedVpnService"

        private const val ZEPTUN_PROOF_MS = 6_000L

        private const val NETWORK_SETTLE_MS = 1_500L
        private const val RETRY_FIRST_MS = 3_000L
        private const val RETRY_MAX_MS = 60_000L

        private const val SHIM_PORT = LocalPorts.SHIM

        private const val MASTERDNS_IDLE_TIMEOUT_MS = 90_000L

        private const val MASTERDNS_MAX_WAIT_MS = 300_000L

        private const val TOR_BOOTSTRAP_TIMEOUT_MS = 120_000L

        private const val AETHER_READY_TIMEOUT_MS = 120_000L

        private const val AETHER_RESTART_READY_TIMEOUT_MS = 60_000L

        private const val AETHER_MAX_RESTARTS = 3

        /** Backoff between monitor relaunches: 3s, then 6s, then 12s.
         *  The previous fixed 1s hammered a struggling network; the
         *  bound stays 3 relaunches — only the spacing changes, and
         *  the status line names the wait (see aether_retry_status). */
        private val AETHER_RESTART_BACKOFF_MS = longArrayOf(3_000L, 6_000L, 12_000L)

        /** Optimizer: how long the session must be up before the
         *  background search starts (the incumbent must prove itself
         *  stable first). */
        private const val AETHER_OPTIMIZER_STABLE_MS = 30_000L

        /** Optimizer: earliest a switch may happen after connect, and
         *  the window inside which a switch is allowed at all. */
        private const val AETHER_OPTIMIZER_MIN_AGE_MS = 60_000L
        private const val AETHER_OPTIMIZER_WINDOW_MS = 5 * 60_000L

        /** Optimizer: an incumbent this fast is good enough — no
         *  challenger is probed and no switch happens. */
        private const val AETHER_OPTIMIZER_GOOD_ENOUGH_MS = 300L

        /** Optimizer: a challenger must beat the incumbent by this
         *  fraction on consecutive samples to earn a switch. */
        private const val AETHER_OPTIMIZER_BEAT_NUMERATOR = 7L
        private const val AETHER_OPTIMIZER_BEAT_DENOMINATOR = 10L

        /** Optimizer: most challengers probed in one session, and the
         *  probe budget each challenger gets. */
        private const val AETHER_OPTIMIZER_MAX_CHALLENGERS = 3
        private const val AETHER_OPTIMIZER_PROBE_TIMEOUT_MS = 45_000L

        /** Optimizer: trace samples taken through the live tunnel to
         *  judge the incumbent, and the gap between them. */
        private const val AETHER_OPTIMIZER_INCUMBENT_SAMPLES = 3
        private const val AETHER_OPTIMIZER_SAMPLE_GAP_MS = 3_000L

        /** Optimizer: port-up budget for the swapped-in core before
         *  rolling back to the incumbent. */
        private const val AETHER_OPTIMIZER_SWAP_READY_MS = 15_000L

        /** Settle delay after a network switch before restarting Aether:
         * a switch fires several callbacks; one restart is enough. */
        private const val AETHER_NETWORK_SETTLE_MS = 2_000L

        /** Minimum gap between network-change restarts (anti-flap). */
        private const val AETHER_NETWORK_MIN_INTERVAL_MS = 15_000L

        private const val SNISPOOF_PORT = LocalPorts.SNI_SPOOF
        private const val MTU = 1500

        private val LAN_BYPASS_ROUTES = listOf(
            "0.0.0.0/5", "8.0.0.0/7", "11.0.0.0/8", "12.0.0.0/6", "16.0.0.0/4",
            "32.0.0.0/3", "64.0.0.0/2", "128.0.0.0/3", "160.0.0.0/5", "168.0.0.0/6",
            "172.0.0.0/12", "172.32.0.0/11", "172.64.0.0/10", "172.128.0.0/9", "173.0.0.0/8",
            "174.0.0.0/7", "176.0.0.0/4", "192.0.0.0/9", "192.128.0.0/11", "192.160.0.0/13",
            "192.169.0.0/16", "192.170.0.0/15", "192.172.0.0/14", "192.176.0.0/12",
            "192.192.0.0/10", "193.0.0.0/8", "194.0.0.0/7", "196.0.0.0/6", "200.0.0.0/5",
            "208.0.0.0/4", "240.0.0.0/4",
        )
    }
}
