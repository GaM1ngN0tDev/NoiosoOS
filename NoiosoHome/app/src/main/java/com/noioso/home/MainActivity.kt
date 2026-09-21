package com.noioso.home

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.absoluteValue

/* ============================================================
   NoiosoLauncher — nero, testo bianco, niente icone.
   Pagina 1: Telefono / Messaggi / Fotocamera / Galleria
   Pagine successive (scroll in giù): tutte le altre app
   Pallini sulla destra = indicatore di pagina
   Tap lungo su un nome = info app (per disinstallare)
   ============================================================ */

private const val APPS_PER_PAGE = 6

data class AppEntry(
    val label: String,
    val packageName: String?,
    val intent: Intent
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.statusBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent { LauncherScreen() }
    }

    /** Sul launcher il tasto "indietro" non deve fare nulla. */
    override fun onBackPressed() = Unit

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    private fun hideStatusBar() {
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.statusBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

/* ------------------------------------------------------------
   Caricamento delle app
   ------------------------------------------------------------ */

private fun coreApps(ctx: Context): List<AppEntry> {
    val pm = ctx.packageManager
    val out = mutableListOf<AppEntry>()

    fun add(label: String, intent: Intent?) {
        if (intent == null) return
        val info = intent.resolveActivity(pm) ?: return
        out += AppEntry(label, info.packageName, intent)
    }

    add(ctx.getString(R.string.app_phone), Intent(Intent.ACTION_DIAL))

    val smsPkg = Telephony.Sms.getDefaultSmsPackage(ctx)
    add(
        ctx.getString(R.string.app_messages),
        smsPkg?.let { pm.getLaunchIntentForPackage(it) }
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("sms:"))
    )

    add(ctx.getString(R.string.app_camera), Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))

    add(
        ctx.getString(R.string.app_gallery),
        Intent(Intent.ACTION_VIEW).setDataAndType(
            Uri.parse("content://media/internal/images/media"), "image/*"
        )
    )

    return out
}

/**
 * Parole chiave nel nome del pacchetto per riconoscere le app "utili"
 * (email, mappe, calendario, produttività...) e quelle "distraenti"
 * (social, video, giochi). Non trovato in nessuna delle due -> neutro.
 * Aggiungi/togli parole qui se una tua app finisce nel gruppo sbagliato.
 */
private val PRIORITY_KEYWORDS = listOf(
    "settings", "contacts", "security", "gmail", "outlook", "calendar",
    "maps", "drive", "docs", "sheets", "slides", "keep", "notes",
    "translate", "photos", "files", "wallet", "bank", "calculator",
    "clock", "health", "fit", "weather", "pay", "authenticator",
    "onedrive", "office"
)

private val DISTRACTING_KEYWORDS = listOf(
    "instagram", "facebook", "tiktok", "snapchat", "twitter", "x.com",
    "reddit", "discord", "youtube", "netflix", "spotify", "pinterest",
    "whatsapp", "telegram", "tinder", "game", "geometry", "roblox",
    "twitch", "prime", "disney", "hbo"
)

/** 0 = importante, 1 = neutro, 2 = distraente */
private fun importanceRank(pkg: String, category: Int): Int {
    val lower = pkg.lowercase(Locale.getDefault())
    if (lower.contains("com.android.settings") || lower.endsWith(".settings")) return -1
    if (DISTRACTING_KEYWORDS.any { lower.contains(it) }) return 2
    if (PRIORITY_KEYWORDS.any { lower.contains(it) }) return 0

    return when (category) {
        ApplicationInfo.CATEGORY_SOCIAL,
        ApplicationInfo.CATEGORY_GAME,
        ApplicationInfo.CATEGORY_VIDEO -> 2
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> 0
        else -> 1
    }
}

private fun otherApps(ctx: Context, exclude: Set<String>): List<AppEntry> {
    val pm = ctx.packageManager
    val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    return pm.queryIntentActivities(main, 0)
        .asSequence()
        .map { it.activityInfo }
        .filter { it.packageName != ctx.packageName && it.packageName !in exclude }
        .distinctBy { it.packageName }
        .mapNotNull { info ->
            val label = pm.getApplicationLabel(info.applicationInfo).toString()
                .lowercase(Locale.getDefault())
            pm.getLaunchIntentForPackage(info.packageName)?.let { intent ->
                Triple(
                    AppEntry(label, info.packageName, intent),
                    importanceRank(info.packageName, info.applicationInfo.category),
                    label
                )
            }
        }
        .sortedWith(compareBy({ it.second }, { it.third }))
        .map { it.first }
        .toList()
}

/* ------------------------------------------------------------
   Notifiche attive — richiede il permesso "Accesso notifiche"
   ------------------------------------------------------------ */

object NotificationRepo {
    // package -> quante notifiche attive per quell'app
    val counts = mutableStateMapOf<String, Int>()

    fun update(list: List<StatusBarNotification>) {
        val grouped = list
            .filter { it.notification.flags and Notification.FLAG_ONGOING_EVENT == 0 }
            .groupingBy { it.packageName }
            .eachCount()
        counts.clear()
        counts.putAll(grouped)
    }
}

class NoiosoNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        NotificationRepo.update(activeNotifications?.toList() ?: emptyList())
    }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        NotificationRepo.update(activeNotifications?.toList() ?: emptyList())
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        NotificationRepo.update(activeNotifications?.toList() ?: emptyList())
    }
}

private fun notificationAccessGranted(ctx: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

@Composable
private fun NotificationRow() {
    val ctx = LocalContext.current
    val counts = NotificationRepo.counts
    var granted by remember { mutableStateOf(notificationAccessGranted(ctx)) }

    // ricontrolla il permesso quando torni sul launcher (es. dopo averlo attivato nelle impostazioni)
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                granted = notificationAccessGranted(ctx)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    if (!granted) {
        Text(
            text = stringResource(R.string.enable_notification_access),
            color = Color(0xFF6E6E6E),
            fontSize = 12.sp,
            modifier = Modifier.clickable {
                runCatching {
                    ctx.startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        )
        return
    }

    if (counts.isEmpty()) return

    val pm = ctx.packageManager
    val maxShown = 5
    val packages = counts.keys.toList()
    val shown = packages.take(maxShown)
    val extra = packages.size - shown.size

    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        shown.forEach { pkg ->
            val icon: Drawable? = remember(pkg) {
                runCatching { pm.getApplicationIcon(pkg) }.getOrNull()
            }
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1C1C1C)),
                contentAlignment = Alignment.Center
            ) {
                if (icon != null) {
                    Image(
                        painter = BitmapPainter(icon.toBitmap(96, 96).asImageBitmap()),
                        contentDescription = null,
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                    )
                }
            }
        }

        if (extra > 0) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2A2A2A)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "+$extra",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

private val NoiosoDarkScheme = darkColorScheme(
    background = Color.Black,
    surface = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White,
    primary = Color.White
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LauncherScreen() {
    MaterialTheme(colorScheme = NoiosoDarkScheme) {
        LauncherContent()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LauncherContent() {
    val ctx = LocalContext.current

    val pages: List<List<AppEntry>> = remember {
        val core = coreApps(ctx)
        val excluded = core.mapNotNull { it.packageName }.toSet()
        listOf(core) + otherApps(ctx, excluded).chunked(APPS_PER_PAGE)
    }

    val pagerState = rememberPagerState { pages.size }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {

        VerticalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->

            // le pagine sfumano mentre scorri
            val offset = ((pagerState.currentPage - page) +
                    pagerState.currentPageOffsetFraction).absoluteValue
            val fade = (1f - offset).coerceIn(0f, 1f)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 40.dp, end = 36.dp)
                    .alpha(fade),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (page == 0) {
                    Clock()
                    Spacer(Modifier.height(18.dp))
                    NotificationRow()
                    Spacer(Modifier.height(38.dp))
                }

                pages[page].forEach { app ->
                    AppLabel(app)
                }
            }
        }

        PageDots(
            count = pages.size,
            current = pagerState.currentPage,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp)
        )
    }
}

@Composable
private fun Clock() {
    var now by remember { mutableStateOf(LocalDateTime.now()) }

    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(10_000)
        }
    }

    val time = remember(now.hour, now.minute) {
        now.format(DateTimeFormatter.ofPattern("HH:mm"))
    }
    val date = remember(now.dayOfYear) {
        now.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()))
    }

    Text(
        text = time,
        color = Color.White,
        fontSize = 72.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-1).sp,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = date.lowercase(Locale.getDefault()),
        color = Color(0xFF9A9A9A),
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.3.sp,
        textAlign = TextAlign.Center
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppLabel(app: AppEntry) {
    val ctx = LocalContext.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    // schermi più stretti (es. Pixel 7a ~360dp) -> testo un po' più grande
    val appFontSize = if (screenWidthDp <= 400) 26.sp else 22.sp
    val haptics = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }

    val color by animateFloatAsState(
        targetValue = if (pressed) 0.45f else 1f,
        animationSpec = tween(120),
        label = "press"
    )

    val displayLabel = remember(app.label) {
        app.label.split(" ").joinToString(" ") { word ->
            word.replaceFirstChar { it.titlecase(Locale.getDefault()) }
        }
    }

    Text(
        text = displayLabel,
        color = Color.White,
        fontSize = appFontSize,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.1.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .alpha(color)
            .padding(vertical = 10.dp)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    pressed = true
                    runCatching {
                        ctx.startActivity(
                            Intent(app.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                    pressed = false
                },
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    app.packageName?.let { pkg ->
                        runCatching {
                            ctx.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    .setData(Uri.parse("package:$pkg"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                }
            )
    )
}

@Composable
private fun PageDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    if (count <= 1) return

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(7.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        repeat(count) { i ->
            val active = i == current
            val size by animateDpAsState(
                targetValue = if (active) 7.dp else 4.dp,
                animationSpec = tween(200),
                label = "dot"
            )
            val alpha by animateFloatAsState(
                targetValue = if (active) 1f else 0.28f,
                animationSpec = tween(200),
                label = "dotAlpha"
            )
            Box(
                Modifier
                    .size(size)
                    .alpha(alpha)
                    .background(Color.White, CircleShape)
            )
        }
    }
}