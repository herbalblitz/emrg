package com.emrg.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

val EmrgTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 22.sp,
        fontWeight = androidx.compose.ui.text.FontWeight.Bold,
    ),
    titleMedium = TextStyle(
        fontSize = 18.sp,
        fontWeight = androidx.compose.ui.text.FontWeight.Bold,
    ),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(
        fontSize = 14.sp,
        fontWeight = androidx.compose.ui.text.FontWeight.Medium,
    ),
    labelMedium = TextStyle(fontSize = 12.sp),
)
