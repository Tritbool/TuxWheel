package io.github.tuxwheel.compose

import android.widget.TextView
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import io.github.tuxwheel.R

@Composable
fun EventsScreen(text: String, scrollState: ScrollState) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val appearance = remember(context) { TextView(context) }
    val font = FontFamily(Font(R.font.prime))
    Text(
        text = text,
        style = TextStyle(
            color = Color(appearance.currentTextColor),
            fontSize = with(density) { appearance.textSize.toSp() },
            fontFamily = font,
            textAlign = TextAlign.Start,
            platformStyle = PlatformTextStyle(includeFontPadding = true)
        ),
        modifier = Modifier.fillMaxSize().verticalScroll(scrollState)
    )
}
