package dev.vitals.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vitals.app.AppViewModel
import dev.vitals.app.core.Proc
import dev.vitals.app.ui.Bar
import dev.vitals.app.ui.DividerLine
import dev.vitals.app.ui.Palette
import dev.vitals.app.ui.SearchField
import dev.vitals.app.ui.Segmented
import dev.vitals.app.ui.TextAction
import dev.vitals.app.ui.Type

@Composable
fun ProcessesScreen(vm: AppViewModel) {
    val filtered = remember(vm.procs, vm.procFilter) {
        val needle = vm.procFilter.trim().lowercase()
        if (needle.isEmpty()) vm.procs else vm.procs.filter { it.name.lowercase().contains(needle) }
    }

    val maxValue = remember(filtered, vm.procSort) {
        val raw = when (vm.procSort) {
            "mem" -> filtered.maxOfOrNull { it.mem.toFloat() } ?: 1f
            else -> filtered.maxOfOrNull { it.cpu } ?: 1f
        }
        raw.coerceAtLeast(1f)
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SearchField(vm.procFilter, vm::filterProcs, placeholder = "Filtrar procesos")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Segmented(
                    options = listOf("cpu" to "CPU", "mem" to "Memoria"),
                    selected = vm.procSort,
                    onSelect = vm::sortProcs,
                )
                Spacer(Modifier.weight(1f))
                TextAction("Actualizar") { vm.reloadProcs() }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (filtered.isEmpty()) "Sin resultados" else "${filtered.size} procesos",
                    style = Type.micro,
                )
                Text("se actualiza cada 4 s", style = Type.micro)
            }
        }
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Brush.verticalGradient(listOf(Palette.Surface, Palette.SurfaceLow)))
                .border(1.dp, Palette.Line, RoundedCornerShape(18.dp)),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            itemsIndexed(filtered, key = { _, proc -> proc.pid }) { index, proc ->
                ProcRow(proc, vm.procSort, maxValue, last = index == filtered.lastIndex)
            }
            if (filtered.isEmpty()) {
                item {
                    Text(
                        if (vm.procFilter.isBlank()) {
                            "El host todavía no ha enviado procesos."
                        } else {
                            "Ningún proceso coincide con «${vm.procFilter}»."
                        },
                        color = Palette.Muted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProcRow(proc: Proc, sort: String, max: Float, last: Boolean) {
    val value = if (sort == "cpu") proc.cpu else proc.mem.toFloat()
    val fraction = if (sort == "cpu") {
        (proc.cpu / 100f).coerceIn(0f, 1f)
    } else {
        (value / max).coerceIn(0f, 1f)
    }
    val hot = if (sort == "cpu") proc.cpu > 70f else fraction > 0.7f
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f, fill = false)) {
                    Text(
                        proc.name,
                        color = Palette.Text,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("PID ${proc.pid}", style = Type.micro)
                }
                Text(
                    if (sort == "cpu") "${dev.vitals.app.ui.Fmt.one(proc.cpu)} %" else dev.vitals.app.ui.Fmt.bytes(proc.mem),
                    color = Palette.Text,
                    fontSize = 12.sp,
                )
            }
            Bar(fraction, if (hot) Palette.Tx else Palette.Accent, height = 3.dp)
        }
        if (!last) DividerLine()
    }
}
