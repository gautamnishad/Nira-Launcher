package com.example.niagaraclone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// 1. DATA MODEL & STORAGE HELPER
// ---------------------------------------------------------------------------

data class AppModel(
    val label: String,
    val packageName: String,
    val icon: ImageBitmap,
    val indexLetter: String
)

enum class LauncherMode {
    FAVORITES,
    ALL_APPS,
    SEARCH
}

private const val PREFS_NAME = "niagara_clone_prefs"
private const val KEY_FAVORITES = "favorite_packages"

fun getSavedFavorites(context: Context): Set<String> {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return prefs.getStringSet(KEY_FAVORITES, null) ?: emptySet()
}

fun saveFavorites(context: Context, favorites: Set<String>) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit().putStringSet(KEY_FAVORITES, favorites).apply()
}

// ---------------------------------------------------------------------------
// 2. MAIN ACTIVITY
// ---------------------------------------------------------------------------

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color.White,
                    surface = Color(0xFF161618),
                    onSurface = Color.White
                )
            ) {
                NiagaraHomeScreen()
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 3. MAIN LAUNCHER SCREEN
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NiagaraHomeScreen() {
    val context = LocalContext.current
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()

    var allApps by remember { mutableStateOf<List<AppModel>>(emptyList()) }
    var favoritePackages by remember { mutableStateOf(getSavedFavorites(context)) }
    var launcherMode by remember { mutableStateOf(LauncherMode.FAVORITES) }
    var selectedAppForMenu by remember { mutableStateOf<AppModel?>(null) }
    var reloadTrigger by remember { mutableIntStateOf(0) }

    val allAppsListState = rememberLazyListState()
    val alphabetIndices = remember {
        listOf("★") + ('A'..'Z').map { it.toString() } + listOf("#")
    }

    // Load all launchable apps on IO thread
    LaunchedEffect(reloadTrigger) {
        val loaded = loadInstalledApps(context)
        allApps = loaded

        // Auto-populate up to 6 common apps on very first launch
        if (favoritePackages.isEmpty() && loaded.isNotEmpty()) {
            val defaultFavs = loaded.take(6).map { it.packageName }.toSet()
            favoritePackages = defaultFavs
            saveFavorites(context, defaultFavs)
        }
    }

    // Listen for app installs / uninstalls dynamically
    DisposableEffect(context) {
        val packageReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                reloadTrigger++
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        context.registerReceiver(packageReceiver, filter)
        onDispose {
            context.unregisterReceiver(packageReceiver)
        }
    }

    // Back press returns to Favorites from All Apps or Search; does nothing on Favorites
    BackHandler(enabled = true) {
        if (launcherMode != LauncherMode.FAVORITES) {
            launcherMode = LauncherMode.FAVORITES
        }
    }

    val favoriteApps = remember(allApps, favoritePackages) {
        allApps.filter { favoritePackages.contains(it.packageName) }
    }

    // Map each alphabet character to its first index in the LazyColumn (accounting for 1 header item)
    val letterPositionMap = remember(allApps) {
        val map = mutableMapOf<String, Int>()
        allApps.forEachIndexed { index, app ->
            if (!map.containsKey(app.indexLetter)) {
                map[app.indexLetter] = index + 1 // +1 because index 0 is the header
            }
        }
        map
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.35f),
                        Color.Black.copy(alpha = 0.55f)
                    )
                )
            )
            .windowInsetsPadding(WindowInsets.systemBars)
    ) {
        // Main Content Area
        when (launcherMode) {
            LauncherMode.FAVORITES -> {
                FavoritesHomeView(
                    favoriteApps = favoriteApps,
                    onAppClick = { launchApp(context, it.packageName) },
                    onAppLongClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        selectedAppForMenu = it
                    },
                    onOpenSearch = { launcherMode = LauncherMode.SEARCH }
                )
            }

            LauncherMode.ALL_APPS -> {
                AllAppsListView(
                    allApps = allApps,
                    listState = allAppsListState,
                    onAppClick = { launchApp(context, it.packageName) },
                    onAppLongClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        selectedAppForMenu = it
                    },
                    onOpenSearch = { launcherMode = LauncherMode.SEARCH },
                    onBackgroundTap = { launcherMode = LauncherMode.FAVORITES }
                )
            }

            LauncherMode.SEARCH -> {
                SearchOverlayView(
                    allApps = allApps,
                    onAppClick = {
                        launchApp(context, it.packageName)
                        launcherMode = LauncherMode.FAVORITES
                    },
                    onAppLongClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        selectedAppForMenu = it
                    },
                    onDismiss = { launcherMode = LauncherMode.FAVORITES }
                )
            }
        }

        // Signature Right-Edge Wave Alphabet Scrollbar (hidden during search)
        if (launcherMode != LauncherMode.SEARCH) {
            AlphabetWaveScrollbar(
                letters = alphabetIndices,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight(0.76f)
                    .width(56.dp),
                onLetterSelected = { selectedLetter ->
                    if (selectedLetter == "★") {
                        launcherMode = LauncherMode.FAVORITES
                    } else {
                        if (launcherMode != LauncherMode.ALL_APPS) {
                            launcherMode = LauncherMode.ALL_APPS
                        }
                        val targetIndex = letterPositionMap[selectedLetter]
                        if (targetIndex != null) {
                            coroutineScope.launch {
                                allAppsListState.scrollToItem(targetIndex)
                            }
                        }
                    }
                }
            )
        }

        // Long-Press App Options Bottom Sheet
        selectedAppForMenu?.let { targetApp ->
            val isFav = favoritePackages.contains(targetApp.packageName)
            ModalBottomSheet(
                onDismissRequest = { selectedAppForMenu = null },
                containerColor = Color(0xFF1C1C1E),
                contentColor = Color.White
            ) {
                AppOptionsSheetContent(
                    app = targetApp,
                    isFavorite = isFav,
                    onToggleFavorite = {
                        val updated = favoritePackages.toMutableSet()
                        if (isFav) updated.remove(targetApp.packageName)
                        else updated.add(targetApp.packageName)
                        favoritePackages = updated
                        saveFavorites(context, updated)
                        selectedAppForMenu = null
                    },
                    onOpenAppInfo = {
                        openAppSettings(context, targetApp.packageName)
                        selectedAppForMenu = null
                    },
                    onUninstallApp = {
                        uninstallApp(context, targetApp.packageName)
                        selectedAppForMenu = null
                    }
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 4. MINIMAL HEADER (LIVE CLOCK, DATE & BATTERY)
// ---------------------------------------------------------------------------

@Composable
fun MinimalClockBatteryHeader(
    modifier: Modifier = Modifier,
    onSearchClick: () -> Unit
) {
    val context = LocalContext.current
    var currentTime by remember { mutableStateOf(formatTime(Date())) }
    var currentDate by remember { mutableStateOf(formatDate(Date())) }
    var batteryPct by remember { mutableIntStateOf(getBatteryLevel(context)) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val now = Date()
                currentTime = formatTime(now)
                currentDate = formatDate(now)
                if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (level >= 0 && scale > 0) {
                        batteryPct = (level * 100) / scale
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        context.registerReceiver(receiver, filter)
        onDispose { context.unregisterReceiver(receiver) }
    }

    val textShadow = Shadow(
        color = Color.Black.copy(alpha = 0.6f),
        offset = Offset(1f, 2f),
        blurRadius = 6f
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 28.dp, end = 64.dp, top = 28.dp, bottom = 12.dp)
    ) {
        Text(
            text = currentTime,
            style = TextStyle(
                color = Color.White,
                fontSize = 48.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = (-1).sp,
                shadow = textShadow
            )
        )
        Spacer(modifier = Modifier.height(2.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "$currentDate  •  $batteryPct%",
                style = TextStyle(
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    shadow = textShadow
                )
            )
            IconButton(
                onClick = onSearchClick,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search Apps",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 5. FAVORITES VIEW & ALL-APPS LIST VIEW
// ---------------------------------------------------------------------------

@Composable
fun FavoritesHomeView(
    favoriteApps: List<AppModel>,
    onAppClick: (AppModel) -> Unit,
    onAppLongClick: (AppModel) -> Unit,
    onOpenSearch: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        MinimalClockBatteryHeader(onSearchClick = onOpenSearch)

        // Vertically centered ergonomic left-aligned list
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(end = 56.dp),
            verticalArrangement = Arrangement.Center,
            contentPadding = PaddingValues(vertical = 24.dp)
        ) {
            if (favoriteApps.isEmpty()) {
                item {
                    Text(
                        text = "Slide the right alphabet bar and long-press any app to pin it to Favorites.",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 15.sp,
                        modifier = Modifier.padding(horizontal = 28.dp)
                    )
                }
            } else {
                items(favoriteApps, key = { it.packageName }) { app ->
                    NiagaraAppRow(
                        app = app,
                        onClick = { onAppClick(app) },
                        onLongClick = { onAppLongClick(app) }
                    )
                }
            }
        }

        // Subtle Bottom Search Pill
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 28.dp, bottom = 20.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Surface(
                onClick = onOpenSearch,
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.14f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Search apps",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
fun AllAppsListView(
    allApps: List<AppModel>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onAppClick: (AppModel) -> Unit,
    onAppLongClick: (AppModel) -> Unit,
    onOpenSearch: () -> Unit,
    onBackgroundTap: () -> Unit
) {
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(end = 56.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onBackgroundTap
            ),
        contentPadding = PaddingValues(bottom = 80.dp)
    ) {
        item {
            MinimalClockBatteryHeader(onSearchClick = onOpenSearch)
        }

        items(
            items = allApps,
            key = { it.packageName }
        ) { app ->
            val index = allApps.indexOf(app)
            val showSectionHeader = index == 0 || allApps[index - 1].indexLetter != app.indexLetter

            if (showSectionHeader) {
                Text(
                    text = app.indexLetter,
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 32.dp, top = 16.dp, bottom = 4.dp)
                )
            }

            NiagaraAppRow(
                app = app,
                onClick = { onAppClick(app) },
                onLongClick = { onAppLongClick(app) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NiagaraAppRow(
    app: AppModel,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 28.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            bitmap = app.icon,
            contentDescription = app.label,
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(10.dp))
        )
        Spacer(modifier = Modifier.width(18.dp))
        Text(
            text = app.label,
            style = TextStyle(
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                shadow = Shadow(
                    color = Color.Black.copy(alpha = 0.55f),
                    offset = Offset(1f, 1f),
                    blurRadius = 4f
                )
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ---------------------------------------------------------------------------
// 6. SIGNATURE RIGHT-EDGE ALPHABET WAVE SCROLLBAR
// ---------------------------------------------------------------------------

@Composable
fun AlphabetWaveScrollbar(
    letters: List<String>,
    modifier: Modifier = Modifier,
    onLetterSelected: (String) -> Unit
) {
    val view = LocalView.current
    val density = LocalDensity.current

    var barHeightPx by remember { mutableFloatStateOf(1f) }
    var touchY by remember { mutableStateOf<Float?>(null) }
    var activeIndex by remember { mutableIntStateOf(-1) }

    val maxWaveRadiusPx = with(density) { 95.dp.toPx() }
    val maxHorizontalPullPx = with(density) { 54.dp.toPx() }
    val bubbleOffsetXPx = with(density) { (-96).dp.toPx() }

    Box(
        modifier = modifier
            .onGloballyPositioned { coordinates ->
                barHeightPx = coordinates.size.height.toFloat().coerceAtLeast(1f)
            }
            .pointerInput(letters) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    touchY = down.position.y

                    fun processTouch(yPos: Float) {
                        val clampedY = yPos.coerceIn(0f, barHeightPx)
                        touchY = clampedY
                        val step = barHeightPx / letters.size
                        val newIndex = (clampedY / step).toInt().coerceIn(0, letters.lastIndex)
                        if (newIndex != activeIndex) {
                            activeIndex = newIndex
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            onLetterSelected(letters[newIndex])
                        }
                    }

                    processTouch(down.position.y)

                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull()
                        if (change != null && change.pressed) {
                            change.consume()
                            processTouch(change.position.y)
                        }
                    } while (event.changes.any { it.pressed })

                    touchY = null
                    activeIndex = -1
                }
            },
        contentAlignment = Alignment.CenterEnd
    ) {
        // Floating active letter indicator bubble near the user's thumb
        val currentTouchY = touchY
        if (currentTouchY != null && activeIndex in letters.indices) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset {
                        IntOffset(
                            x = bubbleOffsetXPx.roundToInt(),
                            y = (currentTouchY - with(density) { 26.dp.toPx() }).roundToInt()
                        )
                    }
                    .size(52.dp)
                    .background(Color.White.copy(alpha = 0.92f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = letters[activeIndex],
                    color = Color.Black,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Vertical column of letters with dynamic Raised-Cosine Wave offset
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(end = 8.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            letters.forEachIndexed { index, letter ->
                val itemCenterY = (index + 0.5f) * (barHeightPx / letters.size)

                val waveFactor = if (currentTouchY != null) {
                    val distance = abs(currentTouchY - itemCenterY)
                    if (distance < maxWaveRadiusPx) {
                        val normalized = distance / maxWaveRadiusPx
                        ((cos(normalized * Math.PI) + 1.0) / 2.0).toFloat()
                    } else {
                        0f
                    }
                } else {
                    0f
                }

                val isSelected = index == activeIndex

                Text(
                    text = letter,
                    color = if (isSelected) Color.White else Color.White.copy(alpha = 0.72f),
                    fontSize = 11.sp,
                    fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                    modifier = Modifier.graphicsLayer {
                        translationX = -waveFactor * maxHorizontalPullPx
                        val scale = 1f + (waveFactor * 0.75f)
                        scaleX = scale
                        scaleY = scale
                    }
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 7. INSTANT APP SEARCH OVERLAY
// ---------------------------------------------------------------------------

@Composable
fun SearchOverlayView(
    allApps: List<AppModel>,
    onAppClick: (AppModel) -> Unit,
    onAppLongClick: (AppModel) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    val filteredApps = remember(query, allApps) {
        if (query.isBlank()) allApps
        else allApps.filter { it.label.contains(query.trim(), ignoreCase = true) }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(top = 16.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = {
                Text("Search apps...", color = Color.White.copy(alpha = 0.5f))
            },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = null, tint = Color.White)
            },
            trailingIcon = {
                IconButton(onClick = {
                    if (query.isNotEmpty()) query = "" else onDismiss()
                }) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(
                onGo = {
                    filteredApps.firstOrNull()?.let {
                        focusManager.clearFocus()
                        onAppClick(it)
                    }
                }
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color.White.copy(alpha = 0.4f),
                unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                cursorColor = Color.White
            ),
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .focusRequester(focusRequester)
        )

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            items(filteredApps, key = { it.packageName }) { app ->
                NiagaraAppRow(
                    app = app,
                    onClick = { onAppClick(app) },
                    onLongClick = { onAppLongClick(app) }
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 8. LONG-PRESS BOTTOM SHEET MENU
// ---------------------------------------------------------------------------

@Composable
fun AppOptionsSheetContent(
    app: AppModel,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onUninstallApp: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 20.dp)
        ) {
            Image(
                bitmap = app.icon,
                contentDescription = app.label,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = app.label,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = app.packageName,
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

        ListItem(
            headlineContent = {
                Text(
                    if (isFavorite) "Remove from Favorites" else "Add to Favorites",
                    color = Color.White
                )
            },
            leadingContent = {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Star else Icons.Outlined.StarBorder,
                    contentDescription = null,
                    tint = if (isFavorite) Color(0xFFFFD54F) else Color.White
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable(onClick = onToggleFavorite)
        )

        ListItem(
            headlineContent = { Text("App Info", color = Color.White) },
            leadingContent = {
                Icon(Icons.Default.Info, contentDescription = null, tint = Color.White)
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable(onClick = onOpenAppInfo)
        )

        ListItem(
            headlineContent = { Text("Uninstall", color = Color(0xFFFF6B6B)) },
            leadingContent = {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = null,
                    tint = Color(0xFFFF6B6B)
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable(onClick = onUninstallApp)
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------------------
// 9. SYSTEM UTILITIES & PACKAGE MANAGER HELPERS
// ---------------------------------------------------------------------------

suspend fun loadInstalledApps(context: Context): List<AppModel> = withContext(Dispatchers.IO) {
    val pm: PackageManager = context.packageManager
    val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
    }

    val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
    val myPackage = context.packageName

    resolveInfos
        .asSequence()
        .mapNotNull { info ->
            val pkgName = info.activityInfo.packageName
            if (pkgName == myPackage) return@mapNotNull null

            val label = info.loadLabel(pm).toString().trim()
            val drawable = info.loadIcon(pm)
            val iconBitmap = drawableToImageBitmap(drawable, sizePx = 128)
            val firstChar = label.firstOrNull()?.uppercaseChar()
            val indexLetter = if (firstChar != null && firstChar in 'A'..'Z') {
                firstChar.toString()
            } else {
                "#"
            }

            AppModel(
                label = label,
                packageName = pkgName,
                icon = iconBitmap,
                indexLetter = indexLetter
            )
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase(Locale.getDefault()) }
        .toList()
}

fun drawableToImageBitmap(drawable: Drawable, sizePx: Int): ImageBitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap.asImageBitmap()
}

fun launchApp(context: Context, packageName: String) {
    val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
    if (launchIntent != null) {
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
    } else {
        Toast.makeText(context, "Unable to launch app", Toast.LENGTH_SHORT).show()
    }
}

fun openAppSettings(context: Context, packageName: String) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

fun uninstallApp(context: Context, packageName: String) {
    val intent = Intent(Intent.ACTION_DELETE).apply {
        data = Uri.fromParts("package", packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

private fun formatTime(date: Date): String {
    return SimpleDateFormat("h:mm", Locale.getDefault()).format(date)
}

private fun formatDate(date: Date): String {
    return SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(date)
}

private fun getBatteryLevel(context: Context): Int {
    val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    return bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
}
