package io.github.tuxwheel.bms

import android.app.Application
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.common.truth.Truth.assertThat
import io.github.tuxwheel.R
import io.github.tuxwheel.compose.MainContainerScreen
import io.github.tuxwheel.compose.SmartBmsScreen
import io.github.tuxwheel.navigation.MainPages
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w800dp-h1000dp")
class SmartBmsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun presentation(packs: Int, cells: Int = 0) = BmsPresentation(
        listOf(BmsRow(R.string.bmsVoltage, List(packs) { "${100 + it}.00 V" })) +
            BmsMapper.cellLabels.take(cells).map { label ->
                BmsRow(label, List(packs) { "4.000 V" })
            },
        (1..packs).toList(),
        isGotway = true
    )

    private fun group(number: Int) = compose.onNodeWithText(
        compose.activity.getString(R.string.bmsGroupTitle, number)
    )

    @Test fun `packs wrap at minimum readable width and reflow when width changes`() {
        compose.activity.setTheme(R.style.OriginalTheme)
        var width by mutableStateOf(439.dp)
        var fontScale by mutableStateOf(1f)
        val scroll = ScrollState(0)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                Box(Modifier.requiredSize(width, 600.dp)) {
                    SmartBmsScreen(presentation(3), R.style.OriginalTheme, scroll)
                }
            }
        }
        val first = group(1).getUnclippedBoundsInRoot()
        val second = group(2).getUnclippedBoundsInRoot()
        assertThat(second.top).isGreaterThan(first.bottom)
        assertThat(second.left).isEqualTo(first.left)

        compose.runOnIdle { width = 440.dp }
        val twoColumns = group(2).getUnclippedBoundsInRoot()
        assertThat(twoColumns.top).isEqualTo(group(1).getUnclippedBoundsInRoot().top)
        assertThat(twoColumns.left).isGreaterThan(group(1).getUnclippedBoundsInRoot().left)
        assertThat(group(3).getUnclippedBoundsInRoot().top).isGreaterThan(twoColumns.bottom)

        compose.runOnIdle { width = 660.dp }
        assertThat(group(3).getUnclippedBoundsInRoot().top)
            .isEqualTo(group(1).getUnclippedBoundsInRoot().top)
        compose.onNodeWithText("102.00 V").assertIsDisplayed()

        compose.runOnIdle { fontScale = 2f }
        assertThat(group(2).getUnclippedBoundsInRoot().top)
            .isGreaterThan(group(1).getUnclippedBoundsInRoot().bottom)
    }

    @Test fun `all five packs scroll vertically without trapping main pager swipes`() {
        compose.activity.setTheme(R.style.OriginalTheme)
        val ids = listOf(R.layout.main_view_smart_bms, R.layout.main_view_graph, R.layout.main_view_events)
        var pages by mutableStateOf(MainPages(ids, ids.first()))
        val scroll = ScrollState(0)
        val header = View(compose.activity).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)
        }
        compose.setContent {
            Box(Modifier.requiredSize(360.dp, 480.dp)) {
                MainContainerScreen(pages, header, { pages = pages.select(it) }) { id ->
                    if (id == R.layout.main_view_smart_bms) {
                        // Match the nested ComposeView hosted by MainContainerRenderer.
                        AndroidView(
                            modifier = Modifier.fillMaxSize().testTag("bms"),
                            factory = { context ->
                                ComposeView(context).apply {
                                    setViewCompositionStrategy(
                                        ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                                    setContent {
                                        SmartBmsScreen(presentation(5, 30), R.style.OriginalTheme, scroll)
                                    }
                                }
                            }
                        )
                    } else {
                        Text(if (id == R.layout.main_view_graph) "Chart" else "Events", Modifier.fillMaxSize())
                    }
                }
            }
        }
        compose.onNodeWithTag("bms").performTouchInput { swipeUp() }
        compose.runOnIdle { assertThat(scroll.value).isGreaterThan(0) }
        (1..5).forEach { number ->
            group(number).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("${99 + number}.00 V").performScrollTo().assertIsDisplayed()
        }
        compose.runOnIdle { assertThat(scroll.value).isGreaterThan(0) }
        group(5).performScrollTo()
        compose.onNodeWithTag("bms").performTouchInput { swipeLeft() }
        compose.onNodeWithText("Chart").assertIsDisplayed().performTouchInput { swipeLeft() }
        compose.onNodeWithText("Events").assertIsDisplayed().performTouchInput { swipeRight() }
        compose.onNodeWithText("Chart").assertIsDisplayed().performTouchInput { swipeRight() }
        group(5).assertIsDisplayed()
        compose.runOnIdle { assertThat(pages.selectedId).isEqualTo(R.layout.main_view_smart_bms) }
    }
}
