package app.looi.android

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.fillMaxSize
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
                LooiTheme { androidx.compose.material3.Surface(androidx.compose.ui.Modifier.fillMaxSize()) { if(state.presented) RemoteDialog(remote,true) } }
            }
            compose.runOnIdle { remote.open() }
            compose.waitUntil(5000) {remote.ready}
            compose.waitForIdle()
            compose.onNodeWithText("D-pad").performClick()
            compose.waitUntil(5000) {remote.ready}
            compose.onNodeWithContentDescription("Forward").performTouchInput { down(center) }
            compose.waitUntil(5000) {commands.isNotEmpty()}
            compose.onNodeWithContentDescription("Forward").performTouchInput { up() }
            compose.waitUntil(5000) {remote.ready && !remote.state.value.moving}
            compose.runOnIdle {assertTrue(stops>=3);assertTrue(commands.first().getDouble("linear")>0)}
            compose.onNodeWithText("Joystick").performClick()
            compose.waitUntil(5000) {remote.ready}
            val joystick=compose.onNodeWithContentDescription("Joystick: drag to steer, release to stop")
            joystick.performTouchInput { down(center);moveTo(center+androidx.compose.ui.geometry.Offset(width*.25f,-height*.25f)) }
            compose.waitUntil(5000) {commands.any {it.getDouble("linear")>0 && it.getDouble("angular")>0}}
            joystick.performTouchInput { cancel() }
            compose.waitUntil(5000) {remote.ready && !remote.state.value.moving}
            compose.waitForIdle()
            val image=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            val path=java.io.File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"remote-emulator.png")
            path.outputStream().use {image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};image.recycle()
            joystick.performCustomAccessibilityActionWithLabel("Turn left")
            compose.waitUntil(5000) {commands.any {it.getDouble("angular")<0}}
            compose.waitUntil(5000) {remote.ready && !remote.state.value.moving}
            compose.onNodeWithText("Close remote").performClick()
            compose.waitUntil(5000) {remote.state.value.phase == RemotePhase.INACTIVE}
            compose.onNodeWithText("Remote control").assertDoesNotExist()
        } finally {L10n.language=language;scope.cancel()}
    }
}
