package dev.cluvex.zedsecure.ui

import dev.cluvex.zedsecure.ui.platform.BackHandler
import dev.cluvex.zedsecure.ui.home.HomeFabCluster
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cluvex.zedsecure.data.config.ConfigRepository
import dev.cluvex.zedsecure.shared.resources.Res
import dev.cluvex.zedsecure.shared.resources.auto_group_all
import dev.cluvex.zedsecure.shared.resources.auto_title_for
import dev.cluvex.zedsecure.shared.resources.tour_done
import dev.cluvex.zedsecure.shared.resources.tour_next
import dev.cluvex.zedsecure.shared.resources.tour_skip
import dev.cluvex.zedsecure.domain.model.AppLanguage
import dev.cluvex.zedsecure.domain.model.AppSettings
import dev.cluvex.zedsecure.domain.model.NavBarStyle
import dev.cluvex.zedsecure.domain.model.NavTabs
import dev.cluvex.zedsecure.ui.navigation.LogsTabPage
import dev.cluvex.zedsecure.ui.navigation.SubscriptionsTabPage
import dev.cluvex.zedsecure.ui.servers.ServersScreen
import dev.cluvex.zedsecure.ui.theme.toPersonalization
import dev.cluvex.zedsecure.ui.connection.ConnectionViewModel
import dev.cluvex.zedsecure.ui.easteregg.SpaceScreen
import dev.cluvex.zedsecure.ui.home.HomeScreen
import dev.cluvex.zedsecure.ui.motion.transitionDecoration
import dev.cluvex.zedsecure.ui.navigation.TopDestination
import dev.cluvex.zedsecure.ui.settings.SettingsScreen
import dev.cluvex.zedsecure.ui.settings.availableNavTabs
import dev.cluvex.zedsecure.ui.update.NudgeHost
import dev.cluvex.zedsecure.ui.vault.VaultImportHost

