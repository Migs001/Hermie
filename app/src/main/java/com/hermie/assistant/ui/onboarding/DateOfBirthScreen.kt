package com.hermie.assistant.ui.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermie.assistant.ui.components.HermieButton
import com.hermie.assistant.ui.components.HermieSectionLabel
import com.hermie.assistant.ui.components.HermieTextField
import com.hermie.assistant.ui.theme.HermieSerif
import com.hermie.assistant.ui.theme.HermieForest
import com.hermie.assistant.ui.theme.HermieGrey

@Composable
fun DateOfBirthScreen(
    dateOfBirth: String,
    onDateChanged: (String) -> Unit,
    onBack: () -> Unit,
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
        Spacer(Modifier.height(8.dp))

        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = HermieForest,
            modifier = Modifier
                .size(28.dp)
                .align(Alignment.Start)
                .clickable(onClick = onBack)
        )

        Spacer(Modifier.height(48.dp))

        HermieSectionLabel("PERSONAL INFORMATION")

        Spacer(Modifier.height(12.dp))

        Text(
            text = "When were you born?",
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
            text = "So Hermie can celebrate with you",
            style = TextStyle(
                fontFamily = HermieSerif,
                fontSize = 15.sp,
                color = HermieGrey,
                textAlign = TextAlign.Center
            )
        )

        Spacer(Modifier.height(48.dp))

        HermieTextField(
            value = dateOfBirth,
            onValueChange = { input ->
                // Auto-format as DD/MM/YYYY
                val digits = input.filter { it.isDigit() }
                val formatted = buildString {
                    for (i in digits.indices) {
                        if (i == 2 || i == 4) append('/')
                        if (i < 8) append(digits[i])
                    }
                }
                onDateChanged(formatted)
            },
            label = "Date of Birth",
            placeholder = "DD/MM/YYYY",
            keyboardType = KeyboardType.Number
        )

        Spacer(Modifier.height(24.dp))

        HermieButton(
            text = "Proceed",
            onClick = onNext,
            enabled = dateOfBirth.length == 10 // DD/MM/YYYY
        )

        Spacer(Modifier.height(32.dp))
    }
}
