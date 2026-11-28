package dev.apksim.composeapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** The Trips tab: the trips kept in SQLite (a tap renames the first between two names) and a note field. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Trips(db: Db) {
  var rows by remember { mutableStateOf(db.all()) }
  // `--feature dev.apksim.testapp.rename_v2`: the rename behaves as a changed build would (scripts/replay-check.py).
  val context = LocalContext.current
  val roman = remember(context) { if (context.packageManager.hasSystemFeature("dev.apksim.testapp.rename_v2")) "Roman holiday" else "Roman vacation" }
  Column(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      itemsIndexed(rows, key = { _, r -> r.first }) { index, (id, title) ->
        val scheme = MaterialTheme.colorScheme
        val (container, content) = when (index % 3) {
          0 -> scheme.primaryContainer to scheme.onPrimaryContainer
          1 -> scheme.tertiaryContainer to scheme.onTertiaryContainer
          else -> scheme.secondaryContainer to scheme.onSecondaryContainer
        }
        Card(
          onClick = {
            db.rename(id, if (title.startsWith("Roman")) "Italian vacation" else roman)
            rows = db.all()
          },
          shape = MaterialTheme.shapes.extraLarge,
          colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        ) {
          Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).background(content, tripShape(index)), contentAlignment = Alignment.Center) {
              Icon(Icons.Filled.Place, null, tint = container)
            }
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
              Text(title, style = MaterialTheme.typography.titleLargeEmphasized)
              Text(TRIP_DATES[index % TRIP_DATES.size], style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.Filled.Edit, "Rename")
          }
        }
      }
      item {
        // How far the packing for the first trip is: a wave that stands still (no frame is asked for at rest).
        Column(Modifier.padding(horizontal = 8.dp, vertical = 12.dp)) {
          Text("Packing for ${rows.firstOrNull()?.second ?: "the next trip"}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
          LinearWavyProgressIndicator(progress = { 0.6f }, Modifier.fillMaxWidth().padding(top = 10.dp), waveSpeed = 0.dp)
        }
      }
    }
    // A text field that rises with the software keyboard (apksim: `keyboard soft on`). It ends where the FAB begins.
    var note by remember { mutableStateOf("") }
    OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth().padding(start = 16.dp, end = 88.dp, bottom = 12.dp).imePadding(),
        label = { Text("Trip note") }, shape = MaterialTheme.shapes.large)
  }
}

private val TRIP_DATES = listOf("June 3 to 17 · 14 days", "September 12 to 14 · a weekend", "January 20 to 27 · 7 days")

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun tripShape(index: Int) = when (index % 3) {
  0 -> MaterialShapes.Cookie6Sided
  1 -> MaterialShapes.Sunny
  else -> MaterialShapes.Gem
}.toShape()