// NOTE (beta2): a name-based Saver for TopDestination was tried here
// (Opus's optional hardening: save the tab by enum name, not ordinal)
// but `rememberSaveable(saver = ...)` does not resolve in this Compose
// version — the call's type came out as Nothing and `by` delegation
// failed to compile (builds 42202009691 + 42203791843). Reverted to the
// proven upstream/beta1 pattern below; the ordinal-restore risk was
// never confirmed (enums restore by name in this setup).

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MainScaffold(
    settings: AppSettings,
    configRepository: ConfigRepository,
    onToggleConnection: () -> Unit,

    onActiveServerChanged: () -> Unit = {},
    onImportZsx: () -> Unit,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onLanguage: (AppLanguage) -> Unit,

    appVersion: String = "",
    onAetherAuto: (suspend (onProgress: (String) -> Unit) -> Result<dev.cluvex.zedsecure.domain.config.AetherProfile>)? = null,
    onAetherScan: (suspend (args: List<String>, onProgress: (String) -> Unit) -> Result<dev.cluvex.zedsecure.domain.config.AetherScanResult>)? = null,
    onAetherConnect: (() -> Unit)? = null,
) {
    val platform = dev.cluvex.zedsecure.ui.platform.LocalPlatform.current
    var current by rememberSaveable { mutableStateOf(TopDestination.Home) }

    var setupDone by rememberSaveable {
        mutableStateOf(settings.onboardingVersion >= dev.cluvex.zedsecure.ui.onboarding.ONBOARDING_VERSION)
    }
    var tourStep by rememberSaveable { mutableStateOf(-1) }
    var spaceUnlocked by rememberSaveable { mutableStateOf(false) }

    var deepLinkOrigin by rememberSaveable { mutableStateOf<TopDestination?>(null) }

    var pendingSettingsPage by rememberSaveable {
        mutableStateOf(dev.cluvex.zedsecure.ui.settings.SettingsPage.Root)
    }
    var serversAddRequest by rememberSaveable { mutableStateOf(false) }
    val bento = settings.appDesign == dev.cluvex.zedsecure.domain.model.AppDesign.Bento
    // Tabs the user picked in Settings -> Nav bar (NavTabs); the bar shows only
    // these, in this order. Home and Settings are always present.
    val tabs = androidx.compose.runtime.remember(settings.navTabs, platform.supportsPerAppProxy) {
        NavTabs.parse(settings.navTabs, availableNavTabs(platform.supportsPerAppProxy)).map { TopDestination.of(it) }
    }
    fun orderOf(destination: TopDestination) =
        tabs.indexOf(destination).takeIf { it >= 0 } ?: TopDestination.entries.indexOf(destination)
    val barStyle = if (bento) settings.bento.navStyle else settings.navBarStyle
    val dock = bento && barStyle == NavBarStyle.Dock
    var dockShown by rememberSaveable { mutableStateOf(true) }
    androidx.compose.runtime.LaunchedEffect(current) { dockShown = true }
    val autoHide = settings.bento.dockAutoHide
    val dockScroll = androidx.compose.runtime.remember(autoHide) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPostScroll(
                consumed: androidx.compose.ui.geometry.Offset,
                available: androidx.compose.ui.geometry.Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
            ): androidx.compose.ui.geometry.Offset {
                if (autoHide) {
                    if (consumed.y < -4f) dockShown = false else if (consumed.y > 4f || available.y > 24f) dockShown = true
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }
    val connectionVm: ConnectionViewModel = viewModel { ConnectionViewModel() }
    val homeVm: dev.cluvex.zedsecure.ui.home.HomeViewModel = viewModel { dev.cluvex.zedsecure.ui.home.HomeViewModel() }
    val effectsSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()

    val profiles by configRepository.profiles.collectAsStateWithLifecycle()
    val subscriptions by configRepository.subscriptions.collectAsStateWithLifecycle()
    val activeId by configRepository.activeId.collectAsStateWithLifecycle()

    val activeProfile = androidx.compose.runtime.remember(profiles, subscriptions, activeId) {
        activeId?.let { configRepository.profile(it) }
    }
    val autoSession by dev.cluvex.zedsecure.core.AutoSelect.session.collectAsStateWithLifecycle()

    val lockedNote = activeProfile?.takeIf { it.isLocked }?.note

    val mtuHint = androidx.compose.runtime.remember(activeProfile) {
        val parsed = activeProfile?.linkServer
        val host = parsed?.address?.takeIf { it.isNotBlank() } ?: activeProfile?.address.orEmpty()
        dev.cluvex.zedsecure.data.net.MtuServerHint(
            host = host,
            overhead = parsed
                ?.let { dev.cluvex.zedsecure.data.net.MtuOverheads.ofServer(it) }
                ?: dev.cluvex.zedsecure.data.net.MtuOverheads.worstCase(),
        )
    }

    VaultImportHost(
        repository = configRepository,
        onImported = { current = TopDestination.Servers },
    )

    dev.cluvex.zedsecure.ui.servers.DeepLinkImportHost(
        repository = configRepository,
        onImported = { current = TopDestination.Servers },
    )

    NudgeHost(settings = settings, onUpdateSettings = onUpdateSettings)

    BackHandler(enabled = current != TopDestination.Home) { current = TopDestination.Home }

    BackHandler(enabled = tourStep >= 0) {
        tourStep = -1
        current = TopDestination.Home
    }

    if (spaceUnlocked) {
        SpaceScreen(onExit = { spaceUnlocked = false })
        return
    }

    if (!setupDone) {
        dev.cluvex.zedsecure.ui.onboarding.OnboardingFlow(
            settings = settings,
            onUpdate = onUpdateSettings,
            onLanguage = onLanguage,
            onStartTour = {
                onUpdateSettings {
                    it.copy(onboardingVersion = dev.cluvex.zedsecure.ui.onboarding.ONBOARDING_VERSION)
                }
                setupDone = true
                current = TopDestination.Home
                tourStep = 0
            },
            onFinish = {
                onUpdateSettings {
                    it.copy(onboardingVersion = dev.cluvex.zedsecure.ui.onboarding.ONBOARDING_VERSION)
                }
                setupDone = true
            },
        )
        return
    }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        bottomBar = {
            if (!dock) {
                dev.cluvex.zedsecure.ui.navigation.ZedNavBar(
                    style = barStyle,
                    current = current,
                    onSelect = { current = it },
                    tabs = tabs,
                )
            }
        },
    ) { scaffoldPadding ->
      val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
      val innerPadding = if (dock) {
          PaddingValues(
              start = scaffoldPadding.calculateStartPadding(layoutDirection),
              top = scaffoldPadding.calculateTopPadding(),
              end = scaffoldPadding.calculateEndPadding(layoutDirection),
              bottom = scaffoldPadding.calculateBottomPadding() + dev.cluvex.zedsecure.ui.bento.BentoDockHeight +
                  dev.cluvex.zedsecure.ui.bento.BentoDockMargin + 8.dp,
          )
      } else {
          scaffoldPadding
      }
      androidx.compose.foundation.layout.Box(
          Modifier
              .fillMaxSize()
              .then(if (dock) Modifier.nestedScroll(dockScroll) else Modifier),
      ) {
        val sign = dev.cluvex.zedsecure.ui.motion.layoutSign()
        val serverTargets = profiles
            .filter { it.standardLink != null }
            .map { it.id to it.name }
        val autoCountries = androidx.compose.runtime.remember(profiles) {
            profiles.mapNotNull { dev.cluvex.zedsecure.domain.config.ProfileCountry.of(it) }.distinct()
        }
        val groupTargets = listOf(
            dev.cluvex.zedsecure.domain.config.AutoSelectIds.ALL to
                stringResource(Res.string.auto_title_for, stringResource(Res.string.auto_group_all)),
        ) + subscriptions.map { sub ->
            dev.cluvex.zedsecure.domain.config.AutoSelectIds.of(sub.id) to stringResource(Res.string.auto_title_for, sub.name)
        }
        AnimatedContent(
            targetState = current,
            transitionSpec = {
                dev.cluvex.zedsecure.ui.motion.screenTransition(
                    style = settings.screenTransition,
                    forward = orderOf(targetState) >= orderOf(initialState),
                    direction = sign,
                    reduceMotion = settings.reduceMotion,
                ).using(SizeTransform(clip = false))
            },
            label = "top-destination",
            modifier = Modifier.fillMaxSize(),
        ) { dest ->

            val decoration = transitionDecoration(
                style = settings.screenTransition,
                forward = orderOf(dest) >= orderOf(current),
                direction = sign,
                reduceMotion = settings.reduceMotion,
            )
            Box(decoration) {
            when (dest) {
                TopDestination.Home -> {
                  val homeName = activeProfile?.let { p ->
                      if (p.isAutoSelect) {
                          stringResource(
                              Res.string.auto_title_for,
                              dev.cluvex.zedsecure.ui.servers.autoSelectGroupLabel(p),
                          )
                      } else p.name
                  }
                  val homeDetail = activeProfile?.takeIf { it.isAutoSelect }?.let { p ->
                      autoSession?.takeIf { it.profileId == p.id }?.let { session ->
                          dev.cluvex.zedsecure.ui.servers.autoSelectLiveLine(session) { id ->
                              profiles.firstOrNull { it.id == id }?.name
                          }
                      }
                  }
                  val activeSubscription = activeProfile?.let { p ->
                      p.subscriptionId.ifBlank { p.autoSelectSettings()?.subscriptionId.orEmpty() }
                          .takeIf { it.isNotBlank() }
                          ?.let { id -> subscriptions.firstOrNull { it.id == id } }
                  }
                  HomeScreen(
                    connectionVm = connectionVm,
                    homeVm = homeVm,
                    activeConfigName = homeName,
                    activeSubscription = activeSubscription,
                    exitGeneration = autoSession?.takeIf { it.profileId == activeProfile?.id }?.exitGeneration ?: 0,
                    activeConfigDetail = homeDetail,
                    lockedNote = lockedNote,
                    localProxyAddress = if (settings.runMode == dev.cluvex.zedsecure.domain.model.RunMode.ProxyOnly) {
                        "127.0.0.1:${dev.cluvex.zedsecure.core.VpnManager.activeSocksPort ?: dev.cluvex.zedsecure.domain.config.LocalProxy.SOCKS_PORT}"
                    } else null,
                    activeLocked = activeProfile?.isLocked == true,
                    reduceMotion = settings.reduceMotion,

                    showConnectionInfo = activeProfile?.isDnsTunnel != true,
                    ipApiUrl = settings.ipApiUrl,
                    delayTestUrl = settings.delayTestUrl,

                    personalization = settings.toPersonalization(),
                    connectStyle = settings.connectButtonStyle,
                    design = settings.appDesign,
                    contentPadding = innerPadding,
                    onToggleConnection = onToggleConnection,

                    onBrowseConfigs = { current = TopDestination.Servers },
                    onSecretUnlocked = { spaceUnlocked = true },
                    bentoActive = activeProfile?.let { p ->
                        dev.cluvex.zedsecure.ui.bento.BentoActiveServer(
                            name = homeName ?: p.name,
                            detail = homeDetail ?: p.transportLabel.removeSuffix(" · sing-box"),
                            country = if (settings.detectServerLocation) dev.cluvex.zedsecure.domain.config.ProfileCountry.of(p) else null,
                            protocol = p.protocol,
                            engine = dev.cluvex.zedsecure.ui.bento.bentoEngineOf(p),
                            locked = p.isLocked,
                            subscription = activeSubscription,
                        )
                    },
                    bentoPicks = androidx.compose.runtime.remember(profiles, activeId, settings.detectServerLocation) {
                        profiles
                            .filter { !it.isLocked && it.id != activeId && (it.lastPingMs ?: 0) > 0 }
                            .sortedBy { it.lastPingMs }
                            .take(3)
                            .map {
                                dev.cluvex.zedsecure.ui.bento.BentoServerPick(
                                    id = it.id,
                                    name = it.name,
                                    detail = it.transportLabel.removeSuffix(" · sing-box"),
                                    country = if (settings.detectServerLocation) dev.cluvex.zedsecure.domain.config.ProfileCountry.of(it) else null,
                                    protocol = it.protocol,
                                    pingMs = it.lastPingMs,
                                )
                            }
                    },
                    onPickServer = { id ->
                        configRepository.setActive(id)
                        onActiveServerChanged()
                    },
                    onAddServer = {
                        serversAddRequest = true
                        current = TopDestination.Servers
                    },
                    onOpenSpeedTest = {
                        if (TopDestination.SpeedTest in tabs) {
                            current = TopDestination.SpeedTest
                        } else {
                            deepLinkOrigin = current
                            pendingSettingsPage = dev.cluvex.zedsecure.ui.settings.SettingsPage.SpeedTest
                            current = TopDestination.Settings
                        }
                    },
                    onOpenMap = {
                        if (TopDestination.Map in tabs) {
                            current = TopDestination.Map
                        } else {
                            deepLinkOrigin = current
                            pendingSettingsPage = dev.cluvex.zedsecure.ui.settings.SettingsPage.Map
                            current = TopDestination.Settings
                        }
                    },
                  )
                }
                TopDestination.Servers -> ServersScreen(
                    repository = configRepository,
                    contentPadding = innerPadding,
                    twoColumns = settings.doubleColumnDisplay,
                    showAllGroup = settings.groupAllDisplay,
                    confirmRemove = settings.confirmRemove,
                    realPingConcurrency = settings.realPingConcurrency,
                    delayTestUrl = settings.delayTestUrl,
                    autoTestAfterUpdate = settings.autoTestAfterUpdate,
                    autoRemoveInvalidAfterTest = settings.autoRemoveInvalidAfterTest,
                    autoSortAfterTest = settings.autoSortAfterTest,
                    personalization = settings.toPersonalization(),
                    reduceMotion = settings.reduceMotion,
                    onAetherAuto = onAetherAuto,
                    onAetherScan = onAetherScan,
                    onAetherConnect = onAetherConnect,
                    onServerActivated = {
                        if (settings.homeAfterSelect) current = TopDestination.Home

                        onActiveServerChanged()
                    },
                    detectServerLocation = settings.detectServerLocation,
                    addRequest = serversAddRequest,
                    onAddRequestConsumed = { serversAddRequest = false },
                )
                TopDestination.Vault -> dev.cluvex.zedsecure.ui.components.ProvidePageBack(null) {
                    dev.cluvex.zedsecure.ui.vault.VaultScreen(
                        repository = configRepository,
                        contentPadding = innerPadding,
                        onImportRequested = onImportZsx,
                    )
                }
                TopDestination.Subscriptions -> SubscriptionsTabPage(configRepository, innerPadding)
                TopDestination.Logs -> LogsTabPage(innerPadding)
                TopDestination.SpeedTest -> dev.cluvex.zedsecure.ui.components.ProvidePageBack(null) {
                    dev.cluvex.zedsecure.ui.speedtest.SpeedTestScreen(
                        innerPadding,
                        Modifier,
                        activeProfile?.isDnsBasedTunnel == true,
                    )
                }
                TopDestination.Map -> dev.cluvex.zedsecure.ui.components.ProvidePageBack(null) {
                    dev.cluvex.zedsecure.ui.map.MapScreen(
                        contentPadding = innerPadding,
                        reduceMotion = settings.reduceMotion,
                        ipApiUrl = settings.ipApiUrl,
                        personalization = settings.toPersonalization(),
                        savedOriginCode = settings.verifiedOriginCode,
                        savedOriginLabel = settings.verifiedOriginLabel,
                        onRealLocation = { code, label ->
                            if (code != settings.verifiedOriginCode || label != settings.verifiedOriginLabel) {
                                onUpdateSettings { it.copy(verifiedOriginCode = code, verifiedOriginLabel = label) }
                            }
                        },
                    )
                }
                TopDestination.Routing -> dev.cluvex.zedsecure.ui.components.ProvidePageBack(null) {
                    dev.cluvex.zedsecure.ui.settings.RoutingScreen(
                        settings, innerPadding, onUpdateSettings, Modifier, serverTargets, groupTargets,
                    )
                }
                TopDestination.Apps -> dev.cluvex.zedsecure.ui.components.ProvidePageBack(null) {
                    dev.cluvex.zedsecure.ui.settings.PerAppProxyScreen(
                        settings, innerPadding, onUpdateSettings, Modifier,
                    )
                }
                TopDestination.Settings -> SettingsScreen(
                    settings = settings,
                    contentPadding = innerPadding,
                    onUpdate = onUpdateSettings,
                    onLanguage = onLanguage,
                    initialPage = pendingSettingsPage,
                    onDeepLinkBack = deepLinkOrigin?.let { origin ->
                        {
                            current = origin
                            deepLinkOrigin = null
                        }
                    },
                    onInitialPageConsumed = {
                        pendingSettingsPage = dev.cluvex.zedsecure.ui.settings.SettingsPage.Root
                    },

                    serverTargets = serverTargets,
                    autoCountries = autoCountries,
                    groupTargets = groupTargets,
                    dnsTunnelActive = activeProfile?.isDnsBasedTunnel == true,

                    onOpenVault = if (TopDestination.Vault in tabs) null else ({ current = TopDestination.Vault }),

                    onReplayTour = {
                        current = TopDestination.Home
                        tourStep = 0
                    },

                    mtuHint = mtuHint,
                )
            }
            }
        }

        val vpnStatus by dev.cluvex.zedsecure.core.VpnManager.status.collectAsStateWithLifecycle()
        val detailsOpen by homeVm.expanded.collectAsStateWithLifecycle()
        HomeFabCluster(
            visible = current == TopDestination.Home && !bento &&
                !(detailsOpen && settings.appDesign == dev.cluvex.zedsecure.domain.model.AppDesign.Pixel),
            showSpeedTest = vpnStatus.state == dev.cluvex.zedsecure.domain.model.ConnectionState.Connected &&
                activeProfile?.isDnsBasedTunnel != true,
            reduceMotion = settings.reduceMotion,
            atEnd = settings.speedFabAtEnd,
            yFraction = settings.speedFabY,
            onMove = { end, y -> onUpdateSettings { it.copy(speedFabAtEnd = end, speedFabY = y) } },
            onOpenMap = {
                if (TopDestination.Map in tabs) {
                    current = TopDestination.Map
                } else {
                    deepLinkOrigin = current
                    pendingSettingsPage = dev.cluvex.zedsecure.ui.settings.SettingsPage.Map
                    current = TopDestination.Settings
                }
            },
            onOpenSpeedTest = {
                if (TopDestination.SpeedTest in tabs) {
                    current = TopDestination.SpeedTest
                } else {
                    deepLinkOrigin = current
                    pendingSettingsPage = dev.cluvex.zedsecure.ui.settings.SettingsPage.SpeedTest
                    current = TopDestination.Settings
                }
            },
            modifier = Modifier.padding(innerPadding),
        )
      }
    }

    if (dock) {
        dev.cluvex.zedsecure.ui.bento.BentoDock(
            current = current,
            onSelect = { current = it },
            visible = dockShown || !autoHide,
            modifier = Modifier.align(Alignment.BottomCenter),
            tabs = tabs,
        )
    }

    val tourSteps = dev.cluvex.zedsecure.ui.onboarding.rememberTourSteps()
    if (tourStep in tourSteps.indices) {
        val step = tourSteps[tourStep]

        val finish = {
            tourStep = -1
            current = TopDestination.Home
        }
        val advance = { if (tourStep >= tourSteps.lastIndex) finish() else tourStep += 1 }
        androidx.compose.runtime.LaunchedEffect(tourStep) {
            current = when (step.destination) {
                dev.cluvex.zedsecure.ui.onboarding.TourDestination.Home -> TopDestination.Home
                dev.cluvex.zedsecure.ui.onboarding.TourDestination.Servers -> TopDestination.Servers
                dev.cluvex.zedsecure.ui.onboarding.TourDestination.Settings -> TopDestination.Settings
            }
        }
        // The Rdx steps point at rows inside the add-server sheet: ask it to open.
        val sheetStep = 
            step.target == dev.cluvex.zedsecure.ui.onboarding.TourTargets.SERVERS_RDX_CF
        androidx.compose.runtime.LaunchedEffect(sheetStep) {
            dev.cluvex.zedsecure.ui.onboarding.TourPins.openAddSheet = sheetStep
        }
        androidx.compose.runtime.DisposableEffect(Unit) {
            onDispose {
                val pins = dev.cluvex.zedsecure.ui.onboarding.TourPins
                pins.openAddSheet = false
                pins.active = false
                pins.sheetStepActive = false
                pins.currentTarget = null
                pins.sheetOverlay = null
                pins.sheetOverlayLive = false
            }
        }
        // One overlay instance. It renders here for normal steps; the two Rdx steps
        // live inside the add-server sheet, which owns its own window, so the sheet
        // composes this instance itself (otherwise it draws behind the sheet and
        // its hole/caption point at nothing).
        val overlay: @Composable () -> Unit = {
            dev.cluvex.zedsecure.ui.onboarding.TourOverlay(
                step = step,
                index = tourStep,
                total = tourSteps.size,
                nextLabel = if (tourStep == tourSteps.lastIndex) {
                    stringResource(Res.string.tour_done)
                } else {
                    stringResource(Res.string.tour_next)
                },
                skipLabel = stringResource(Res.string.tour_skip),
                onNext = advance,
                onSkip = finish,
                onTargetMissing = advance,
            )
        }
        androidx.compose.runtime.SideEffect {
            val pins = dev.cluvex.zedsecure.ui.onboarding.TourPins
            pins.active = true
            pins.sheetStepActive = sheetStep
            pins.currentTarget = step.target
            pins.sheetOverlay = if (sheetStep) overlay else null
        }
        if (sheetStep) {
            // Nothing renders here while the step is hosted by the sheet, so keep the
            // tour moving if the sheet never shows up or the user dismisses it.
            // (Once hosted, the overlay's own missing-target watchdog takes over.)
            androidx.compose.runtime.LaunchedEffect(step.target) {
                var unhosted = 0L
                while (unhosted < 3_000L) {
                    kotlinx.coroutines.delay(200L)
                    unhosted = if (dev.cluvex.zedsecure.ui.onboarding.TourPins.sheetOverlayLive) {
                        0L
                    } else {
                        unhosted + 200L
                    }
                }
                advance()
            }
        } else {
            overlay()
        }
    }
    }
}

private fun languageEnglishName(language: AppLanguage): String = when (language) {
    AppLanguage.Persian -> "Persian"
    AppLanguage.English -> "English"

    AppLanguage.System -> "the language the user writes in"
}

private fun languageNativeName(language: AppLanguage): String = when (language) {
    AppLanguage.Persian -> "فارسی"
    AppLanguage.English -> "English"
    AppLanguage.System -> ""
}
