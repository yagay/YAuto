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

/** All route-level system and gesture back events pass through this shared handler. */
@Composable
fun PageBackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}

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
            // The platform forwards Android predictive/gesture/hardware back to
            // onDismissRequest = onBack. The dialog remains visible until the owner
            // pops its one child page or explicitly closes the root.
            dismissOnBackPress = true,
        ),
    ) {
        PageBackHandler(onBack = onBack)
        content()
    }
}
