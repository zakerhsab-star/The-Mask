package com.example.appfreezer

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

private data class AppEntry(val label: String, val pkg: String)

class MainActivity : ComponentActivity() {

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, _ -> recreate() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        Shizuku.addRequestPermissionResultListener(permListener)
        val store = FrozenStore(this)
        val freezer = AppFreezer(store)
        val prefs = Prefs(this)
        val icons = IconCache(this)

        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaskTheme {
                    Box(Modifier.fillMaxSize().background(InkBackdrop)) {
                        Root(store, freezer, prefs, icons)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permListener)
        super.onDestroy()
    }

    @Composable
    private fun Root(store: FrozenStore, freezer: AppFreezer, prefs: Prefs, icons: IconCache) {
        var showSettings by remember { mutableStateOf(false) }
        BackHandler(enabled = showSettings) { showSettings = false }
        Crossfade(targetState = showSettings, label = "screen") { settings ->
            if (settings) SettingsScreen(prefs) { showSettings = false }
            else HomeScreen(store, freezer, prefs, icons) { showSettings = true }
        }
    }

    // ───────────── الشاشة الرئيسية ─────────────

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun HomeScreen(
        store: FrozenStore,
        freezer: AppFreezer,
        prefs: Prefs,
        icons: IconCache,
        onSettings: () -> Unit
    ) {
        val scope = rememberCoroutineScope()
        val snackbar = remember { SnackbarHostState() }
        val mode = remember { prefs.mode }
        var tab by remember { mutableIntStateOf(0) }
        var query by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var frozen by remember { mutableStateOf(store.all()) }
        var installed by remember { mutableStateOf<List<AppEntry>?>(null) }
        var tick by remember { mutableIntStateOf(0) }
        val ready = ShizukuShell.isReady()
        val granted = ready && ShizukuShell.hasPermission()

        LaunchedEffect(tick) {
            val frozenNow = store.all()
            frozen = frozenNow
            installed = withContext(Dispatchers.IO) { loadInstalled(frozenNow.map { it.pkg }.toSet()) }
        }

        fun act(okMsg: String, block: () -> ShellResult) {
            if (busy) return
            busy = true
            scope.launch {
                val r = withContext(Dispatchers.IO) { block() }
                busy = false
                tick++
                snackbar.showSnackbar(
                    if (r.ok && !r.out.contains("Failure", true)) okMsg
                    else "تعذّر التنفيذ: ${(r.out + " " + r.err).trim().take(120)}"
                )
            }
        }

        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets.systemBars,
            snackbarHost = {
                SnackbarHost(snackbar) {
                    Snackbar(
                        it,
                        containerColor = Palette.VelvetHi,
                        contentColor = Palette.Porcelain,
                        shape = RoundedCornerShape(14.dp)
                    )
                }
            }
        ) { pad ->
            val q = query.trim()
            val frozenShown = frozen.filter { q.isEmpty() || it.label.contains(q, true) || it.pkg.contains(q, true) }
            val installedShown = installed.orEmpty()
                .filter { q.isEmpty() || it.label.contains(q, true) || it.pkg.contains(q, true) }

            LazyColumn(
                contentPadding = PaddingValues(
                    start = 20.dp, end = 20.dp,
                    top = pad.calculateTopPadding() + 8.dp,
                    bottom = pad.calculateBottomPadding() + 24.dp
                ),
                modifier = Modifier.fillMaxSize()
            ) {
                // الترويسة
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MaskLogo(46.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("The Mask", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "اخفِ أي تطبيق وابقِ على بياناته",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = onSettings,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Palette.Velvet)
                        ) {
                            Icon(Icons.Filled.Settings, contentDescription = "الإعدادات", tint = Palette.Brass)
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }

                // حالة Shizuku
                item {
                    StatusStrip(
                        ready = ready,
                        granted = granted,
                        modeLabel = mode.label,
                        frozenCount = frozen.size,
                        onRequest = { Shizuku.requestPermission(1) },
                        onOpenShizuku = {
                            packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                                ?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }
                    )
                    Spacer(Modifier.height(20.dp))
                }

                // التبويبات
                item {
                    Row(Modifier.fillMaxWidth()) {
                        TabLabel("المخفية", frozen.size, tab == 0, Modifier.weight(1f)) { tab = 0 }
                        TabLabel("كل التطبيقات", null, tab == 1, Modifier.weight(1f)) { tab = 1 }
                    }
                    HorizontalDivider(color = Palette.VelvetHi)
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("ابحث باسم التطبيق") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Palette.Velvet,
                            unfocusedContainerColor = Palette.Velvet,
                            focusedBorderColor = Palette.Brass,
                            unfocusedBorderColor = Color.Transparent,
                            cursorColor = Palette.Brass
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(14.dp))
                }

                if (tab == 0) {
                    if (frozenShown.isEmpty()) {
                        item {
                            EmptyState(
                                if (frozen.isEmpty()) "لا توجد تطبيقات مخفية" else "لا نتائج",
                                if (frozen.isEmpty()) "افتح \"كل التطبيقات\" واضغط إخفاء على أي تطبيق." else "جرّب اسماً آخر."
                            )
                        }
                    }
                    items(frozenShown, key = { "f" + it.pkg }) { app ->
                        FrozenCard(app, icons, granted && !busy, Modifier.animateItem()) {
                            act("تمت استعادة ${app.label}") { freezer.restore(app) }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                } else {
                    if (installed == null) {
                        item { EmptyState("جارٍ التحميل", "ثوانٍ ونعرض تطبيقاتك.") }
                    } else if (installedShown.isEmpty()) {
                        item { EmptyState("لا نتائج", "جرّب اسماً آخر.") }
                    }
                    items(installedShown, key = { "i" + it.pkg }) { app ->
                        InstalledRow(app, icons, granted && !busy, Modifier.animateItem()) {
                            act("تم إخفاء ${app.label}") { freezer.freeze(app.pkg, app.label, mode) }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun StatusStrip(
        ready: Boolean,
        granted: Boolean,
        modeLabel: String,
        frozenCount: Int,
        onRequest: () -> Unit,
        onOpenShizuku: () -> Unit
    ) {
        val (dot, title, sub) = when {
            !ready -> Triple(Palette.Ember, "Shizuku غير شغال", "شغّله من تطبيق Shizuku عبر Wireless debugging.")
            !granted -> Triple(Palette.Brass, "نحتاج إذن Shizuku", "اسمح للتطبيق بتنفيذ الأوامر.")
            else -> Triple(Palette.Sage, "جاهز للعمل", "الطريقة: $modeLabel · المخفية: $frozenCount")
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Palette.Velvet)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                !ready -> OutlinedButton(
                    onClick = onOpenShizuku,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Palette.Brass),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.Brass)
                ) { Text("فتح Shizuku") }
                !granted -> Button(
                    onClick = onRequest,
                    shape = RoundedCornerShape(12.dp)
                ) { Text("السماح") }
            }
        }
    }

    @Composable
    private fun TabLabel(text: String, count: Int?, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
        Column(
            modifier.clickable(onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text,
                    fontSize = 15.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) Palette.Porcelain else Palette.Mist
                )
                if (count != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "$count",
                        fontSize = 12.sp,
                        color = Palette.Ink,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (selected) Palette.Brass else Palette.Mist)
                            .padding(horizontal = 7.dp, vertical = 1.dp)
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(if (selected) Palette.Brass else Color.Transparent)
            )
        }
    }

