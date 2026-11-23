package dev.vitals.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dev.vitals.app.ui.VitalsApp
import dev.vitals.app.ui.VitalsTheme

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Testing hook: `adb shell am start -n dev.vitals.app/.MainActivity \
        //   -e pair "vitals1:..."` pairs without the camera.
        intent?.getStringExtra("pair")?.let { vm.autoPair(it) }
        // Testing hook: `-e watch 1` enables the foreground watchdog (and `0`
        // disables it) without tapping the settings sheet.
        intent?.getStringExtra("watch")?.let { vm.setWatchdog(it != "0" && it != "false") }
        setContent {
            VitalsTheme {
                VitalsApp(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("pair")?.let { vm.autoPair(it) }
    }

    override fun onResume() {
        super.onResume()
        vm.onVisible()
    }

    override fun onPause() {
        super.onPause()
        vm.onHidden()
    }
}
