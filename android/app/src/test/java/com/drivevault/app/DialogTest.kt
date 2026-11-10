package com.drivevault.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import com.drivevault.app.ui.InputDialog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DialogTest {
    @get:Rule val rule = createComposeRule()
    @Test fun menuThenDialog() {
        rule.setContent { MaterialTheme {
            var menu by remember { mutableStateOf(false) }; var dlg by remember { mutableStateOf(false) }
            Box { Button({ menu = true }) { Text("Open") }
                DropdownMenu(menu, { menu = false }) { DropdownMenuItem({ Text("New folder") }, { menu = false; dlg = true }) } }
            if (dlg) InputDialog("T", "L", "x", onDismiss = { dlg = false }) { null }
        } }
        rule.onNodeWithText("Open").performClick(); rule.onNodeWithText("New folder").performClick()
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("abc"); println("MENU+DIALOG OK")
    }
    @Test fun dialogWithFocus() {
        Session.autoFocus = true
        rule.setContent { MaterialTheme { InputDialog("T", "L", "x", onDismiss = {}) { null } } }
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("abc"); println("FOCUS OK")
    }
}
