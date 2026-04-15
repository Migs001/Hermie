package com.hermie.assistant.ui.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hermie.assistant.ui.components.HermieButton
import com.hermie.assistant.ui.components.HermieSectionLabel
import com.hermie.assistant.ui.components.HermieTextField
import com.hermie.assistant.ui.theme.HermieSerif
import com.hermie.assistant.ui.theme.HermieForest
import com.hermie.assistant.ui.theme.HermieGrey
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Composable
fun NameScreen(
    name: String,
    onNameChanged: (String) -> Unit,
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .imePadding()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(80.dp))

        HermieSectionLabel("WELCOME TO HERMIE")

        Spacer(Modifier.height(12.dp))

        Text(
            text = "What's your name?",
            style = TextStyle(
                fontFamily = HermieSerif,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = HermieForest,
                lineHeight = 38.sp,
                textAlign = TextAlign.Center
            )
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "This is what Hermie will call you",
            style = TextStyle(
                fontFamily = HermieSerif,
                fontSize = 15.sp,
                color = HermieGrey,
                textAlign = TextAlign.Center
            )
        )

        Spacer(Modifier.height(48.dp))

        HermieTextField(
            value = name,
            onValueChange = onNameChanged,
            label = "Your Name",
            placeholder = "Enter your name"
        )

        Spacer(Modifier.height(24.dp))

        HermieButton(
            text = "Proceed",
            onClick = onNext,
            enabled = name.isNotBlank()
        )

        Spacer(Modifier.height(32.dp))
    }
}
