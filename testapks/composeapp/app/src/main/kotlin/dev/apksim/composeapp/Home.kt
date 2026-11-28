package dev.apksim.composeapp

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** What the Home tab remembers: the places saved, the search text and whether only saved ones show. */
@Stable
class HomeState(saved: Set<Int>, query: String, onlySaved: Boolean) {
  var saved by mutableStateOf(saved)
  var query by mutableStateOf(query)
  var onlySaved by mutableStateOf(onlySaved)
  fun toggle(i: Int) { saved = if (i in saved) saved - i else saved + i }
}

@Composable
fun rememberHomeState(): HomeState = rememberSaveable(saver = listSaver(
    save = { listOf(it.saved.toList(), it.query, it.onlySaved) },
    restore = { @Suppress("UNCHECKED_CAST") HomeState((it[0] as List<Int>).toSet(), it[1] as String, it[2] as Boolean) })) {
  HomeState(emptySet(), "", false)
}

/** A place of the list, made up from its number: the same every launch, so screenshots and tests can name them. */
class Place(val number: Int) {
  val name get() = "Item $number"
  val city get() = CITIES[(number - 1) % CITIES.size]
  val days get() = 2 + (number * 7) % 12
  val tagline get() = "$city · $days days"
}

private val CITIES = listOf("Lisbon", "Kyoto", "Reykjavik", "Marrakesh", "Oaxaca", "Hanoi", "Tbilisi", "Valparaiso", "Bergen", "Cape Town")

/** One of Material's shapes and a container colour pair for a place, by its number. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun placeShape(number: Int): Shape = when (number % 6) {
  0 -> MaterialShapes.Cookie9Sided
  1 -> MaterialShapes.Flower
  2 -> MaterialShapes.Sunny
  3 -> MaterialShapes.Clover4Leaf
  4 -> MaterialShapes.Pentagon
  else -> MaterialShapes.Cookie4Sided
}.toShape()

@Composable
fun placeColors(number: Int): Pair<Color, Color> = MaterialTheme.colorScheme.run {
  when (number % 3) {
    0 -> tertiaryContainer to onTertiaryContainer
    1 -> primaryContainer to onPrimaryContainer
    else -> secondary to onSecondary
  }
}

/** A place's number in its shape: the avatar of a list row, and larger the page's own. */
@Composable
fun PlaceBadge(number: Int, size: Dp, modifier: Modifier = Modifier) {
  val (container, content) = placeColors(number)
  Box(modifier.size(size).background(container, placeShape(number)), contentAlignment = Alignment.Center) {
    Text(number.toString(), color = content,
        style = if (size >= 96.dp) MaterialTheme.typography.displaySmallEmphasized else MaterialTheme.typography.titleSmallEmphasized)
  }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeScreen(state: HomeState, count: Int, wide: Boolean, onOpen: (Int) -> Unit) {
  val places = remember(state.query, state.onlySaved, state.saved) {
    (1..100).filter { (!state.onlySaved || it in state.saved) && (state.query.isBlank() || Place(it).run { name.contains(state.query.trim(), true) || city.contains(state.query.trim(), true) }) }
  }
  if (wide) {
    LazyVerticalGrid(GridCells.Adaptive(240.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 112.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      item(span = { GridItemSpan(maxLineSpan) }) { HomeHeader(state, count, places.size) }
      gridItems(places, key = { it }) { i ->
        val place = Place(i)
        Card(onClick = { state.toggle(i) }, shape = MaterialTheme.shapes.largeIncreased,
            colors = CardDefaults.cardColors(containerColor =
                if (i in state.saved) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainer)) {
          Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PlaceBadge(i, 48.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
              Text(place.name, style = MaterialTheme.typography.titleMedium)
              Text(place.tagline, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PlaceActions(place, i in state.saved, onOpen)
          }
        }
      }
    }
  } else {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
      item { HomeHeader(state, count, places.size) }
      itemsIndexed(places, key = { _, i -> i }) { index, i ->
        val place = Place(i)
        SegmentedListItem(
          onClick = { state.toggle(i) },
          shapes = ListItemDefaults.segmentedShapes(index, places.size),
          // A saved place stands out by its container as well as its heart.
          colors = ListItemDefaults.segmentedColors(containerColor =
              if (i in state.saved) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainer),
          leadingContent = { PlaceBadge(i, 40.dp) },
          supportingContent = { Text(place.tagline) },
          trailingContent = { PlaceActions(place, i in state.saved, onOpen) },
        ) { Text(place.name) }
      }
    }
  }
}

/** The saved mark, and the way to a place's page. */
@Composable
private fun PlaceActions(place: Place, saved: Boolean, onOpen: (Int) -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    if (saved) Icon(Icons.Filled.Favorite, "Saved", tint = MaterialTheme.colorScheme.primary)
    IconButton({ onOpen(place.number) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Open ${place.name}") }
  }
}

/** Search, the counter (the screen's one loud moment) and the filter. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HomeHeader(state: HomeState, count: Int, shown: Int) {
  Column(Modifier.padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    TextField(state.query, { state.query = it }, Modifier.fillMaxWidth(), singleLine = true, shape = CircleShape,
        placeholder = { Text("Search places") }, leadingIcon = { Icon(Icons.Filled.Search, null) },
        trailingIcon = if (state.query.isEmpty()) null else ({ IconButton({ state.query = "" }) { Icon(Icons.Filled.Clear, "Clear search") } }),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh))
    Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.extraLargeIncreased, modifier = Modifier.fillMaxWidth()) {
      Box(Modifier.height(IntrinsicSize.Min)) {
        // Two of Material's shapes, turned a notch by every count: the springs are the theme's expressive ones.
        val turn by animateFloatAsState(count * 40f, MaterialTheme.motionScheme.slowSpatialSpec(), label = "turn")
        Box(Modifier.align(Alignment.CenterEnd).offset(x = 44.dp).size(176.dp).graphicsLayer { rotationZ = turn }
            .background(MaterialTheme.colorScheme.onPrimaryContainer, MaterialShapes.Cookie9Sided.toShape()))
        Box(Modifier.align(Alignment.CenterEnd).offset(x = (-78).dp, y = 34.dp).size(64.dp).graphicsLayer { rotationZ = -turn * 1.5f }
            .background(MaterialTheme.colorScheme.tertiaryContainer, MaterialShapes.Flower.toShape()))
        Column(Modifier.padding(start = 24.dp, top = 24.dp, bottom = 24.dp, end = 156.dp)) {
          Text("Where to next?", style = MaterialTheme.typography.labelLarge)
          Text("FAB clicks: $count", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.headlineMediumEmphasized)
          Text("Tap + to count. Tap a place to save it.", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium)
        }
      }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
      Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        ToggleButton(!state.onlySaved, { state.onlySaved = false }, shapes = ButtonGroupDefaults.connectedLeadingButtonShapes()) { Text("All") }
        ToggleButton(state.onlySaved, { state.onlySaved = true }, shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
            icon = { Icon(if (state.onlySaved) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, null, Modifier.size(ToggleButtonDefaults.IconSize)) }) {
          Text("Saved ${state.saved.size}")
        }
      }
      Spacer(Modifier.weight(1f))
      Text(if (shown == 1) "1 place" else "$shown places", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}
