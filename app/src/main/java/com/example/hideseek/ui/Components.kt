package com.example.hideseek.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hideseek.ui.theme.Hs
import androidx.compose.material3.Button as M3Button

fun Context.shareText(text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    startActivity(Intent.createChooser(send, "Share room code"))
}

/** Standard page: dark background, safe-area padding, optional vertical scroll. */
@Composable
fun HsScreen(
    modifier: Modifier = Modifier,
    background: Color = Hs.Bg,
    scroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().background(background).safeDrawingPadding()) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

/** Secondary page with a simple back header. */
@Composable
fun HsPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    HsScreen {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "←",
                color = Hs.Text,
                fontSize = 26.sp,
                modifier = Modifier.clickable(onClick = onBack).padding(end = 16.dp, top = 4.dp, bottom = 4.dp),
            )
            Text(title, color = Hs.Text, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        }
        content()
    }
}

@Composable
fun Title(text: String, color: Color = Hs.Text, modifier: Modifier = Modifier) =
    Text(text, color = color, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, modifier = modifier)

@Composable
fun Label(text: String, color: Color = Hs.TextDim, modifier: Modifier = Modifier) =
    Text(text.uppercase(), color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = modifier)

@Composable
fun Body(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Hs.Text,
    dim: Boolean = false,
    size: Int = 15,
    bold: Boolean = false,
    center: Boolean = false,
) = Text(
    text,
    modifier = modifier,
    color = if (dim) Hs.TextDim else color,
    fontSize = size.sp,
    lineHeight = (size * 1.45).sp,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    textAlign = if (center) TextAlign.Center else null,
)

@Composable
fun HsCard(
    modifier: Modifier = Modifier,
    borderColor: Color = Hs.Border,
    background: Color = Hs.Surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(14.dp))
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun HRow(
    modifier: Modifier = Modifier,
    gap: Int = 8,
    alignment: Alignment.Vertical = Alignment.CenterVertically,
    content: @Composable RowScope.() -> Unit,
) = Row(modifier, horizontalArrangement = Arrangement.spacedBy(gap.dp), verticalAlignment = alignment, content = content)

enum class Variant(val bg: Color, val fg: Color) {
    Primary(Hs.Primary, Hs.PrimaryText),
    Secondary(Hs.SurfaceAlt, Hs.Text),
    Police(Hs.Police, Color.White),
    Thief(Hs.Thief, Color.White),
    Danger(Hs.Danger, Color.White),
    Ghost(Color.Transparent, Hs.TextDim),
}

@Composable
fun HsButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: Variant = Variant.Primary,
    enabled: Boolean = true,
    loading: Boolean = false,
    small: Boolean = false,
) {
    M3Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.then(if (small) Modifier.heightIn(min = 40.dp) else Modifier.fillMaxWidth().heightIn(min = 52.dp)),
        shape = RoundedCornerShape(if (small) 8.dp else 14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = variant.bg,
            contentColor = variant.fg,
            disabledContainerColor = variant.bg.copy(alpha = if (variant == Variant.Ghost) 0f else 0.4f),
            disabledContentColor = variant.fg.copy(alpha = 0.6f),
        ),
        border = if (variant == Variant.Ghost) BorderStroke(1.dp, Hs.Border) else null,
    ) {
        if (loading) {
            CircularProgressIndicator(color = variant.fg, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        } else {
            Text(text, fontSize = if (small) 14.sp else 17.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun Chip(label: String, active: Boolean, onClick: () -> Unit, color: Color = Hs.Primary) {
    Box(
        Modifier
            .heightIn(min = 40.dp)
            .background(if (active) color else Color.Transparent, RoundedCornerShape(50))
            .border(1.dp, if (active) color else Hs.Border, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (active) Hs.PrimaryText else Hs.Text, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun Dot(color: Color, size: Int = 10) = Box(Modifier.size(size.dp).background(color, CircleShape))

@Composable
fun ErrorText(text: String?) {
    if (!text.isNullOrBlank()) Text(text, color = Hs.Danger, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
fun HsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    maxLength: Int = 100,
    textStyle: TextStyle = TextStyle(fontSize = 17.sp),
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.take(maxLength)) },
        placeholder = { Text(placeholder, color = Hs.TextFaint) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        textStyle = textStyle.copy(color = Hs.Text),
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Hs.SurfaceAlt,
            unfocusedContainerColor = Hs.SurfaceAlt,
            focusedBorderColor = Hs.Primary,
            unfocusedBorderColor = Hs.Border,
            cursorColor = Hs.Primary,
        ),
    )
}

/** Wraps chips onto several lines. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ChipRow(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}
