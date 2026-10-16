package app.looi.android

import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSmokeTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun bilingualSettingsAndOfflineCompanionSurviveRecreation() {
        val runtime=(compose.activity.application as LooiApplication).runtime
        compose.waitUntil(15000) { runtime.state.value.loaded }
        compose.runOnIdle { runtime.setLanguage("en"); runtime.cameraWanted(false) }
        compose.waitUntil(5000) { runtime.state.value.language == "en" }
        compose.onNodeWithText("Companion settings").assertExists()
        compose.onNodeWithText("Start companionship").performClick()
        compose.waitUntil(5000) {runtime.state.value.phase == Phase.ACTIVE}
        compose.waitUntil(15000) {runtime.face.isReady}
        compose.onNodeWithText("Exit companionship").assertExists()
        // A ready JS bridge alone does not prove that the original eyes are visible.
        compose.waitForIdle()
        var bluePixels=0
        compose.waitUntil(5000) {
            val image=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            bluePixels=0
            for(y in 0 until image.height step 2) for(x in 0 until image.width step 2) {
                val c=image.getPixel(x,y); val blue=android.graphics.Color.blue(c); val green=android.graphics.Color.green(c); val red=android.graphics.Color.red(c)
                if(blue > 178 && blue > green && blue > red*1.2f) bluePixels++
            }
            image.recycle(); bluePixels > 1000
        }
        val layout=kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.Main) { runtime.face.layoutDiagnostics() }
        org.junit.Assert.assertTrue("Original eyes must render visible blue pixels: count=$bluePixels; $layout",bluePixels > 1000)
        val token=runtime.gate.generation
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        org.junit.Assert.assertEquals(token,runtime.gate.generation)
        compose.waitUntil(15000) {runtime.face.isReady}
        compose.onNodeWithText("Exit").performClick()
        compose.waitUntil(5000) {runtime.state.value.phase == Phase.IDLE}
        compose.onNodeWithText("简体中文").performScrollTo().performClick()
        compose.waitUntil(5000) {runtime.state.value.language == "zh"}
        compose.onNodeWithText("陪伴设置").assertExists()
        compose.onNodeWithText("开始陪伴").assertExists()
    }
}
