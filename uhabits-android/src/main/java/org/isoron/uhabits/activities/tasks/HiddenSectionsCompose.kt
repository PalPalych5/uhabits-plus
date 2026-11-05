package org.isoron.uhabits.activities.tasks

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.isoron.uhabits.compose.UHabitComposeColors
import org.isoron.uhabits.compose.UHabitComposeTheme
import org.isoron.uhabits.compose.LocalUHabitComposeColors
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.ImageView
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.icons.IconPack
import org.isoron.uhabits.core.containers.IconRef

internal data class HiddenSectionRow(val id: String, val name: String, val path: String, val showPath: Boolean, val icon: String?, val color: Int)

@Composable
internal fun HiddenSectionsCompose(
    rows: List<HiddenSectionRow>, colors: UHabitComposeColors,
    title: String, hint: String, back: String, clear: String, empty: String, noResults: String,
    show: String, showPath: String, onBack: () -> Unit, onShow: (String) -> Unit
) {
    UHabitComposeTheme(colors) {
        val themeColors = LocalUHabitComposeColors.current
        val background = themeColors.background
        val foreground = themeColors.foreground
        val secondary = themeColors.secondary
        val accent = themeColors.accent
        var query by remember { mutableStateOf("") }
        val filtered = rows.filter { (it.path + " / " + it.name).contains(query.trim(), ignoreCase = true) }
        Column(Modifier.fillMaxSize().background(Color(background))) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText("‹", Modifier.clickable(onClickLabel = back, onClick = onBack).padding(horizontal = 16.dp, vertical = 12.dp), style = TextStyle(color = Color(foreground), fontSize = 24.sp))
                BasicText(title, style = TextStyle(color = Color(foreground), fontSize = 18.sp))
            }
            val searchShape = RoundedCornerShape(10.dp)
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)
                    .heightIn(min = 48.dp).clip(searchShape)
                    .background(Color(foreground).copy(alpha = .05f))
                    .border(1.dp, Color(foreground).copy(alpha = .10f), searchShape),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(painterResource(R.drawable.ic_tabler_search), contentDescription = null,
                    colorFilter = ColorFilter.tint(Color(secondary)), modifier = Modifier.size(48.dp).padding(15.dp))
                BasicTextField(query, { query = it }, Modifier.weight(1f), singleLine = true,
                    textStyle = TextStyle(color = Color(foreground), fontSize = 14.sp),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) BasicText(hint, style = TextStyle(color = Color(secondary), fontSize = 14.sp))
                            inner()
                        }
                    })
                if (query.isNotEmpty()) Box(
                    Modifier.size(48.dp).semantics { contentDescription = clear }.clickable(onClickLabel = clear) { query = "" },
                    contentAlignment = Alignment.Center
                ) {
                    Image(painterResource(R.drawable.ic_tabler_x), contentDescription = null, colorFilter = ColorFilter.tint(Color(secondary)), modifier = Modifier.size(18.dp))
                }
            }
            if (filtered.isEmpty()) BasicText(if (rows.isEmpty()) empty else noResults, Modifier.padding(16.dp), style = TextStyle(color = Color(secondary)))
            LazyColumn(contentPadding = PaddingValues(bottom = 80.dp)) {
                items(filtered, key = { it.id }) { row ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        val icon = IconRef.fromString(row.icon)
                        if (icon is IconRef.Emoji) {
                            BasicText(icon.value, Modifier.width(34.dp), style = TextStyle(fontSize = 18.sp))
                        } else {
                            AndroidView(factory = { context -> ImageView(context) }, modifier = Modifier.size(24.dp), update = { view ->
                                view.setImageResource(if (icon is IconRef.Vector) IconPack.getDrawableRes(icon.key, R.drawable.ic_tabler_folder) else R.drawable.ic_tabler_folder)
                                view.setColorFilter(row.color)
                                view.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                            })
                            Spacer(Modifier.width(10.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            BasicText(row.name, style = TextStyle(color = Color(foreground), fontSize = 15.sp))
                            if (row.path.isNotEmpty()) BasicText(row.path, style = TextStyle(color = Color(secondary), fontSize = 12.sp))
                        }
                        val label = if (row.showPath) showPath else show
                        BasicText(label, Modifier.clickable(onClickLabel = "$label: ${row.name}") { onShow(row.id) }.padding(12.dp), style = TextStyle(color = Color(accent), fontSize = 14.sp))
                    }
                }
            }
        }
    }
}