    /** التطبيق المخفي: بطاقة بإطار نحاسي خفيف وأيقونة بلا ألوان. */
    @Composable
    private fun FrozenCard(app: FrozenApp, icons: IconCache, enabled: Boolean, modifier: Modifier, onRestore: () -> Unit) {
        Row(
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Palette.Velvet)
                .border(1.dp, Palette.Brass.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(app.pkg, app.label, icons, veiled = true, size = 48.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(app.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "مخفي · ${app.mode.label}",
                    fontSize = 12.sp,
                    color = Palette.Brass
                )
            }
            Button(
                onClick = onRestore,
                enabled = enabled,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Palette.Porcelain,
                    contentColor = Palette.Ink,
                    disabledContainerColor = Palette.VelvetHi,
                    disabledContentColor = Palette.Mist
                )
            ) { Text("استعادة", fontWeight = FontWeight.SemiBold) }
        }
    }

    /** التطبيق العادي: صف مسطح بفاصل رفيع، عشان القائمة الطويلة تفضل مريحة. */
    @Composable
    private fun InstalledRow(app: AppEntry, icons: IconCache, enabled: Boolean, modifier: Modifier, onHide: () -> Unit) {
        Column(modifier) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIcon(app.pkg, app.label, icons, veiled = false, size = 44.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    Text(
                        app.pkg,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                OutlinedButton(
                    onClick = onHide,
                    enabled = enabled,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, if (enabled) Palette.Brass else Palette.VelvetHi),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.Brass)
                ) { Text("إخفاء") }
            }
            HorizontalDivider(color = Palette.VelvetHi.copy(alpha = 0.6f))
        }
    }

    @Composable
    private fun EmptyState(title: String, hint: String) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            MaskLogo(64.dp)
            Spacer(Modifier.height(14.dp))
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Spacer(Modifier.height(4.dp))
            Text(hint, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }

    // ───────────── شاشة الإعدادات ─────────────

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SettingsScreen(prefs: Prefs, onBack: () -> Unit) {
        var mode by remember { mutableStateOf(prefs.mode) }
        val ready = ShizukuShell.isReady()
        val granted = ready && ShizukuShell.hasPermission()

        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع", tint = Palette.Porcelain)
                }
                Text("الإعدادات", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }

            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SectionTitle("Shizuku")
                StatusStrip(
                    ready = ready, granted = granted, modeLabel = mode.label, frozenCount = 0,
                    onRequest = { Shizuku.requestPermission(1) },
                    onOpenShizuku = {
                        packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                            ?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                )

                Spacer(Modifier.height(6.dp))
                SectionTitle("طريقة الإخفاء")
                FreezeMode.values().forEach { m ->
                    val selected = mode == m
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (selected) Palette.VelvetHi else Palette.Velvet)
                            .border(
                                1.dp,
                                if (selected) Palette.Brass else Color.Transparent,
                                RoundedCornerShape(16.dp)
                            )
                            .clickable { mode = m; prefs.mode = m }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = { mode = m; prefs.mode = m },
                            colors = RadioButtonDefaults.colors(selectedColor = Palette.Brass)
                        )
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text(m.label, fontWeight = FontWeight.SemiBold)
                            Text(m.desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    "على ريلمي: لو ظهر خطأ Permission Denial، فعّل \"Disable permission monitoring\" من خيارات المطور.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
            }

            // التذييل: اسم المطوّر
            Column(
                Modifier.fillMaxWidth().padding(vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                HorizontalDivider(color = Palette.VelvetHi, modifier = Modifier.padding(horizontal = 20.dp))
                Spacer(Modifier.height(16.dp))
                MaskLogo(40.dp)
                Spacer(Modifier.height(8.dp))
                Text("The Mask · الإصدار 0.1", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(2.dp))
                Text("تم التطوير بواسطة أحمد علاء", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Brass)
            }
        }
    }

    @Composable
    private fun SectionTitle(text: String) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Palette.Mist)
    }

    private fun loadInstalled(frozenPkgs: Set<String>): List<AppEntry> {
        val pm = packageManager
        return pm.getInstalledApplications(0)
            .filter {
                it.flags and ApplicationInfo.FLAG_SYSTEM == 0 &&
                    it.packageName != packageName &&
                    it.packageName !in frozenPkgs
            }
            .map { AppEntry(pm.getApplicationLabel(it).toString(), it.packageName) }
            .sortedBy { it.label.lowercase() }
    }
}
