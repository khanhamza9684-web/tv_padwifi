package com.tvpad.pad

/** Bit numbers inside the 16 button field. Android maps these in order to A, B, C, X, Y, Z, L1, R1, L2, R2, Select, Start, Mode, L3, R3. */
object Btn {
    const val A = 0
    const val B = 1
    const val X = 3
    const val Y = 4
    const val L1 = 6
    const val R1 = 7
    const val L2 = 8
    const val R2 = 9
    const val SELECT = 10
    const val START = 11
    const val MODE = 12
    const val L3 = 13
    const val R3 = 14
}

class PadState {
    var buttons = 0
    var hat = 8 // 0=N 1=NE 2=E 3=SE 4=S 5=SW 6=W 7=NW 8=centered
    var lx = 0
    var ly = 0
    var rx = 0
    var ry = 0

    /** 7 byte HID input report matching [HidDescriptor.GAMEPAD]. */
    fun report(): ByteArray = byteArrayOf(
        buttons.toByte(), (buttons shr 8).toByte(), hat.toByte(),
        lx.toByte(), ly.toByte(), rx.toByte(), ry.toByte()
    )
}

interface PadOutput {
    /** Called after every change. [prevButtons] and [prevHat] are the values before this change. */
    fun update(s: PadState, prevButtons: Int, prevHat: Int)
    fun close()
}

class PadController(private val out: PadOutput) {
    val state = PadState()
    private var prevButtons = 0
    private var prevHat = 8
    private var dirs = 0

    private fun push() {
        out.update(state, prevButtons, prevHat)
        prevButtons = state.buttons
        prevHat = state.hat
    }

    fun setButton(bit: Int, down: Boolean) {
        val mask = 1 shl bit
        state.buttons = if (down) state.buttons or mask else state.buttons and mask.inv()
        push()
    }

    /** dir bits: 1 = up, 2 = right, 4 = down, 8 = left. */
    fun setDir(bit: Int, down: Boolean) {
        dirs = if (down) dirs or bit else dirs and bit.inv()
        val u = (dirs and 1) != 0 && (dirs and 4) == 0
        val d = (dirs and 4) != 0 && (dirs and 1) == 0
        val r = (dirs and 2) != 0 && (dirs and 8) == 0
        val l = (dirs and 8) != 0 && (dirs and 2) == 0
        state.hat = when {
            u && r -> 1
            r && d -> 3
            d && l -> 5
            l && u -> 7
            u -> 0
            r -> 2
            d -> 4
            l -> 6
            else -> 8
        }
        push()
    }

    fun setLeft(x: Int, y: Int) {
        state.lx = x
        state.ly = y
        push()
    }

    fun setRight(x: Int, y: Int) {
        state.rx = x
        state.ry = y
        push()
    }

    fun close() = out.close()
}
