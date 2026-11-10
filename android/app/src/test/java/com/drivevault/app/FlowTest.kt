package com.drivevault.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.drivevault.app.ui.importSrcs
import com.drivevault.app.ui.Nav
import com.drivevault.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class FlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    // The test clock is driven by hand: a focused text field's blinking cursor would otherwise keep Compose "busy" forever.
    private fun waitUntil(timeout: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while (true) {
            rule.mainClock.advanceTimeBy(50); rule.waitForIdle()
            if (cond()) return
            if (System.currentTimeMillis() > end) {
                rule.onAllNodes(isRoot()).fetchSemanticsNodes().forEachIndexed { i, _ -> rule.onAllNodes(isRoot())[i].printToLog("TREE$i") }
                throw AssertionError("timed out")
            }
            Thread.sleep(10)
        }
    }
    private fun waitFor(text: String, timeout: Long = 20_000, substring: Boolean = true) =
        waitUntil(timeout) { rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
    // no waitForIdle(): a focused text field's blinking cursor never lets Robolectric go idle
    private fun clickDesc(d: String) { waitUntil { rule.onAllNodesWithContentDescription(d).fetchSemanticsNodes().isNotEmpty() }; rule.onAllNodesWithContentDescription(d).onFirst().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick); rule.mainClock.advanceTimeBy(400) }
    private fun clickLast(text: String) { waitFor(text); rule.onAllNodesWithText(text, substring = false).onLast().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick); rule.mainClock.advanceTimeBy(400) }
    private fun click(text: String) { waitFor(text); rule.onAllNodesWithText(text, substring = false).onFirst().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick); rule.mainClock.advanceTimeBy(400) }
    private fun shot(name: String) { println("---- screen: $name ----"); rule.onRoot().printToLog("DV"); }

    @Test fun unreadableDriveNeverOffersCreate() {
        val gone = File("/tmp/robotest-not-mounted").apply { deleteRecursively() }   // like a USB drive that isn't ready yet
        rule.mainClock.autoAdvance = false
        assertEquals(DriveState.NotReady, Storage.fileDrive(gone).state())
        rule.runOnUiThread { Session.select(gone) }
        waitFor("Drive not ready"); waitFor("Retry")
        assertTrue(rule.onAllNodesWithText("Create vault").fetchSemanticsNodes().isEmpty())
        gone.mkdirs()                                                                  // drive becomes readable
        click("Retry"); waitFor("Protect this drive"); waitFor("Create vault")
        gone.deleteRecursively()
    }

    @Test fun fullFlow() {
        ITERATIONS = 2000; KEY_ITERATIONS = 2000; QUESTION_ITERATIONS = 2000; ARGON2_MEMORY_KIB = 2048; ARGON2_ITERATIONS = 1
        val base = File("/tmp/robotest").apply { deleteRecursively(); mkdirs() }
        rule.mainClock.autoAdvance = false
        Session.autoFocus = false
        rule.runOnUiThread { Session.select(base) }
        waitFor("Protect this drive")
        // ---- create vault
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("phone pass 1")
        rule.onAllNodes(hasSetTextAction())[1].performTextInput("phone pass 1")
        click("Create vault")
        waitFor("Your recovery key"); click("I've saved it")
        waitFor("This folder is empty")
        println("created + unlocked; desktop app installed: " + File(base, "DriveVault/app/drivevault.py").exists())
        try { println("assets: " + rule.activity.assets.list("desktop")?.toList()) } catch (e: Exception) { println("assets error $e") }
        // ---- add files (same path the file picker uses)
        val srcs = listOf("notes.txt" to "hello from robolectric\nline 2", "b.csv" to "a,b\n1,\"x, y\"", "c.txt" to "third").map { (n, t) ->
            Storage.Src(n, t.length.toLong(), null, { ByteArrayInputStream(t.toByteArray()) }, emptyList()) } +
            Storage.Src("deep.txt", 4, null, { ByteArrayInputStream("deep".toByteArray()) }, listOf("Album", "Sub"))
        rule.runOnUiThread { importSrcs(srcs, Nav.cur) }
        waitFor("notes.txt"); waitFor("Album")
        rule.mainClock.advanceTimeBy(12000)
        // ---- new folder through the + menu
        // (typing inside dialogs can't be driven under Robolectric; dialog logic is covered by DialogTest)
        rule.onNodeWithContentDescription("Add").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick); waitFor("Add a folder"); click("Cancel")
        rule.runOnUiThread { Session.vault!!.mkdir(Nav.cur, "Trips"); Session.changed() }; waitFor("Trips")
        // ---- open folder, back
        click("Album"); waitFor("Sub"); clickDesc("Back"); waitFor("notes.txt")
        // ---- viewer on text file
        click("notes.txt"); waitFor("hello from robolectric"); waitFor("3 / 3")
        clickDesc("Close"); rule.mainClock.advanceTimeBy(400)
        // ---- select + trash + restore
        rule.onNodeWithText("c.txt").performTouchInput { longClick() }; waitFor("1 selected")
        click("Trash"); waitUntil(10_000) { rule.onAllNodesWithText("c.txt").fetchSemanticsNodes().isEmpty() }
        clickDesc("Menu"); click("Trash (1)"); waitFor("c.txt")
        rule.onNodeWithText("c.txt").performTouchInput { longClick() }; click("Restore")
        clickDesc("Home"); waitFor("c.txt")
        // ---- custom order shows drag handles
        clickDesc("Sort"); click("Custom order")
        waitFor("Custom order: drag"); rule.onAllNodesWithContentDescription("Drag to reorder").assertCountEquals(5)
        // ---- move via dialog
        rule.onNodeWithText("c.txt").performTouchInput { longClick() }; click("Move")
        waitFor("Move 1 item(s) to…"); clickLast("Trips"); rule.mainClock.advanceTimeBy(500); click("Move here")
        waitUntil(10_000) { rule.onAllNodesWithText("c.txt").fetchSemanticsNodes().isEmpty() }
        // ---- history
        clickDesc("Menu"); click("History")
        waitFor("Verified:"); waitFor("Moved to trash"); shot("history")
        clickDesc("Back"); waitFor("notes.txt")
        // ---- security
        clickDesc("Menu"); click("Security"); waitFor("Recovery key"); waitFor("Trusted phone")
        clickDesc("Back"); waitFor("notes.txt")
        // ---- grid view and back
        clickDesc("Grid view"); waitFor("notes.txt"); waitFor("Trips"); clickDesc("List view"); waitFor("notes.txt")
        // ---- lock + unlock + wrong password; auto-lock choice is on the lock screen
        clickDesc("Menu"); click("Lock"); waitFor("Unlock vault"); waitFor("Immediately")
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("wrong pass")
        click("Unlock"); waitFor("Wrong password")
        rule.onAllNodes(hasSetTextAction())[0].performTextClearance(); rule.onAllNodes(hasSetTextAction())[0].performTextInput("phone pass 1")
        click("Unlock"); waitFor("notes.txt")
        rule.runOnUiThread { Session.lock("test end") }
        println("FLOW OK")
    }
}
