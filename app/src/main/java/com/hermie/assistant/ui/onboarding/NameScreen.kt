package com.hermie.assistant.ui.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermie.assistant.ui.components.HermieButton
import com.hermie.assistant.ui.components.HermieSectionLabel
import com.hermie.assistant.ui.components.HermieTextField
import com.hermie.assistant.ui.theme.HermieForest
import com.hermie.assistant.ui.theme.HermieGrey

@Composable
fun NameScreen(
    name: String,
    onNameChanged: (String) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = HermieForest,
            modifier = Modifier
                .size(28.dp)
                .clickable(onClick = onBack)
        )

        Spacer(Modifier.height(24.dp))

        HermieSectionLabel("PERSONAL INFORMATION")

        Spacer(Modifier.height(12.dp))

        Text(
            text = "Enter your name",
            style = TextStyle(
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = HermieForest,
                lineHeight = 38.sp
            )
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "What should Hermie call you?",
            style = TextStyle(fontSize = 15.sp, color = HermieGrey)
        )

        Spacer(Modifier.height(40.dp))

        HermieTextField(
            value = name,
            onValueChange = onNameChanged,
            label = "Your Name",
            placeholder = "Enter your name"
        )

        Spacer(Modifier.weight(1f))

        HermieButton(
            text = "Proceed",
            onClick = onNext,
            enabled = name.isNotBlank(),
            modifier = Modifier.padding(bottom = 32.dp)
        )
    }
}
