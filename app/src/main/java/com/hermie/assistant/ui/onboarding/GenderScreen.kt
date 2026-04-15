package com.hermie.assistant.ui.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermie.assistant.ui.components.HermieButton
import com.hermie.assistant.ui.components.HermieOptionCard
import com.hermie.assistant.ui.components.HermieSectionLabel
import com.hermie.assistant.ui.theme.HermieSerif
import com.hermie.assistant.ui.theme.HermieForest
import com.hermie.assistant.ui.theme.HermieGrey

@Composable
fun GenderScreen(
    selectedGender: String,
    onGenderSelected: (String) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
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

        HermieSectionLabel("GETTING TO KNOW YOU")

        Spacer(Modifier.height(12.dp))

        Text(
            text = "Tell us about\nyourself",
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
            text = "This helps Hermie personalise your experience",
            style = TextStyle(
                fontFamily = HermieSerif,
                fontSize = 15.sp,
                color = HermieGrey,
                textAlign = TextAlign.Center
            )
        )

        Spacer(Modifier.height(40.dp))

        val options = listOf("Male" to "male", "Female" to "female", "Other" to "other")
        options.forEach { (label, value) ->
            HermieOptionCard(
                text = label,
                selected = selectedGender == value,
                onClick = { onGenderSelected(value) }
            )
            Spacer(Modifier.height(12.dp))
        }

        Spacer(Modifier.weight(1f))

        HermieButton(
            text = "Proceed",
            onClick = onNext,
            enabled = selectedGender.isNotEmpty(),
            modifier = Modifier.padding(bottom = 32.dp)
        )
    }
}
