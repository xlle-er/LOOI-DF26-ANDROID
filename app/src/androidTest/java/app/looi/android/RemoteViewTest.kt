package app.looi.android

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class RemoteViewTest {
    @get:Rule val compose=createComposeRule()
    @Test fun dpadHoldReleaseAndJoystickAccessibilityPulse() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val commands=mutableListOf<JSONObject>()
        var stops=0
        val remote=RemoteControl(scope,{true},send={commands+=it},stop={stops++;true})
        val language=L10n.language;L10n.language="en"
        try {
            compose.setContent {
                val state by remote.state.collectAsState()
                LooiTheme { if(state.presented) RemoteDialog(remote,true) }
            }
            compose.runOnIdle { remote.open() }
            compose.waitUntil(5000) {remote.ready}
            compose.onNodeWithText("D-pad").performClick()
            compose.waitUntil(5000) {remote.ready}
            compose.onNodeWithContentDescription("Forward").performTouchInput { down(center) }
            compose.waitUntil(5000) {commands.isNotEmpty()}
            compose.onNodeWithContentDescription("Forward").performTouchInput { up() }
            compose.waitUntil(5000) {remote.ready && !remote.state.value.moving}
            compose.runOnIdle {assertTrue(stops>=3);assertTrue(commands.first().getDouble("linear")>0)}
            compose.onNodeWithText("Joystick").performClick()
            compose.waitUntil(5000) {remote.ready}
            compose.onNodeWithContentDescription("Joystick: drag to steer, release to stop").performCustomAccessibilityActionWithLabel("Turn left")
            compose.waitUntil(5000) {commands.any {it.getDouble("angular")<0}}
            compose.waitUntil(5000) {remote.ready && !remote.state.value.moving}
            compose.onNodeWithText("Close remote").performClick()
            compose.waitUntil(5000) {remote.state.value.phase == RemotePhase.INACTIVE}
            compose.onNodeWithText("Remote control").assertDoesNotExist()
        } finally {L10n.language=language;scope.cancel()}
    }
}
