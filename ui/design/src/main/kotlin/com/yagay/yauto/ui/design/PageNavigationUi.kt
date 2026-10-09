package com.yagay.yauto.ui.design

import androidx.activity.compose.BackHandler
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Shared action for top app bars. All visible back arrows must call the same
 * destination-level callback as the system/gesture BackHandler.
 */
@Composable
fun PageBackButton(onBack: () -> Unit, tint: Color = LocalContentColor.current) {
    TextButton(onClick = onBack) {
        Icon(
            painter = painterResource(R.drawable.ic_back),
            contentDescription = stringResource(R.string.icon_back),
            tint = tint,
        )
    }
}

/**
 * Common fullscreen dialog navigation policy.
 *
 * Back is delegated to the owning child stack, not straight to the screen that
 * opened the dialog. The default Android dialog dismissal would skip child pages.
 */
@Composable
fun NavigationDialog(onBack: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
        ),
    ) {
        BackHandler(onBack = onBack)
        content()
    }
}
