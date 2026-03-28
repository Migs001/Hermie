package com.hermie.assistant.ui.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermie.assistant.ui.components.HermieButton
import com.hermie.assistant.ui.components.HermieOptionCard
import com.hermie.assistant.ui.components.HermieSectionLabel
import com.hermie.assistant.ui.theme.HermieForest
import com.hermie.assistant.ui.theme.HermieGrey

@Composable
fun GenderScreen(
    selectedGender: String,
    onGenderSelected: (String) -> Unit,
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
    ) {
        Spacer(Modifier.height(32.dp))

        HermieSectionLabel("GETTING TO KNOW YOU")

        Spacer(Modifier.height(12.dp))

        Text(
            text = "Tell us about\nyourself",
            style = TextStyle(
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = HermieForest,
                lineHeight = 38.sp
            )
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "This helps Hermie personalise your experience",
            style = TextStyle(fontSize = 15.sp, color = HermieGrey)
        )

        Spacer(Modifier.height(40.dp))

        val options = listOf("Boy" to "boy", "Girl" to "girl", "Other" to "other")
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
            text = "Continue",
            onClick = onNext,
            enabled = selectedGender.isNotEmpty(),
            modifier = Modifier.padding(bottom = 32.dp)
        )
    }
}
