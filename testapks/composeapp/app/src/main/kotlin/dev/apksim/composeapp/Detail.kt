package dev.apksim.composeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private val TRANSPORT = listOf("Train", "Plane", "Car")
private val PACKING = listOf("Passport", "Camera", "Rain jacket")

/**
 * A place's page, opened from its row on Home: the controls of an ordinary booking form (a date picker, a stepper, a
 * slider, a single choice, checkboxes), a confirmation dialog and a snackbar. Back, or the arrow, returns to the list.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DetailScreen(number: Int, saved: Boolean, onSave: () -> Unit, onBack: () -> Unit) {
  val place = remember(number) { Place(number) }
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val snackbars = remember { SnackbarHostState() }
  var travellers by rememberSaveable { mutableIntStateOf(2) }
  var budget by rememberSaveable { mutableFloatStateOf(500f) }
  var transport by rememberSaveable { mutableIntStateOf(0) }
  var packed by rememberSaveable { mutableStateOf(listOf(false, false, false)) }
  var date by rememberSaveable { mutableStateOf<Long?>(null) }
  var picking by rememberSaveable { mutableStateOf(false) }
  var confirming by rememberSaveable { mutableStateOf(false) }
  val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
  Scaffold(
    modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
    topBar = {
      LargeFlexibleTopAppBar(
        title = { Text(place.name) },
        subtitle = { Text(place.tagline) },
        navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        scrollBehavior = scroll,
      )
    },
    snackbarHost = { SnackbarHost(snackbars) },
    // A page below the tabs gets a floating toolbar for its own actions, not the navigation bar.
    floatingActionButton = {
      HorizontalFloatingToolbar(
        expanded = true,
        floatingActionButton = {
          FloatingToolbarDefaults.VibrantFloatingActionButton({ confirming = true }) { Icon(Icons.Filled.Check, "Book") }
        },
        colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
      ) {
        IconButton(onSave) { Icon(if (saved) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, if (saved) "Remove from saved" else "Save") }
        IconButton({ picking = true }) { Icon(Icons.Filled.DateRange, "Pick a date") }
        IconButton({
          val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_TEXT, "${place.name}, ${place.tagline}")
          context.startActivity(android.content.Intent.createChooser(send, "Share place"))
        }) { Icon(Icons.Filled.Share, "Share place") }
      }
    },
    floatingActionButtonPosition = FabPosition.Center,
  ) { pad ->
    Column(Modifier.padding(pad).consumeWindowInsets(pad).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.extraLargeIncreased, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
          PlaceBadge(number, 112.dp)
          Column(Modifier.padding(start = 20.dp)) {
            Text(place.city, style = MaterialTheme.typography.headlineSmallEmphasized)
            Text("${place.days} days from $${"%.0f".format(budget)}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (date == null) "No date yet" else "Leaving " + java.time.Instant.ofEpochMilli(date!!).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                .format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)),
                Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
          }
        }
      }
      Section("Who is going") {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text("Travellers: $travellers", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton({ travellers-- }, enabled = travellers > 1) { Text("Fewer") }
            FilledTonalButton({ travellers++ }, enabled = travellers < 9) { Text("More") }
          }
        }
      }
      Section("Budget: $${"%.0f".format(budget)}") {
        Slider(budget, { budget = (it / 50f).toInt() * 50f }, valueRange = 100f..2000f)
      }
      Section("Getting there") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
          TRANSPORT.forEachIndexed { i, name ->
            ToggleButton(transport == i, { transport = i }, Modifier.weight(1f), shapes = when (i) {
              0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
              TRANSPORT.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
              else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
            }) { Text(name) }
          }
        }
      }
      Section("Packing") {
        PACKING.forEachIndexed { i, name ->
          Row(Modifier.fillMaxWidth().toggleable(packed[i], role = Role.Checkbox) { on -> packed = packed.toMutableList().also { it[i] = on } },
              verticalAlignment = Alignment.CenterVertically) {
            Checkbox(packed[i], null, Modifier.padding(vertical = 10.dp))
            Text(name, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
          }
        }
      }
      val height = ButtonDefaults.MediumContainerHeight
      Button({ confirming = true }, modifier = Modifier.fillMaxWidth().heightIn(height), shapes = ButtonDefaults.shapesFor(height),
          contentPadding = ButtonDefaults.contentPaddingFor(height)) {
        Text("Book ${place.name}", style = ButtonDefaults.textStyleFor(height))
      }
    }
  }
  if (picking) {
    val state = rememberDatePickerState(initialSelectedDateMillis = date)
    DatePickerDialog(onDismissRequest = { picking = false },
        confirmButton = { TextButton({ date = state.selectedDateMillis; picking = false }) { Text("Set date") } },
        dismissButton = { TextButton({ picking = false }) { Text("Cancel") } }) { DatePicker(state) }
  }
  if (confirming) AlertDialog(
    onDismissRequest = { confirming = false },
    title = { Text("Book ${place.name}?") },
    text = { Text("$travellers by ${TRANSPORT[transport].lowercase()} to ${place.city}, ${packed.count { it }} of ${PACKING.size} things packed.") },
    confirmButton = { TextButton({ confirming = false; scope.launch { snackbars.showSnackbar("Booked ${place.name} for $travellers") } }) { Text("Book") } },
    dismissButton = { TextButton({ confirming = false }) { Text("Not yet") } },
  )
}

/** A titled group on a tonal container: related controls read as one thing. */
@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
  Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
      Text(title, Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.titleMediumEmphasized)
      content()
    }
  }
}
