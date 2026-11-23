package dev.vitals.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.vitals.app.AppViewModel

@Composable
fun VitalsApp(vm: AppViewModel) {
    var sheet by remember { mutableStateOf(Sheet.None) }

    Box(Modifier.fillMaxSize().background(Palette.Bg)) {
        if (vm.session == null) {
            Onboarding(vm)
        } else {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                TopBar(
                    vm = vm,
                    onSettings = { sheet = Sheet.Settings },
                    onEndpoints = { sheet = Sheet.Endpoints },
                )
                Box(Modifier.weight(1f)) {
                    when (vm.screen) {
                        Screen.Home -> HomeScreen(vm)
                        Screen.Cpu -> CpuScreen(vm)
                        Screen.Net -> NetScreen(vm)
                        Screen.Procs -> ProcsScreen(vm)
                        Screen.Rules -> RulesScreen(vm)
                        Screen.Caps -> CapsScreen(vm)
                    }
                }
                TabBar(
                    current = vm.screen,
                    onSelect = vm::selectScreen,
                    modifier = Modifier.navigationBarsPadding(),
                )
            }
        }

        ToastHost(
            message = vm.toast,
            onDismiss = { vm.toast = null },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 88.dp),
        )

        VitalsSheets(vm = vm, sheet = sheet, onClose = { sheet = Sheet.None })
    }
}
