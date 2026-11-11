package dev.apksim.composeapp

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.window.core.layout.WindowSizeClass
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.foundation.background
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import android.graphics.RuntimeShader
import androidx.compose.ui.unit.dp
import dev.apksim.composeapp.ui.theme.*

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
    super.onCreate(savedInstanceState)
    val db = Db(this)
    Log.i("ComposeApp", "onCreate; rows=" + db.all())
    // A deep link apksim://composeapp/<tab> opens on that tab (apksim's `open` command).
    val startTab = listOf("home", "trips", "settings", "shader").indexOf(intent?.data?.lastPathSegment).coerceAtLeast(0)
    // Follows the system's dark mode; light is Material's default scheme, as before.
    setContent {
      MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) { Root(db, startTab) }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Root(db: Db, startTab: Int = 0) {
  // Saved, so resizing the window (an activity recreated for the new size) keeps the tab and the count.
  var tab by rememberSaveable { mutableIntStateOf(startTab) }
  var count by rememberSaveable { mutableIntStateOf(0) }
  // Adaptive: a bottom bar and a list below 600 dp of width (phones), a navigation rail and a grid from there on
  // (foldables, tablets, desktop, TV), as Material's window size classes suggest.
  val sizeClass = currentWindowAdaptiveInfo().windowSizeClass
  val wide = sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
  val widthClass = when {
    sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) -> "expanded"
    wide -> "medium"
    else -> "compact"
  }
  val window = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.let { "${it.width.toDp().value.toInt()}×${it.height.toDp().value.toInt()}" } }
  val tabs = listOf("Home" to Icons.Default.Home, "Trips" to Icons.Default.Place, "Settings" to Icons.Default.Settings, "Shader" to Icons.Default.Star)
  Scaffold(
    // Test tags as resource ids, so UiAutomator finds the FAB by By.res("fab").
    modifier = Modifier.semantics { testTagsAsResourceId = true },
    topBar = { TopAppBar(title = { Text("ComposeApp") }, actions = { Text("$widthClass $window", Modifier.padding(end = 16.dp)) }) },
    floatingActionButton = { FloatingActionButton(onClick = { count++ }, Modifier.testTag("fab")) { Icon(Icons.Default.Add, "Add") } },
    bottomBar = {
      if (!wide) NavigationBar {
        tabs.forEachIndexed { i, (l, ic) ->
          NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(ic, l) }, label = { Text(l) })
        }
      }
    },
  ) { pad ->
    Row(Modifier.padding(pad)) {
      if (wide) NavigationRail {
        tabs.forEachIndexed { i, (l, ic) ->
          NavigationRailItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(ic, l) }, label = { Text(l) })
        }
      }
      Box(Modifier.weight(1f)) {
        when (tab) {
          0 -> if (wide) {
            LazyVerticalGrid(GridCells.Adaptive(240.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
              item(span = { GridItemSpan(maxLineSpan) }) { Text("FAB clicks: $count", Modifier.padding(8.dp)) }
              gridItems((1..100).toList()) { i ->
                Card(Modifier.fillMaxWidth().padding(4.dp)) { Text("Item $i", Modifier.padding(16.dp)) }
              }
            }
          } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
              item { Text("FAB clicks: $count", Modifier.padding(8.dp)) }
              items((1..100).toList()) { i ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Text("Item $i", Modifier.padding(16.dp)) }
              }
            }
          }
          1 -> Trips(db)
          3 -> ShaderScreen()
          else -> SettingsScreen()
        }
      }
    }
  }
}

@Composable
fun Trips(db: Db) {
  var rows by remember { mutableStateOf(db.all()) }
  // `--feature dev.apksim.testapp.rename_v2`: the rename behaves as a changed build would (scripts/replay-check.py).
  val context = LocalContext.current
  val roman = remember { if (context.packageManager.hasSystemFeature("dev.apksim.testapp.rename_v2")) "Roman holiday" else "Roman vacation" }
  Column(Modifier.fillMaxSize()) {
  LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp)) {
    items(rows, key = { it.first }) { (id, title) ->
      ListItem(
        headlineContent = { Text(title) },
        modifier = Modifier.clickable {
          db.rename(id, if (title.startsWith("Roman")) "Italian vacation" else roman)
          rows = db.all()
        },
      )
    }
  }
  // A text field that rises with the software keyboard (apksim: `keyboard soft on`).
  var note by remember { mutableStateOf("") }
  OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth().padding(12.dp).imePadding(), label = { Text("Trip note") })
  }
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

