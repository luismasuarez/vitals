package dev.vitals.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.vitals.app.AppViewModel
import dev.vitals.app.ui.screens.AccessScreen
import dev.vitals.app.ui.screens.AlertsScreen
import dev.vitals.app.ui.screens.ConnectionScreen
import dev.vitals.app.ui.screens.DiagnosticsScreen
import dev.vitals.app.ui.screens.MoreScreen
import dev.vitals.app.ui.screens.OverviewScreen
import dev.vitals.app.ui.screens.PermissionsScreen
import dev.vitals.app.ui.screens.ProcessesScreen
import dev.vitals.app.ui.screens.ResourcesScreen
import dev.vitals.app.ui.screens.SettingsScreen

@Composable
fun VitalsApp(vm: AppViewModel) {
    BackHandler(enabled = vm.session != null && vm.canGoBack) { vm.back() }

    Box(Modifier.fillMaxSize().background(Palette.Bg)) {
        if (vm.session == null) {
            Onboarding(vm)
        } else {
            BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding()) {
                val wide = maxWidth >= 600.dp
                Row(Modifier.fillMaxSize()) {
                    if (wide) {
                        NavRail(current = vm.screen, onSelect = { vm.navigate(it) })
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        TopBar(vm)
                        Box(Modifier.weight(1f)) {
                            AnimatedContent(
                                targetState = vm.screen,
                                transitionSpec = {
                                    fadeIn(tween(180)) togetherWith fadeOut(tween(120))
                                },
                                label = "screen",
                            ) { screen ->
                                when (screen) {
                                    Screen.Overview -> OverviewScreen(vm)
                                    Screen.Resources -> ResourcesScreen(vm)
                                    Screen.Access -> AccessScreen(vm)
                                    Screen.Alerts -> AlertsScreen(vm)
                                    Screen.More -> MoreScreen(vm)
                                    Screen.Processes -> ProcessesScreen(vm)
                                    Screen.Diagnostics -> DiagnosticsScreen(vm)
                                    Screen.Permissions -> PermissionsScreen(vm)
                                    Screen.Connection -> ConnectionScreen(vm)
                                    Screen.Settings -> SettingsScreen(vm)
                                }
                            }
                        }
                        if (!wide && vm.screen.showsTabBar) {
                            TabBar(
                                current = vm.screen,
                                onSelect = { vm.navigate(it) },
                                modifier = Modifier.navigationBarsPadding(),
                            )
                        } else {
                            Box(Modifier.navigationBarsPadding())
                        }
                    }
                }
            }
        }

        ToastHost(
            message = vm.toast,
            onDismiss = { vm.toast = null },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = if (vm.session != null && vm.screen.showsTabBar) 84.dp else 20.dp),
        )
    }
}
