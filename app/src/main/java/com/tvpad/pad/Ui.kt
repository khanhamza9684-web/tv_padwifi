package com.tvpad.pad

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** A button that is "down" for exactly as long as a finger is on it. Several can be held at once. */
@Composable
fun PadKey(label: String, modifier: Modifier, shape: Shape = CircleShape, onChange: (Boolean) -> Unit) {
    var down by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    Box(
        modifier
            .clip(shape)
            .background(if (down) cs.primary else cs.surfaceVariant)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    down = true
                    onChange(true)
                    waitForUpOrCancellation()
                    down = false
                    onChange(false)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 14.sp, color = if (down) cs.onPrimary else cs.onSurfaceVariant)
    }
}

/** On screen analog stick. Reports -127..127 on both axes, 0 when released. */
@Composable
fun Stick(modifier: Modifier, onMove: (Int, Int) -> Unit) {
    var off by remember { mutableStateOf(Offset.Zero) }
    val cs = MaterialTheme.colorScheme
    val base = cs.surfaceVariant
    val knob = cs.primary
    Box(
        modifier
            .drawBehind {
                drawCircle(base, radius = size.minDimension / 2f)
                drawCircle(knob, radius = size.minDimension / 4.5f, center = center + off)
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val first = awaitFirstDown()
                    val r = size.width / 2f
                    val c = Offset(size.width / 2f, size.height / 2f)
                    fun move(p: Offset) {
                        var d = p - c
                        val len = d.getDistance()
                        if (len > r) d = d * (r / len)
                        off = d
                        onMove((d.x / r * 127f).roundToInt(), (d.y / r * 127f).roundToInt())
                    }
                    move(first.position)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == first.id } ?: break
                        if (!ch.pressed) break
                        move(ch.position)
                        ch.consume()
                    }
                    off = Offset.Zero
                    onMove(0, 0)
                }
            }
    )
}

@Composable
fun PadScreen(pad: PadController, title: String, onExit: () -> Unit) {
    fun btn(bit: Int): (Boolean) -> Unit = { pad.setButton(bit, it) }
    fun dir(bit: Int): (Boolean) -> Unit = { pad.setDir(bit, it) }
    val shoulder = Modifier.size(width = 64.dp, height = 36.dp)
    val cell = Modifier.size(42.dp)
    val face = Modifier.size(46.dp)
    val pill = RoundedCornerShape(10.dp)

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            PadKey("L2", shoulder, pill, btn(Btn.L2))
            PadKey("L1", shoulder, pill, btn(Btn.L1))
            Text(title, Modifier.weight(1f), textAlign = TextAlign.Center)
            PadKey("R1", shoulder, pill, btn(Btn.R1))
            PadKey("R2", shoulder, pill, btn(Btn.R2))
        }
        Row(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left side: stick on top, D-pad below
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Stick(Modifier.size(104.dp)) { x, y -> pad.setLeft(x, y) }
                Row { Spacer(cell); PadKey("▲", cell, pill, dir(1)); Spacer(cell) }
                Row { PadKey("◀", cell, pill, dir(8)); Spacer(cell); PadKey("▶", cell, pill, dir(2)) }
                Row { Spacer(cell); PadKey("▼", cell, pill, dir(4)); Spacer(cell) }
            }
            // Centre
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PadKey("Select", Modifier.size(width = 64.dp, height = 34.dp), pill, btn(Btn.SELECT))
                    PadKey("Start", Modifier.size(width = 64.dp, height = 34.dp), pill, btn(Btn.START))
                }
                PadKey("Home", Modifier.size(width = 64.dp, height = 34.dp), pill, btn(Btn.MODE))
                OutlinedButton(onClick = onExit) { Text("Exit") }
            }
            // Right side: face buttons on top, stick below
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row { Spacer(face); PadKey("Y", face, CircleShape, btn(Btn.Y)); Spacer(face) }
                Row { PadKey("X", face, CircleShape, btn(Btn.X)); Spacer(face); PadKey("B", face, CircleShape, btn(Btn.B)) }
                Row { Spacer(face); PadKey("A", face, CircleShape, btn(Btn.A)); Spacer(face) }
                Stick(Modifier.size(104.dp)) { x, y -> pad.setRight(x, y) }
            }
        }
    }
}
