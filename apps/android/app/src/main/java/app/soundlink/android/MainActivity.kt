package app.soundlink.android

import android.Manifest
import android.app.LocaleManager
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.semantics.Role
import java.util.UUID
import java.util.concurrent.Executors
import org.json.JSONObject

private val DOWNLOAD_URL = BuildConfig.WEBSITE_URL

private data class Prompt(
    val title: String,
    val text: String = "",
    val accept: String = "",
    val action: () -> Unit = {},
    val choices: List<Pair<String, () -> Unit>> = emptyList(),
    val selected: String? = null,
    val disabledChoices: Set<String> = emptySet(),
    val destructive: Boolean = false,
    val targetRevision: Int? = null,
    val setupStep: SetupStep? = null,
)

class MainActivity : ComponentActivity() {
    private val ui = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor()
    private var page by mutableStateOf("home")
    private var revision by mutableIntStateOf(0)
    private var pairing by mutableStateOf("")
    private var updateStatus by mutableStateOf("")
    private var exporting by mutableStateOf(false)
    private var updating by mutableStateOf(false)
    private var prompt by mutableStateOf<Prompt?>(null)
    private var active = false
    private var polling = false
    private var recoveryAction = 1
    private var promptedTargetRevision = -1
    private var shownSetupStep: SetupStep? = null
    private val preferenceRefresh = Runnable { if (!isDestroyed) refresh() }
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        ui.removeCallbacks(preferenceRefresh)
        ui.post(preferenceRefresh)
    }
    private val prefs
        get() = Link.prefs(this)

    private val ticker =
        object : Runnable {
            override fun run() {
                if (active) {
                    refresh()
                    ui.postDelayed(this, 1000)
                }
            }
        }

    private fun refresh() {
        revision++
        if (!prefs.contains("peer") && page == "onboarding") page = "home"
        val target = json("observe_target")
        if (prompt?.targetRevision?.let { !prefs.contains("peer") || it != target.optInt("revision") } == true) prompt = null
        if (active) {
            HeadphoneSelection.match(this)
            offerHeadphoneConfirmation(target)
            advanceSetup()
        }
    }

    private fun json(key: String) = runCatching {
        JSONObject(prefs.getString(key, "{}") ?: "{}")
    }
        .getOrDefault(JSONObject())

    private fun s(id: Int) = getString(id)

    private fun permission(value: String) =
        checkSelfPermission(value) == PackageManager.PERMISSION_GRANTED

    private fun alert(text: String) {
        prompt = Prompt("Sound Link", text)
    }

    private fun navigate(destination: String) {
        if (destination == "onboarding" && page != destination) shownSetupStep = null
        if (destination != "onboarding" && prompt?.setupStep != null) prompt = null
        page = destination
        if (destination == "onboarding") ui.post { if (active) advanceSetup() }
    }

    private fun back() {
        page =
            when (page) {
                "advanced" -> "diagnostics"
                "headphones" ->
                    if (prefs.getBoolean("setup_pending", false)) "onboarding" else "settings"
                "settings",
                "device",
                "onboarding",
                "download" -> "home"
                else -> "settings"
            }
    }

    private fun fresh(): Boolean {
        val ack = prefs.getLong("ack_elapsed", 0)
        val age = SystemClock.elapsedRealtime() - ack
        return ack > 0 &&
            age in 0..9999 &&
            prefs.getInt("ack_boot", -1) ==
                Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, 0)
    }

    private fun runWork(block: () -> Unit) {
        work.execute {
            try {
                block()
            } catch (e: Exception) {
                ui.post { if (!isDestroyed) alert(UserMessage.error(this, e)) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Link.clientID(this)
        page =
            savedInstanceState?.getString("page")
                ?: if (prefs.getBoolean("setup_pending", false)) "onboarding" else "home"
        if (intent.getBooleanExtra("offline_prompt", false)) page = "home"
        if (intent.getBooleanExtra("notification_preview", false)) page = "diagnostics"
        shownSetupStep = savedInstanceState?.getString("shown_setup_step")?.let { name ->
            runCatching { SetupStep.valueOf(name) }.getOrNull()
        }
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        setContent { SoundLinkTheme { Content() } }
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putString("page", page)
        if (prompt == null) out.putString("shown_setup_step", shownSetupStep?.name)
        super.onSaveInstanceState(out)
    }

    override fun onResume() {
        super.onResume()
        active = true
        ui.post(ticker)
        resumePairing()
        Diagnostics.record(this, "界面可见", "Compose MainActivity 恢复")
        if (Recovery.enabled(this)) {
            Recovery.arm(this)
            Recovery.wake(this, "打开 App 恢复", null)
        }
        if (prefs.getBoolean("connect_after_pair", false) && prefs.contains("peer")) connect()
    }

    override fun onPause() {
        active = false
        ui.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        ui.removeCallbacks(preferenceRefresh)
        work.shutdown()
        super.onDestroy()
    }

    @Composable
    private fun Content() {
        revision // Observe preference snapshots without rebuilding a View tree.
        BackHandler(page != "home") { back() }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
                    )
            ) {
                Row(
                    Modifier.fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (page != "home") {
                        FilledTonalIconButton(
                            onClick = { back() },
                            modifier = Modifier.size(48.dp),
                            colors =
                                IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor =
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = .08f),
                                    contentColor = MaterialTheme.colorScheme.onSurface,
                                ),
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_back),
                                contentDescription = s(R.string.back),
                                modifier = Modifier.size(24.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(
                        when (page) {
                            "settings" -> s(R.string.settings)
                            "headphones" -> s(R.string.headphones)
                            "device" -> s(R.string.paired_mac)
                            "diagnostics" -> s(R.string.help_diagnostics)
                            "advanced" -> s(R.string.advanced)
                            "onboarding" -> s(R.string.connection_setup)
                            "download" -> s(R.string.install_mac)
                            "about" -> s(R.string.about)
                            else -> "Sound Link"
                        },
                        Modifier.weight(1f),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (page == "home")
                        FilledTonalIconButton(
                            onClick = { navigate("settings") },
                            modifier = Modifier.size(48.dp),
                            colors =
                                IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor =
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = .08f),
                                    contentColor = MaterialTheme.colorScheme.onSurface,
                                ),
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_settings),
                                contentDescription = s(R.string.settings),
                                modifier = Modifier.size(24.dp),
                            )
                        }
                }
                key(page) {
                    Column(
                        Modifier.weight(1f)
                            .fillMaxWidth()
                            .testTag("page-scroll")
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 22.dp)
                            .padding(bottom = 24.dp)
                            .windowInsetsPadding(
                                WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
                            ),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        OfflineReconnectCard()
                        when (page) {
                            "home" -> Home()
                            "settings" -> SettingsPage()
                            "headphones" -> Headphones()
                            "device" -> Device()
                            "diagnostics" -> DiagnosticsPage()
                            "advanced" -> Advanced()
                            "onboarding" -> Onboarding()
                            "download" -> Download()
                            "about" -> About()
                        }
                    }
                }
            }
        }
        prompt?.let { current -> PromptDialog(current) }
    }

    @Composable
    private fun PromptDialog(current: Prompt) {
        val colors = MaterialTheme.colorScheme
        Dialog(
            onDismissRequest = { prompt = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = Modifier.padding(24.dp).widthIn(max = 420.dp).fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = colors.surface,
                border = BorderStroke(1.dp, colors.outlineVariant),
            ) {
                Column(Modifier.heightIn(max = 580.dp).padding(24.dp)) {
                    Text(current.title, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                    Column(
                        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    ) {
                        if (current.text.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Text(current.text, color = colors.onSurfaceVariant, fontSize = 14.sp)
                        }
                        if (current.choices.isNotEmpty()) Spacer(Modifier.height(16.dp))
                        current.choices.forEachIndexed { index, (label, action) ->
                            val choiceEnabled = label !in current.disabledChoices
                            if (index > 0) HorizontalDivider(color = colors.outlineVariant)
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable(enabled = choiceEnabled, role = Role.Button) { prompt = null; action() }
                                    .padding(vertical = 14.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(label, Modifier.weight(1f), fontSize = 16.sp,
                                    color = colors.onSurface.copy(alpha = if (choiceEnabled) 1f else .38f))
                                if (current.selected != null) {
                                    RadioButton(selected = current.selected == label, onClick = null,
                                        enabled = choiceEnabled,
                                        modifier = Modifier.padding(start = 16.dp).size(24.dp))
                                } else {
                                    Icon(painterResource(R.drawable.ic_chevron), null,
                                        Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    if (current.choices.isNotEmpty()) {
                        OutlinedButton(onClick = { prompt = null }, modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurface),
                            border = BorderStroke(1.dp, colors.outlineVariant)) { Text(s(R.string.cancel)) }
                    } else {
                        PromptActions(current)
                    }
                }
            }
        }
    }

    @Composable
    private fun PromptActions(current: Prompt) {
        val colors = MaterialTheme.colorScheme
        val accept = current.accept.ifEmpty { s(R.string.ok) }
        val cancel = s(R.string.cancel)
        val style = MaterialTheme.typography.labelLarge
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        fun buttonWidth(text: String) = with(density) {
            measurer.measure(AnnotatedString(text), style = style, maxLines = 1).size.width.toDp()
        } + 34.dp // 16 dp on each side, plus rounding room.
        val cancelWidth = maxOf(64.dp, buttonWidth(cancel))
        val acceptWidth = maxOf(64.dp, buttonWidth(accept))
        val acceptButton: @Composable (Modifier) -> Unit = { modifier ->
            Button(onClick = { prompt = null; current.action() }, modifier = modifier.heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (current.destructive) colors.error else colors.primary,
                    contentColor = if (current.destructive) colors.onError else colors.onPrimary,
                )) { Text(accept, style = style, textAlign = TextAlign.Center) }
        }
        val cancelButton: @Composable (Modifier) -> Unit = { modifier ->
            OutlinedButton(onClick = { prompt = null }, modifier = modifier.heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurface),
                border = BorderStroke(1.dp, colors.outlineVariant)) { Text(cancel, style = style) }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (current.accept.isEmpty()) {
                acceptButton(Modifier.fillMaxWidth())
            } else if (cancelWidth + acceptWidth + 12.dp <= maxWidth) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    cancelButton(Modifier.width(cancelWidth))
                    acceptButton(Modifier.weight(1f))
                }
            } else {
                // Narrow screens, longer translations and larger fonts get full-width actions.
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    acceptButton(Modifier.fillMaxWidth())
                    cancelButton(Modifier.fillMaxWidth())
                }
            }
        }
    }

    @Composable
    private fun Panel(content: @Composable ColumnScope.() -> Unit) {
        Surface(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                content = content,
            )
        }
    }

    @Composable
    private fun Hint(text: String) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }

    @Composable
    private fun Action(
        id: Int,
        enabled: Boolean = true,
        primary: Boolean = false,
        action: () -> Unit,
    ) {
        if (primary)
            Button(action, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = enabled,
                shape = RoundedCornerShape(12.dp)) {
                Text(s(id))
            }
        else
            TextButton(action, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = enabled,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
                Text(s(id), Modifier.weight(1f), textAlign = TextAlign.Start)
            }
    }

    @Composable
    private fun Endpoint(icon: Int, title: String, sample: JSONObject?) {
        val connected = sample?.optString("connection") == "connected"
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                painterResource(icon),
                null,
                Modifier.size(38.dp),
                tint =
                    if (connected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(title, fontSize = 13.sp)
            Hint(
                s(
                    when (sample?.optString("connection")) {
                        "connected" -> R.string.connected
                        "disconnected" -> R.string.not_connected
                        else -> R.string.unknown
                    }
                )
            )
        }
    }

    @Composable
    private fun WelcomeFeature(icon: Int, title: Int, detail: Int) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
            Icon(painterResource(icon), null, Modifier.padding(top = 2.dp).size(24.dp),
                tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(s(title), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Hint(s(detail))
            }
        }
    }

    @Composable
    private fun Home() {
        revision // Subscribe this skippable page to background preference updates.
        if (!prefs.contains("peer")) {
            Panel {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_laptop), null, Modifier.size(30.dp))
                    Text("⇄", Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(painterResource(R.drawable.ic_headphones), null, Modifier.size(56.dp))
                    Text("⇄", Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(painterResource(R.drawable.ic_phone), null, Modifier.size(30.dp))
                }
                Text(s(R.string.welcome_title), Modifier.fillMaxWidth(), fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Text(s(R.string.welcome_subtitle), Modifier.fillMaxWidth(), fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Action(R.string.scan_pair, primary = true) { scan() }
                TextButton(onClick = { navigate("download") },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
                    Text(s(R.string.install_mac))
                }
                if (pairing.isNotEmpty())
                    Text(pairing, color = MaterialTheme.colorScheme.primary, fontSize = 20.sp)
            }
            Panel {
                WelcomeFeature(R.drawable.ic_recovery, R.string.feature_multipoint, R.string.feature_multipoint_detail)
                WelcomeFeature(R.drawable.ic_headphones, R.string.feature_airpods, R.string.feature_airpods_detail)
                WelcomeFeature(R.drawable.ic_play, R.string.feature_playback, R.string.feature_playback_detail)
            }
            return
        }
        val target = json("observe_target")
        val isFresh = fresh()
        val mac =
            json("remote_observation").optJSONObject("mac")?.takeIf {
                isFresh &&
                    it.optInt("targetRevision", -1) == target.optInt("revision", -2) &&
                    it.optDouble("ageMs", 10000.0) + SystemClock.elapsedRealtime() -
                        prefs.getLong("ack_elapsed", 0) < 10000
            }
        val phone =
            PhoneObservation.wire(this)?.takeIf {
                it.optInt("targetRevision", -1) == target.optInt("revision", -2)
            }
        val mc = mac?.optString("connection") == "connected"
        val pc = phone?.optString("connection") == "connected"
        val ready = HeadphoneControl.ready(this@MainActivity)
        val enabled = HeadphoneControl.enabled(this)
        val handoff = json("handoff_remote")
        val local = json("phone_local_control")
        val busy =
            isFresh && handoff.optString("phase").startsWith("connecting") ||
                prefs.getBoolean("running", false) && local.optBoolean("active")
        val summary =
            when {
                !enabled -> R.string.switch_paused
                !ready -> R.string.control_needed
                busy -> R.string.switching
                isFresh && handoff.optString("lastOutcome") == "failed" ||
                    local.optBoolean("blocked") -> R.string.switch_failed
                mc && pc -> R.string.headphones_both
                mc -> R.string.headphones_mac
                pc -> R.string.headphones_phone
                !isFresh -> R.string.mac_unknown
                else -> R.string.waiting_headphones
            }
        Panel {
            Icon(
                painterResource(R.drawable.ic_headphones),
                null,
                Modifier.align(Alignment.CenterHorizontally).size(56.dp),
            )
            Text(
                target.optString("macName").ifEmpty { target.optString("phoneName").ifEmpty { s(R.string.not_selected) } },
                Modifier.fillMaxWidth(),
                fontSize = 27.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                s(summary),
                Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 13.sp,
            )
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Endpoint(R.drawable.ic_laptop, "Mac", mac)
                Text("⇄", fontSize = 22.sp)
                Endpoint(R.drawable.ic_phone, s(R.string.phone), phone)
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s(R.string.automatic_switching), Modifier.weight(1f))
                Switch(enabled, { toggleHeadphones() })
            }
            Hint(s(if (enabled) R.string.follow_playback else R.string.keep_connection))
            if (prefs.getBoolean("setup_pending", false) || !ready)
                Action(R.string.finish_setup, primary = true) { navigate("onboarding") }
        }
        Panel {
            Text(json("peer").optString("name", "Mac"), fontWeight = FontWeight.Medium)
            Hint(s(if (isFresh) R.string.connected else R.string.not_connected))
            Action(R.string.manage) { navigate("device") }
        }
        Hint(s(if (Recovery.enabled(this)) R.string.background_on else R.string.background_off))
    }

    @Composable
    private fun GroupTitle(id: Int) {
        Text(
            s(id),
            Modifier.padding(start = 4.dp),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    @Composable
    private fun SettingsGroup(title: Int, content: @Composable ColumnScope.() -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GroupTitle(title)
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), content = content)
            }
        }
    }

    @Composable
    private fun SettingsRow(id: Int, icon: Int, enabled: Boolean = true, value: String? = null, action: () -> Unit) {
        val color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .38f)
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = action)
            .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), null, Modifier.size(20.dp), tint = color)
            Spacer(Modifier.width(12.dp))
            Text(s(id), Modifier.weight(1f), color = color, fontSize = 15.sp)
            if (value != null) Text(value, Modifier.padding(start = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Icon(painterResource(R.drawable.ic_chevron), null, Modifier.padding(start = 8.dp).size(16.dp),
                tint = color.copy(alpha = if (enabled) .45f else .2f))
        }
    }

    @Composable
    private fun SettingsDivider() {
        HorizontalDivider(Modifier.padding(start = 32.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }

    @Composable
    private fun SettingsPage() {
        revision // Subscribe this skippable page to background preference updates.
        SettingsGroup(R.string.group_devices) {
            SettingsRow(R.string.headphones, R.drawable.ic_headphones) { navigate("headphones") }
            SettingsDivider()
            SettingsRow(R.string.paired_mac, R.drawable.ic_laptop) { navigate("device") }
            SettingsDivider()
            SettingsRow(R.string.finish_setup, R.drawable.ic_checklist, prefs.contains("peer")) { navigate("onboarding") }
            SettingsDivider()
            SettingsRow(R.string.install_mac, R.drawable.ic_download) { navigate("download") }
        }
        SettingsGroup(R.string.group_general) {
            SettingsRow(R.string.offline_reconnect, R.drawable.ic_headphones,
                value = s(offlineModeLabel(offlineMode()))) { offlineReconnect() }
            SettingsDivider()
            SettingsRow(R.string.language, R.drawable.ic_language) { language() }
            SettingsDivider()
            SettingsRow(R.string.appearance, R.drawable.ic_appearance,
                value = s(appearanceLabel(prefs.getString("interface_appearance", "system") ?: "system"))) { appearance() }
        }
        Hint(s(R.string.offline_reconnect_help))
        if (offlineMode() == OfflineReconnectFlow.Mode.PROMPT &&
            !OfflineReconnectNotification.available(this)) {
            Hint(s(R.string.offline_notifications_needed))
            Action(R.string.offline_enable_notifications) { notificationSettings() }
        }
        SettingsGroup(R.string.group_support) {
            SettingsRow(R.string.reset_connections, R.drawable.ic_recovery) {
                prompt = Prompt(s(R.string.reset_connections_title), s(R.string.reset_connections_notice),
                    s(R.string.reset_connections), {
                        val complete = PairingLifecycle.reset(this@MainActivity)
                        pairing = ""
                        promptedTargetRevision = -1
                        navigate("home")
                        refresh()
                        if (!complete) alert(s(R.string.reset_connections_incomplete))
                    }, destructive = true)
            }
            SettingsDivider()
            SettingsRow(R.string.help_diagnostics, R.drawable.ic_help) { navigate("diagnostics") }
            SettingsDivider()
            if (Updater.enabled()) {
                SettingsRow(R.string.check_updates, R.drawable.ic_download, !updating) { checkUpdate() }
                SettingsDivider()
            }
            SettingsRow(R.string.about, R.drawable.ic_info) { navigate("about") }
        }
        Hint(updateStatus.ifEmpty { "Sound Link · ${BuildConfig.VERSION_NAME}" })
    }

    private fun offlineMode() = OfflineReconnectFlow.Mode.parse(
        prefs.getString(OfflineReconnectFlow.PREFERENCE, null))

    private fun offlineModeLabel(mode: OfflineReconnectFlow.Mode) = when (mode) {
        OfflineReconnectFlow.Mode.NEVER -> R.string.offline_never
        OfflineReconnectFlow.Mode.PROMPT -> if (mode.available()) R.string.offline_prompt else R.string.offline_prompt_disabled
        OfflineReconnectFlow.Mode.AUTO -> R.string.offline_auto
    }

    private fun offlineReconnect() {
        prompt = Prompt(s(R.string.offline_reconnect), s(R.string.offline_reconnect_choices),
            selected = s(offlineModeLabel(offlineMode())),
            disabledChoices = OfflineReconnectFlow.Mode.entries.filterNot { it.available() }
                .map { s(offlineModeLabel(it)) }.toSet(),
            choices = OfflineReconnectFlow.Mode.entries.map { mode ->
                s(offlineModeLabel(mode)) to {
                    if (mode.available()) {
                        prefs.edit().putString(OfflineReconnectFlow.PREFERENCE, mode.value()).apply()
                        refresh()
                    }
                }
            })
    }

    private fun notificationSettings() {
        OfflineReconnectNotification.createChannel(this)
        startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, OfflineReconnectNotification.CHANNEL))
    }

    @Composable
    private fun OfflineReconnectCard() {
        revision
        val state = json(OfflineReconnectNotification.STATE)
        val remaining = state.optLong("deadline") - SystemClock.elapsedRealtime()
        if (!prefs.getBoolean("running", false) || remaining <= 0 || state.optString("token").isEmpty()) return
        Panel {
            Text(state.optString("title"), fontWeight = FontWeight.SemiBold)
            Hint(state.optString("detail"))
            if (state.optBoolean("waiting")) {
                Action(R.string.offline_connect, primary = true) {
                    sendBroadcast(OfflineReconnectNotification.action(this@MainActivity,
                        OfflineReconnectNotification.CONNECT, state.optString("token")))
                }
                Action(R.string.offline_ignore) {
                    sendBroadcast(OfflineReconnectNotification.action(this@MainActivity,
                        OfflineReconnectNotification.IGNORE, state.optString("token")))
                }
            }
        }
    }

    @Composable
    private fun About() {
        revision // Subscribe this skippable page to background preference updates.
        Panel {
            Icon(
                painterResource(R.drawable.ic_headphones),
                null,
                Modifier.align(Alignment.CenterHorizontally).size(56.dp),
            )
            Text(
                "Sound Link",
                Modifier.fillMaxWidth(),
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                s(R.string.message_version) + BuildConfig.VERSION_NAME,
                Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Hint(s(R.string.about_description))
        }
    }

    @Composable
    private fun Device() {
        revision // Subscribe this skippable page to background preference updates.
        Panel {
            Text(
                json("peer").optString("name").ifEmpty { s(R.string.not_paired) },
                fontSize = 22.sp,
            )
            if (prefs.contains("peer")) {
                val backgroundState = when {
                    Recovery.association(this@MainActivity) == null || !Recovery.permissions(this@MainActivity) ||
                        !permission(Manifest.permission.ACCESS_LOCAL_NETWORK) -> R.string.recovery_needs_permission
                    Recovery.enabled(this@MainActivity) -> R.string.enabled
                    else -> R.string.recovery_paused
                }
                SettingsRow(R.string.recovery, R.drawable.ic_recovery,
                    value = s(backgroundState)) { recovery(1) }
                Hint(s(R.string.recovery_help))
                HorizontalDivider()
                Action(R.string.reconnect) { connect() }
                Action(R.string.rescan) { scan() }
                Action(R.string.find_bluetooth) { recovery(2) }
                Action(R.string.pause_connection) {
                    Recovery.disable(this@MainActivity)
                    refresh()
                }
                Hint(s(R.string.pause_connection_help))
                Action(R.string.unpair) {
                    prompt =
                        Prompt(
                            s(R.string.message_unpair_this_mac),
                            s(R.string.unpair_notice),
                            s(R.string.message_unpair),
                            {
                                PairingLifecycle.forget(this@MainActivity)
                                pairing = ""
                                navigate("home")
                                refresh()
                            },
                            destructive = true,
                        )
                }
            } else Action(R.string.scan_pair, primary = true) { scan() }
        }
    }

    @Composable
    private fun Download() {
        if (DOWNLOAD_URL.isEmpty()) {
            Panel { Hint(s(R.string.development_companion)) }
            return
        }
        revision // Subscribe this skippable page to background preference updates.
        Panel {
            Icon(
                painterResource(R.drawable.ic_laptop),
                null,
                Modifier.align(Alignment.CenterHorizontally).size(56.dp),
            )
            Text("Sound Link for Mac", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Hint(s(R.string.mac_app_description))
            TextButton(
                onClick = {
                    getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Sound Link", DOWNLOAD_URL))
                    alert(s(R.string.link_copied))
                }
            ) {
                Text(DOWNLOAD_URL)
            }
            Hint(s(R.string.tap_copy))
            Action(R.string.open_download) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL)))
            }
        }
    }

    private fun setupStep(): SetupStep {
        val target = json("observe_target")
        return SetupStep.next(
            Recovery.association(this) != null && Recovery.enabled(this),
            target.optString("phoneAddress").isNotEmpty(), target.optString("macAddress").isNotEmpty(),
            target.optString("pendingSide"), "phone", HeadphoneControl.ready(this@MainActivity),
        )
    }

    private fun advanceSetup() {
        if (!active || page != "onboarding" || !prefs.contains("peer")) return
        val step = setupStep()
        if (prompt?.setupStep?.let { it != step } == true) prompt = null
        if (prompt != null || shownSetupStep == step) return
        shownSetupStep = step
        when (step) {
            SetupStep.RECONNECT -> prompt = Prompt(s(R.string.setup_bluetooth),
                s(R.string.setup_bluetooth_help) + "\n\n" + Recovery.discoveryName(json("peer").optString("pin")),
                s(R.string.allow_reconnect), { recovery(1) }, setupStep = step)
            SetupStep.HEADPHONES -> chooseHeadphone(setup = true)
            SetupStep.CONFIRM -> { promptedTargetRevision = -1; offerHeadphoneConfirmation(json("observe_target")) }
            SetupStep.WAITING -> prompt = Prompt(s(R.string.sync_wait_title), s(R.string.sync_wait_mac), setupStep = step)
            SetupStep.CONTROL -> prompt = Prompt(s(R.string.allow_control), json("observe_target").optString("phoneName") + "\n\n" + s(R.string.control_help),
                s(R.string.allow_control), { allowControl() }, setupStep = step)
            SetupStep.COMPLETE -> prompt = Prompt(s(R.string.setup_complete), s(R.string.setup_complete_help),
                s(R.string.finish), { prefs.edit().remove("setup_pending").apply(); navigate("home") }, setupStep = step)
        }
    }

    @Composable
    private fun SetupRow(number: Int, title: Int, done: Boolean, current: Boolean) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (done) "✓" else number.toString(), fontSize = 18.sp,
                color = if (done || current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(24.dp), textAlign = TextAlign.Center)
            Text(s(title), fontSize = 16.sp, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal)
        }
    }

    @Composable
    private fun Onboarding() {
        revision
        val step = setupStep()
        val target = json("observe_target")
        val synced = target.optString("pendingSide").isEmpty() && target.optString("phoneAddress").isNotEmpty() && target.optString("macAddress").isNotEmpty()
        Panel {
            Text(s(R.string.finish_setup), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Hint(s(R.string.setup_intro))
            SetupRow(1, R.string.setup_bluetooth, step != SetupStep.RECONNECT, step == SetupStep.RECONNECT)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SetupRow(2, R.string.setup_headphones, synced, step in listOf(SetupStep.HEADPHONES, SetupStep.CONFIRM, SetupStep.WAITING))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SetupRow(3, R.string.allow_control, HeadphoneControl.ready(this@MainActivity), step == SetupStep.CONTROL)
            Action(if (step == SetupStep.COMPLETE) R.string.finish else R.string.continue_setup, primary = true) {
                if (step == SetupStep.COMPLETE) { prefs.edit().remove("setup_pending").apply(); navigate("home") }
                else { shownSetupStep = null; advanceSetup() }
            }
            Action(R.string.not_now) { navigate("home") }
        }
    }

    @Composable
    private fun DeviceSelectionRow(label: String, name: String) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(label, Modifier.width(72.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(name.ifEmpty { s(R.string.not_selected) }, Modifier.weight(1f))
        }
    }

    @Composable
    private fun Headphones() {
        revision
        val target = json("observe_target")
        val selected = target.optString("phoneAddress").isNotEmpty()
        val pending = target.optString("pendingSide")
        val synced = pending.isEmpty() && selected && target.optString("macAddress").isNotEmpty()
        Panel {
            Hint(s(R.string.current_headphones))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(painterResource(R.drawable.ic_headphones), null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                Text(target.optString("phoneName").ifEmpty { s(R.string.not_selected) }, modifier = Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            }
            Action(if (selected) R.string.change_headphones else R.string.choose_same, primary = !selected) { chooseHeadphone() }
            TextButton(onClick = { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) { Text(s(R.string.bluetooth_settings)) }
        }
        Panel {
            Text(s(R.string.device_sync), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(s(when { pending == "phone" -> R.string.sync_confirm_phone; pending == "mac" -> R.string.sync_wait_mac; synced -> R.string.sync_complete; else -> R.string.sync_select_hint }),
                color = if (synced) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            DeviceSelectionRow("Android", target.optString("phoneName"))
            DeviceSelectionRow("Mac", target.optString("macName"))
            if (pending == "phone") Action(R.string.confirm_headphones, primary = true) { promptedTargetRevision = -1; offerHeadphoneConfirmation(target) }
        }
        Panel {
            Text(s(R.string.headphone_control), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Hint(s(if (HeadphoneControl.ready(this@MainActivity)) R.string.control_ready else R.string.control_help))
            if (synced && !HeadphoneControl.ready(this@MainActivity)) Action(R.string.allow_control, primary = true) { allowControl() }
        }
    }

    @Composable
    private fun DiagnosticsPage() {
        revision // Subscribe this skippable page to background preference updates.
        if (OfflineReconnectFlow.PROMPT_ENABLED) Panel {
            Text(s(R.string.notification_preview_label), fontSize = 20.sp)
            Hint(s(R.string.notification_preview_help))
            Action(R.string.notification_preview_start, !NotificationPreview.running(), true) {
                if (OfflineReconnectNotification.available(this@MainActivity)) {
                    NotificationPreview.start(this@MainActivity)
                    refresh()
                } else {
                    prompt = Prompt(s(R.string.notification_preview_label), s(R.string.offline_notifications_needed),
                        s(R.string.offline_enable_notifications), { notificationSettings() })
                }
            }
            if (NotificationPreview.status() != 0) Hint(s(NotificationPreview.status()))
            Action(R.string.offline_enable_notifications) { notificationSettings() }
            Hint(s(R.string.notification_banner_help))
        }
        Panel {
            Text(s(R.string.logs), fontSize = 20.sp)
            Hint(s(R.string.recording))
            Text(s(R.string.logs_description))
            Action(R.string.export_logs, !exporting, true) { exportLogs() }
            Hint(s(R.string.logs_contents))
        }
        Panel {
            Action(R.string.restore_audio) {
                PhoneMuteGuard.recover(this@MainActivity, "用户手动恢复")
                alert(s(R.string.restore_audio))
            }
            Action(R.string.previous_playing) {
                prompt =
                    Prompt(
                        s(R.string.previous_playing),
                        s(R.string.pause_explanation),
                        s(R.string.go_settings),
                        { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                    )
            }
            Action(R.string.advanced) { navigate("advanced") }
        }
    }

    @Composable
    private fun Advanced() {
        revision // Subscribe this skippable page to background preference updates.
        Panel {
            Hint(s(R.string.raw_logs))
            SelectionContainer {
                Text(
                    listOf(
                            "state",
                            "observe_target",
                            "local_observation",
                            "remote_observation",
                            "handoff_remote",
                            "handoff_local_status",
                            "phone_local_control",
                            "discovery_state",
                            "address_source",
                            "wake_reason",
                            "events",
                        )
                        .joinToString("\n\n") { "$it\n${prefs.getString(it, "—")}" } +
                        "\n\n" +
                        Diagnostics.summaryText(),
                    fontSize = 11.sp,
                )
            }
            Action(R.string.mark_start) {
                Diagnostics.mark(this@MainActivity, true)
                refresh()
            }
            Action(R.string.mark_end) {
                Diagnostics.mark(this@MainActivity, false)
                refresh()
            }
        }
    }

    private fun appearance() {
        prompt =
            Prompt(
                s(R.string.appearance),
                selected = s(appearanceLabel(prefs.getString("interface_appearance", "system") ?: "system")),
                choices =
                    listOf("system", "light", "dark").map { mode ->
                        s(appearanceLabel(mode)) to
                            {
                                prefs.edit().putString("interface_appearance", mode).apply()
                                refresh()
                            }
                    },
            )
    }

    private fun language() {
        prompt =
            Prompt(
                s(R.string.language),
                selected = when (getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()) {
                    "zh" -> "简体中文"
                    "en" -> "English"
                    else -> s(R.string.system_language)
                },
                choices =
                    listOf(s(R.string.system_language) to "", "简体中文" to "zh", "English" to "en")
                        .map { (label, tag) ->
                            label to
                                {
                                    getSystemService(LocaleManager::class.java).applicationLocales =
                                        LocaleList.forLanguageTags(tag)
                                }
                        },
            )
    }

    private fun toggleHeadphones() {
        if (HeadphoneControl.enabled(this)) HeadphoneControl.disable(this)
        else {
            prefs.edit().putBoolean("handoff_enabled", true).apply()
            if (!HeadphoneControl.ready(this@MainActivity)) navigate("onboarding")
        }
        refresh()
    }

    private fun allowControl() {
        if (!Recovery.permissions(this)) {
            requestPermissions(
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
                15,
            )
            return
        }
        if (HeadphoneControl.target(this).optString("phoneAddress").isEmpty()) {
            alert(s(R.string.confirm_headphones))
            return
        }
        HeadphoneControl.enable(this) { refresh() }
    }

    private fun recovery(action: Int) {
        recoveryAction = action
        val missing =
            arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_LOCAL_NETWORK,
                )
                .filterNot(::permission)
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 12)
            return
        }
        if (action == 1 && Recovery.association(this) == null) {
            Recovery.associate(this) { refresh() }
            return
        }
        if (action == 1) Recovery.enable(this)
        runCatching {
            startForegroundService(
                Intent(this, ConnectionService::class.java)
                    .setAction("DISCOVER")
                    .putExtra("reason", "用户查找最新地址")
            )
        }
            .onFailure { alert(UserMessage.error(this, it as? Exception ?: Exception(it))) }
        refresh()
    }

    private fun scan() {
        if (!permission(Manifest.permission.CAMERA)) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 10)
            return
        }
        startActivityForResult(Intent(this, ScanActivity::class.java), 20)
    }

    private fun required() =
        arrayOf(
                Manifest.permission.ACCESS_LOCAL_NETWORK,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.POST_NOTIFICATIONS,
            )
            .filterNot(::permission)
            .toTypedArray()

    private fun connect() {
        if (!active) return
        prefs.edit().remove("connect_after_pair").apply()
        val missing = required()
        if (missing.isNotEmpty()) {
            requestPermissions(missing, 11)
            return
        }
        if (prefs.contains("peer"))
            runCatching { startForegroundService(Intent(this, ConnectionService::class.java)) }
                .onFailure { alert(UserMessage.error(this, it as? Exception ?: Exception(it))) }
    }

    override fun onRequestPermissionsResult(request: Int, names: Array<String>, grants: IntArray) {
        super.onRequestPermissionsResult(request, names, grants)
        when (request) {
            10 -> if (permission(Manifest.permission.CAMERA)) scan()
            11 ->
                if (
                    permission(Manifest.permission.ACCESS_LOCAL_NETWORK) &&
                        permission(Manifest.permission.BLUETOOTH_CONNECT)
                ) {
                    if (prefs.contains("draft")) resumePairing()
                    else if (prefs.contains("peer"))
                        startForegroundService(Intent(this, ConnectionService::class.java))
                } else
                    alert(
                        s(
                            R.string
                                .message_allow_nearby_devices_and_local_network_access_in_settings
                        )
                    )
            12 ->
                if (
                    Recovery.permissions(this) &&
                        permission(Manifest.permission.ACCESS_LOCAL_NETWORK)
                )
                    recovery(recoveryAction)
                else
                    alert(s(R.string.message_allow_nearby_devices_and_local_network_access_to_find))
            14 -> if (Recovery.permissions(this)) chooseHeadphone()
            15 -> if (Recovery.permissions(this)) allowControl()
        }
    }

    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (request != 20 || result != RESULT_OK || data == null) return
        try {
            val config = Link.code(data.getStringExtra("code") ?: "")
            if (prefs.contains("peer")) {
                val old = json("peer")
                require(old.getString("pin") == config.getString("pin")) {
                    s(R.string.message_this_is_a_different_mac_unpair_the_previous_mac)
                }
                stopService(Intent(this, ConnectionService::class.java))
                old.put("host", config.getString("host")).put("port", config.getInt("port"))
                prefs.edit().putString("peer", old.toString()).commit()
                ui.postDelayed({ connect() }, 500)
                return
            }
            require(config.optString("mode") != "route") {
                s(R.string.message_this_mac_is_already_paired_unpair_its_previous_phone)
            }
            config.put("request", UUID.randomUUID().toString())
            prefs
                .edit()
                .putString("draft", config.toString())
                .putLong("pairing_started", SystemClock.elapsedRealtime())
                .commit()
            pairing = ""
            navigate("home")
            resumePairing()
        } catch (e: Exception) {
            alert(UserMessage.error(this, e))
        }
    }

    private fun resumePairing() {
        if (!active || polling || !prefs.contains("draft")) return
        if (
            !permission(Manifest.permission.ACCESS_LOCAL_NETWORK) ||
                !permission(Manifest.permission.BLUETOOTH_CONNECT)
        ) {
            requestPermissions(required(), 11)
            return
        }
        val config = json("draft")
        val request = config.optString("request")
        val elapsed = SystemClock.elapsedRealtime() - prefs.getLong("pairing_started", 0)
        if (elapsed !in 0..310000) {
            prefs.edit().remove("draft").apply()
            pairing = s(R.string.message_pairing_code_expired_refresh_it_on_your_mac_and)
            return
        }
        polling = true
        if (pairing.isEmpty())
            pairing = s(R.string.message_requesting_pairing_with) + config.optString("name") + "…"
        work.execute {
            try {
                val result = Link.pair(this, config)
                ui.post {
                    polling = false
                    if (isDestroyed || json("draft").optString("request") != request) return@post
                    if (result.optString("state") == "paired") {
                        PairingLifecycle.complete(
                            this,
                            config,
                            result.optString("pairing_id", request),
                        )
                        pairing = ""
                        navigate("onboarding")
                        if (active) connect()
                        refresh()
                    } else {
                        pairing =
                            s(R.string.message_confirm_this_code_on_your_mac_n) +
                                result.optString("code")
                        ui.postDelayed({ resumePairing() }, 1800)
                    }
                }
            } catch (e: Exception) {
                ui.post {
                    polling = false
                    if (isDestroyed || json("draft").optString("request") != request) return@post
                    pairing = s(R.string.message_pairing_incomplete) + UserMessage.error(this, e)
                    if (
                        e is Link.Client.RemoteFailure &&
                            e.code in
                                listOf(
                                    "INVITE_EXPIRED",
                                    "INVITE_INVALID",
                                    "PAIRING_REJECTED",
                                    "ALREADY_PAIRED",
                                )
                    )
                        prefs.edit().remove("draft").apply()
                    else ui.postDelayed({ resumePairing() }, 5000)
                }
            }
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun offerHeadphoneConfirmation(target: JSONObject) {
        if (page == "onboarding" && setupStep() == SetupStep.RECONNECT) return
        if (!prefs.contains("peer") || target.optString("pendingSide") != "phone" || prompt != null ||
            promptedTargetRevision == target.optInt("revision")) return
        val devices = HeadphoneSelection.devices(this)
        if (devices.any { HeadphoneMatch.automatic(target, it.address) }) return
        val candidate = devices.firstOrNull { it.address.equals(target.optString("macAddress"), true) }
        val version = target.optInt("revision")
        promptedTargetRevision = version
        if (page == "onboarding") shownSetupStep = SetupStep.CONFIRM
        prompt = Prompt(
            s(R.string.headphone_sync_title),
            if (candidate == null) getString(R.string.headphone_no_match, target.optString("macName"))
            else getString(R.string.headphone_replace, target.optString("macName"), target.optString("previousName")),
            if (candidate == null) s(R.string.choose_same) else s(R.string.message_confirm),
            {
                if (candidate == null) chooseHeadphone(version)
                else selectHeadphone(candidate, version, "confirm")
            },
            targetRevision = version,
        )
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun selectHeadphone(device: android.bluetooth.BluetoothDevice, version: Int, mode: String) {
        val config = json("peer")
        val generation = PairingLifecycle.generation(this)
        runWork {
            if (generation != PairingLifecycle.generation(this) || !prefs.contains("peer")) return@runWork
            require(HeadphoneSelection.devices(this).any { it.address == device.address }) {
                s(R.string.message_pair_your_headphones_in_your_phone_s_bluetooth_settings)
            }
            val client = Link.Client(config)
            try {
                val result = client.selectHeadphone(this,
                    JSONObject().put("revision", version).put("mode", mode)
                        .put("address", device.address).put("name", HeadphoneSelection.name(device)))
                ui.post {
                    if (isDestroyed) return@post
                    if (HeadphoneSelection.receive(this, result.optJSONObject("observation"), generation)) {
                        refresh()
                    }
                }
            } catch (error: Exception) {
                // Refresh the view after a race, but never silently replay a user's stale choice.
                runCatching { HeadphoneSelection.receive(this, client.ping(this).optJSONObject("observation"), generation) }
                throw error
            }
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun chooseHeadphone(confirmationRevision: Int? = null, setup: Boolean = false) {
        if (!Recovery.permissions(this)) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN), 14)
            return
        }
        if (!prefs.contains("peer")) { alert(s(R.string.connect_mac)); return }
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        if (adapter?.isEnabled != true) {
            alert(s(R.string.message_turn_on_bluetooth_on_your_phone_first)); return
        }
        val devices = HeadphoneSelection.devices(this)
        if (devices.isEmpty()) {
            alert(s(R.string.message_pair_your_headphones_in_your_phone_s_bluetooth_settings)); return
        }
        if (!prefs.contains("observe_target")) {
            val generation = PairingLifecycle.generation(this)
            val config = json("peer")
            runWork {
                val result = Link.Client(config).ping(this)
                ui.post {
                    if (!isDestroyed && HeadphoneSelection.receive(this, result.optJSONObject("observation"), generation))
                        chooseHeadphone(confirmationRevision, setup)
                }
            }
            return
        }
        val target = json("observe_target")
        val version = target.optInt("revision")
        if (confirmationRevision != null && confirmationRevision != version) { refresh(); return }
        prompt = Prompt(
            if (confirmationRevision == null) s(R.string.choose_same) else s(R.string.confirm_headphones),
            if (confirmationRevision == null) s(R.string.paired_headphones_help) else "Mac · ${target.optString("macName")}",
            choices = devices.map { device ->
                "${HeadphoneSelection.name(device)} · ${device.address.takeLast(5)}" to {
                    selectHeadphone(device, version, if (confirmationRevision == null) "propose" else "confirm")
                }
            },
            selected = devices.firstOrNull { it.address.equals(target.optString("phoneAddress"), true) }
                ?.let { "${HeadphoneSelection.name(it)} · ${it.address.takeLast(5)}" } ?: "",
            targetRevision = version,
            setupStep = if (setup) SetupStep.HEADPHONES else null,
        )
    }

    private fun exportLogs() {
        exporting = true
        work.execute {
            try {
                Diagnostics.export(this)
                ui.post {
                    exporting = false
                    if (isDestroyed) return@post
                    val uri =
                        Uri.parse(
                            "content://${packageName}.diagnostics/sound-link-diagnostics.zip"
                        )
                    val intent =
                        Intent(Intent.ACTION_SEND)
                            .setType("application/zip")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    intent.clipData = ClipData.newRawUri("Sound Link diagnostics", uri)
                    runCatching {
                        startActivity(
                            Intent.createChooser(
                                intent,
                                s(R.string.message_share_sound_link_logs),
                            )
                        )
                    }
                        .onFailure { alert(s(R.string.message_no_sharing_app_available)) }
                }
            } catch (e: Exception) {
                ui.post {
                    exporting = false
                    if (!isDestroyed)
                        alert(s(R.string.message_couldn_t_export_logs) + UserMessage.error(this, e))
                }
            }
        }
    }

    private fun checkUpdate() {
        updating = true
        updateStatus = s(R.string.message_checking)
        work.execute {
            try {
                val release = Updater.latest()
                require(
                    release.getString("package") == packageName &&
                        release.getInt("minSdk") <= Build.VERSION.SDK_INT
                )
                ui.post {
                    updating = false
                    if (isDestroyed) return@post
                    if (release.getInt("versionCode") <= BuildConfig.VERSION_CODE)
                        updateStatus =
                            s(R.string.message_version) +
                                BuildConfig.VERSION_NAME +
                                s(R.string.message_is_up_to_date)
                    else {
                        updateStatus = ""
                        prompt =
                            Prompt(
                                s(R.string.message_sound_link_update) +
                                    release.optString("versionName"),
                                s(R.string.message_download_and_install_from_cloud),
                                s(R.string.message_download_and_install),
                                { downloadUpdate(release) },
                            )
                    }
                }
            } catch (e: Exception) {
                ui.post {
                    updating = false
                    updateStatus =
                        s(R.string.message_couldn_t_check_for_updates) + UserMessage.error(this, e)
                }
            }
        }
    }

    private fun downloadUpdate(release: JSONObject) {
        updating = true
        work.execute {
            try {
                Updater.download(this, release)
                ui.post {
                    updating = false
                    if (isDestroyed) return@post
                    if (!packageManager.canRequestPackageInstalls())
                        prompt =
                            Prompt(
                                s(R.string.message_allow_update_installation),
                                s(
                                    R.string
                                        .message_allow_sound_link_to_install_apps_in_settings_then
                                ),
                                s(R.string.message_open_settings),
                                {
                                    startActivity(
                                        Intent(
                                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                            Uri.parse("package:$packageName"),
                                        )
                                    )
                                },
                            )
                    else Updater.install(this)
                }
            } catch (e: Exception) {
                ui.post {
                    updating = false
                    updateStatus = s(R.string.message_update_failed) + UserMessage.error(this, e)
                }
            }
        }
    }
}

@Composable
internal fun SoundLinkTheme(darkOverride: Boolean? = null, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) { Link.prefs(context) }
    var preference by
        remember(preferences) {
            mutableStateOf(preferences.getString("interface_appearance", "system") ?: "system")
        }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == "interface_appearance")
                preference = prefs.getString(key, "system") ?: "system"
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val dark =
        darkOverride
            ?: when (preference) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
    val activity = context as? ComponentActivity
    DisposableEffect(activity, dark) {
        val transparent = android.graphics.Color.TRANSPARENT
        val style =
            if (dark) SystemBarStyle.dark(transparent)
            else SystemBarStyle.light(transparent, transparent)
        activity?.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        onDispose {}
    }
    val colors =
        if (dark)
            darkColorScheme(
                primary = Color(0xff0a84ff), onPrimary = Color.White,
                primaryContainer = Color(0xff173657), onPrimaryContainer = Color(0xffd6eaff),
                surfaceContainerHighest = Color(0xff3a3a3d), surfaceTint = Color(0xff0a84ff),
                background = Color(0xff1c1c1f), onBackground = Color(0xfff5f5f7),
                surface = Color(0xff2b2b2e), onSurface = Color(0xfff5f5f7),
                onSurfaceVariant = Color(0xffababaf),
                surfaceVariant = Color(0xff3a3a3d), outline = Color(0xff636366),
                outlineVariant = Color(0xff414145),
                secondary = Color(0xffababaf), secondaryContainer = Color(0xff3a3a3d),
                onSecondaryContainer = Color(0xfff5f5f7),
                error = Color(0xffff453a), onError = Color.White,
            )
        else
            lightColorScheme(
                primary = Color(0xff007aff), onPrimary = Color.White,
                primaryContainer = Color(0xffd6eaff), onPrimaryContainer = Color(0xff003866),
                surfaceContainerHighest = Color(0xffe5e5ea), surfaceTint = Color(0xff007aff),
                background = Color(0xffedf0f5), onBackground = Color(0xff1c1c1e),
                surface = Color.White, onSurface = Color(0xff1c1c1e),
                onSurfaceVariant = Color(0xff68686d),
                surfaceVariant = Color(0xffe5e5ea), outline = Color(0xffaeaeb2),
                outlineVariant = Color(0xffd4d9e0),
                secondary = Color(0xff68686d), secondaryContainer = Color(0xffe5e5ea),
                onSecondaryContainer = Color(0xff1c1c1e),
                error = Color(0xffff3b30), onError = Color.White,
            )
    MaterialTheme(colorScheme = colors, content = content)
}

internal fun appearanceLabel(mode: String): Int =
    when (mode) {
        "light" -> R.string.appearance_light
        "dark" -> R.string.appearance_dark
        else -> R.string.system_language
    }
