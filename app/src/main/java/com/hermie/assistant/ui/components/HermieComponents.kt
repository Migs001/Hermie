package com.hermie.assistant.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermie.assistant.ui.theme.*
import com.hermie.assistant.ui.theme.HermieSerif

/** Primary full-width button */
@Composable
fun HermieButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val bgColor by animateColorAsState(
        if (enabled) HermieForest else HermieGrey.copy(alpha = 0.4f),
        label = "btn_bg"
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = TextStyle(
                fontFamily = HermieSerif,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = HermieCream
            )
        )
    }
}

/** Secondary outlined button */
@Composable
fun HermieOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(
                BorderStroke(1.5.dp, if (enabled) HermieForest else HermieGrey),
                RoundedCornerShape(16.dp)
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = TextStyle(
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) HermieForest else HermieGrey
            )
        )
    }
}

/** Clean text field inspired by reference image 1 */
@Composable
fun HermieTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = TextStyle(
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal,
                color = HermieGrey,
                letterSpacing = 0.5.sp
            ),
            modifier = Modifier.padding(bottom = 4.dp, start = 4.dp)
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = TextStyle(
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = HermieForest
            ),
            cursorBrush = SolidColor(HermieForest),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier
                .fillMaxWidth()
                .background(HermieOffWhite, RoundedCornerShape(14.dp))
                .padding(horizontal = 16.dp, vertical = 18.dp),
            decorationBox = { innerTextField ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = TextStyle(
                                fontSize = 18.sp,
                                color = HermieGrey.copy(alpha = 0.5f)
                            )
                        )
                    }
                    innerTextField()
                }
            }
        )
    }
}

/** Selectable option chip/card (for gender selection, model tiers, etc.) */
@Composable
fun HermieOptionCard(
    text: String,
    subtitle: String? = null,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor by animateColorAsState(
        if (selected) HermieForest else HermieOffWhite,
        animationSpec = tween(200),
        label = "option_bg"
    )
    val textColor = if (selected) HermieCream else HermieForest

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        Column {
            Text(
                text = text,
                style = TextStyle(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = textColor
                )
            )
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = TextStyle(
                        fontSize = 13.sp,
                        color = if (selected) HermieCream.copy(alpha = 0.7f) else HermieGrey
                    )
                )
            }
        }
    }
}

/** Section header like "PERSONAL INFORMATION" in reference */
@Composable
fun HermieSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = TextStyle(
            fontFamily = HermieSerif,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = HermieGrey,
            letterSpacing = 1.5.sp
        ),
        modifier = modifier
    )
}

/** Progress dots for onboarding */
@Composable
fun OnboardingProgress(
    currentStep: Int,
    totalSteps: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(totalSteps) { step ->
            val width by animateFloatAsState(
                if (step == currentStep) 24f else 8f,
                animationSpec = tween(300),
                label = "dot_width"
            )
            val color by animateColorAsState(
                when {
                    step < currentStep -> HermieForest
                    step == currentStep -> HermieTerra
                    else -> HermieTan
                },
                label = "dot_color"
            )
            Box(
                modifier = Modifier
                    .height(8.dp)
                    .width(width.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color)
            )
        }
    }
}
