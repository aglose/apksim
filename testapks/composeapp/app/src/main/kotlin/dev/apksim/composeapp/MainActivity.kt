package dev.apksim.composeapp

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.RuntimeShader
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import dev.apksim.composeapp.ui.theme.ComposeAppTheme

class Db(c: Context) : SQLiteOpenHelper(c, "trips.db", null, 1) {
  override fun onCreate(d: SQLiteDatabase) {
    d.execSQL("create table trips(id integer primary key, title text)")
    listOf("Italian vacation", "Tokyo weekend", "Alps ski trip").forEach { t ->
      d.insert("trips", null, ContentValues().apply { put("title", t) })
    }
  }
  override fun onUpgrade(d: SQLiteDatabase, a: Int, b: Int) {}
  fun all(): List<Pair<Long, String>> = readableDatabase.rawQuery("select id,title from trips order by id", null).use { c ->
    buildList { while (c.moveToNext()) add(c.getLong(0) to c.getString(1)) }
  }
  fun rename(id: Long, t: String) { writableDatabase.update("trips", ContentValues().apply { put("title", t) }, "id=?", arrayOf(id.toString())) }
}

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    // Edge to edge, as every app targeting Android 15 and up is drawn: the bars' insets reach the Scaffold.
    enableEdgeToEdge()
    // No scrim behind a three-button navigation bar: the navigation bar's own container reaches the screen's edge.
    if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
    super.onCreate(savedInstanceState)
    val db = Db(this)
    Log.i("ComposeApp", "onCreate; rows=" + db.all())
    // A deep link apksim://composeapp/<tab> opens on that tab (apksim's `open` command).
    val startTab = listOf("home", "trips", "settings", "shader").indexOf(intent?.data?.lastPathSegment).coerceAtLeast(0)
    // Follows the system's dark mode, in the app's own Material 3 Expressive theme (ui/theme).
    setContent { ComposeAppTheme { Root(db, startTab) } }
    // overlay=full is a full-screen one that is FLAG_NOT_TOUCHABLE (issue 323): taps must go to the activity below.
    if (intent?.data?.getQueryParameter("overlay") == "full") {
      window.decorView.post {
        val params = android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            android.graphics.PixelFormat.TRANSLUCENT)
        getSystemService(android.view.WindowManager::class.java).addView(android.widget.FrameLayout(this), params)
        Log.i("ComposeApp", "full-screen untouchable overlay added")
      }
    }
    // apksim://composeapp/trips?overlay=1 adds an empty overlay window after this activity's window, as some apps do
    // (issue 123): hidden, 0x0, not focusable. Typing must still reach the focused field below it.
    if (intent?.data?.getQueryParameter("overlay") == "1") {
      window.decorView.post {
        val params = android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.WRAP_CONTENT, android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            android.graphics.PixelFormat.TRANSLUCENT)
        getSystemService(android.view.WindowManager::class.java).addView(android.widget.FrameLayout(this), params)
        Log.i("ComposeApp", "after-activity overlay added")
      }
    }
  }
}

private class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val selectedIcon: androidx.compose.ui.graphics.vector.ImageVector)

