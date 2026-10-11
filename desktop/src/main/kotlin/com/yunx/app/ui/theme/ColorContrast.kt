package com.yunx.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** 原始种子色不是主题容器色，图标前景需按实际亮度选择。 */
internal fun readableSeedForeground(background: Color): Color =
    if ((background.luminance() + 0.05f) / 0.05f >= 1.05f / (background.luminance() + 0.05f))
        Color.Black else Color.White
