package com.tabexplorer.android

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString

/** waitUntil that says what the screen looked like when it gave up, so a failure in CI can be understood from the log. */
fun ComposeTestRule.until(ms: Long, cond: () -> Boolean) {
    try { waitUntil(ms, cond) } catch (e: ComposeTimeoutException) {
        val ops = Ops.state.value
        throw AssertionError("Timed out after $ms ms. Ops: ${ops?.title} finished=${ops?.finished} message=${ops?.message}. Clipboard: ${Clipboard.files.size}.\nScreen:\n" + onRoot().printToString(14))
    }
}