private val TABS = listOf(
  Tab("Home", Icons.Outlined.Home, Icons.Filled.Home),
  Tab("Trips", Icons.Outlined.Place, Icons.Filled.Place),
  Tab("Settings", Icons.Outlined.Settings, Icons.Filled.Settings),
  Tab("Shader", Icons.Outlined.Star, Icons.Filled.Star),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Root(db: Db, startTab: Int = 0) {
  // Saved, so resizing the window (an activity recreated for the new size) keeps the tab, the count and the rest.
  var tab by rememberSaveable { mutableIntStateOf(startTab) }
  var count by rememberSaveable { mutableIntStateOf(0) }
  val home = rememberHomeState()
  // Held here, not in HomeScreen: a place's page replaces the tabs, and the list is where it was on the way back.
  val homeList = androidx.compose.foundation.lazy.rememberLazyListState()
  val homeGrid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
  // The place whose page is open over the tabs (Detail.kt), 0 for none. Back closes it.
  var detail by rememberSaveable { mutableIntStateOf(0) }
  if (detail != 0) {
    BackHandler { detail = 0 }
    DetailScreen(detail, saved = detail in home.saved, onSave = { home.toggle(detail) }, onBack = { detail = 0 })
    return
  }
  // Adaptive: a navigation bar and a list below 600 dp of width (phones), a navigation rail and a grid from there on
  // (foldables, tablets, desktop, TV), as Material's window size classes suggest.
  val sizeClass = currentWindowAdaptiveInfo().windowSizeClass
  val wide = sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
  val widthClass = when {
    sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) -> "expanded"
    wide -> "medium"
    else -> "compact"
  }
  val window = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.let { "${it.width.toDp().value.toInt()}×${it.height.toDp().value.toInt()}" } }
  var menu by remember { mutableStateOf(false) }
  var about by rememberSaveable { mutableStateOf(false) }
  Scaffold(
    // Test tags as resource ids, so UiAutomator finds the FAB by By.res("fab").
    modifier = Modifier.semantics { testTagsAsResourceId = true },
    topBar = {
      TopAppBar(
        title = { Text("ComposeApp", style = MaterialTheme.typography.titleLargeEmphasized) },
        actions = {
          // The window size class, as a badge: what `device` and `size` change.
          Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
              shape = MaterialTheme.shapes.small) {
            Text("$widthClass $window", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
          }
          Box {
            IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "More options") }
            DropdownMenu(menu, { menu = false }) {
              DropdownMenuItem({ Text("Clear saved places") }, { menu = false; home.saved = emptySet() },
                  leadingIcon = { Icon(Icons.Filled.Clear, null) }, enabled = home.saved.isNotEmpty())
              DropdownMenuItem({ Text("Reset counter") }, { menu = false; count = 0 }, leadingIcon = { Icon(Icons.Filled.Refresh, null) })
              DropdownMenuItem({ Text("About") }, { menu = false; about = true }, leadingIcon = { Icon(Icons.Filled.Info, null) })
            }
          }
        },
      )
    },
    floatingActionButton = {
      // The one action that matters on Home is big there (a medium FAB, 80 dp) and an ordinary 56 dp elsewhere.
      val motion = MaterialTheme.motionScheme
      val size by animateDpAsState(if (tab == 0) 80.dp else 56.dp, motion.fastSpatialSpec(), label = "fab size")
      val corner by animateDpAsState(if (tab == 0) 20.dp else 16.dp, motion.fastSpatialSpec(), label = "fab corner")
      FloatingActionButton(onClick = { count++ }, Modifier.size(size).testTag("fab"), shape = RoundedCornerShape(corner),
          containerColor = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer) {
        Icon(Icons.Filled.Add, "Add", Modifier.size(if (tab == 0) 28.dp else 24.dp))
      }
    },
    bottomBar = {
      if (!wide) ShortNavigationBar {
        TABS.forEachIndexed { i, t ->
          ShortNavigationBarItem(selected = tab == i, onClick = { tab = i },
              icon = { Icon(if (tab == i) t.selectedIcon else t.icon, null) }, label = { Text(t.label) })
        }
      }
    },
  ) { pad ->
    Row(Modifier.padding(pad).consumeWindowInsets(pad)) {
      if (wide) WideNavigationRail {
        TABS.forEachIndexed { i, t ->
          WideNavigationRailItem(selected = tab == i, onClick = { tab = i }, railExpanded = false,
              icon = { Icon(if (tab == i) t.selectedIcon else t.icon, null) }, label = { Text(t.label) })
        }
      }
      Box(Modifier.weight(1f)) {
        when (tab) {
          0 -> HomeScreen(home, count, wide, homeList, homeGrid, onOpen = { detail = it })
          1 -> Trips(db)
          3 -> ShaderScreen()
          else -> SettingsScreen()
        }
      }
    }
  }
  if (about) AlertDialog(
    onDismissRequest = { about = false },
    icon = { Icon(Icons.Filled.Info, null) },
    title = { Text("About ComposeApp") },
    text = { Text("The app apksim is tested and demonstrated with: a list, trips kept in SQLite, device features and a shader, in Material 3 Expressive.") },
    confirmButton = { TextButton({ about = false }) { Text("Close") } },
  )
}

// A stress test: a full-size, unthrottled noise shader that changes every frame the screen offers.
private const val STRESS = """
uniform float time;
float hash(float2 p) { return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453); }
float noise(float2 p) {
  float2 i = floor(p);
  float2 f = fract(p);
  float2 u = f * f * (3.0 - 2.0 * f);
  return mix(mix(hash(i), hash(i + float2(1, 0)), u.x), mix(hash(i + float2(0, 1)), hash(i + float2(1, 1)), u.x), u.y);
}
float fbm(float2 p) {
  float v = 0.0;
  float a = 0.5;
  for (int i = 0; i < 6; i++) { v += a * noise(p); p = p * 2.03 + float2(1.7, 9.2); a *= 0.5; }
  return v;
}
half4 main(float2 c) {
  float n = fbm(c / 120.0 + float2(time * 0.4, time * 0.25));
  float m = fbm(c / 60.0 - float2(time * 0.3, 0.0));
  return half4(half3(n, n * m, m), 1.0);
}
"""

@Composable
fun ShaderScreen() {
  val shader = remember { RuntimeShader(STRESS) }
  var time by remember { mutableFloatStateOf(0f) }
  LaunchedEffect(Unit) {
    val start = withInfiniteAnimationFrameNanos { it }
    while (true) withInfiniteAnimationFrameNanos { time = (it - start) / 1e9f }
  }
  val brush = remember(time) {
    shader.setFloatUniform("time", time)
    ShaderBrush(shader)
  }
  Box(Modifier.fillMaxSize().background(brush))
}

