package dev.vitals.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dev.vitals.app.AppViewModel
import dev.vitals.app.core.PairingPayload
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun Onboarding(vm: AppViewModel) {
    Box(Modifier.fillMaxSize().background(Palette.Bg)) {
        when (vm.onboardStep) {
            OnboardStep.Intro -> IntroStep(vm)
            OnboardStep.Scanning -> ScannerStep(vm)
            OnboardStep.Found -> FoundStep(vm, vm.pending)
            OnboardStep.Pairing -> PairingStep()
            OnboardStep.Done -> DoneStep(vm)
            OnboardStep.Error -> ErrorStep(vm)
        }
    }
}

/* ---------------------------------- intro ---------------------------------- */

@Composable
private fun IntroStep(vm: AppViewModel) {
    var manualOpen by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.height(40.dp))
        Logo()
        Spacer(Modifier.height(18.dp))
        Text("vitals", color = Palette.Text, fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Las métricas de tu host, en el bolsillo.",
            color = Palette.Muted,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(44.dp))

        PrimaryButton("Escanear QR") { vm.beginScanning() }
        Spacer(Modifier.height(12.dp))
        GhostButton(if (manualOpen) "Ocultar código manual" else "Pegar el código a mano") {
            manualOpen = !manualOpen
        }

        if (manualOpen) {
            Spacer(Modifier.height(12.dp))
            BasicTextField(
                value = manual,
                onValueChange = { manual = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(84.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Palette.Bg)
                    .border(1.dp, Palette.LineStrong, RoundedCornerShape(12.dp))
                    .padding(10.dp),
                textStyle = TextStyle(
                    color = Palette.Text,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                ),
                cursorBrush = SolidColor(Palette.Accent),
                decorationBox = { inner ->
                    if (manual.isBlank()) {
                        Text("vitals1:eyJ2IjoxLCJuYW1lIjoi…", color = Palette.Dim, fontSize = 11.sp)
                    }
                    inner()
                },
            )
            Spacer(Modifier.height(10.dp))
            PrimaryButton("Conectar") {
                if (manual.isBlank()) {
                    vm.toast = "Pega primero el código"
                } else {
                    vm.acceptPayload(manual)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "Genera el QR en el host con  vitals pair.  Caduca en 5 minutos.",
            color = Palette.Dim,
            fontSize = 11.5.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun Logo() {
    Box(
        Modifier
            .size(68.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF101E18))
            .border(1.dp, Palette.Accent.copy(alpha = 0.25f), RoundedCornerShape(20.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(40.dp)) {
            val u = size.minDimension / 48f
            val stroke = 3f * u
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(4 * u, 30 * u)
                lineTo(11 * u, 30 * u)
                lineTo(15 * u, 16 * u)
                lineTo(21 * u, 42 * u)
                lineTo(26 * u, 22 * u)
                lineTo(30 * u, 30 * u)
                lineTo(44 * u, 30 * u)
            }
            drawPath(path, Palette.Accent, style = Stroke(stroke, cap = StrokeCap.Round))
        }
    }
}

/* --------------------------------- scanner --------------------------------- */

@Composable
private fun ScannerStep(vm: AppViewModel) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }
    var manual by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Escanear QR", color = Palette.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Apunta al código que muestra el host.",
            color = Palette.Muted,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(26.dp))
                .background(Color(0xFF0B1014))
                .border(1.dp, Palette.Accent.copy(alpha = 0.35f), RoundedCornerShape(26.dp)),
        ) {
            if (granted) {
                CameraPreview(onQr = { raw -> vm.acceptPayload(raw) })
                ScanOverlay()
            } else {
                Column(
                    Modifier.fillMaxSize().padding(28.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Sin permiso de cámara", color = Palette.Text, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Concede el permiso o pega el código a mano.",
                        color = Palette.Dim,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        BasicTextField(
            value = manual,
            onValueChange = { manual = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(76.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Palette.Surface)
                .border(1.dp, Palette.LineStrong, RoundedCornerShape(12.dp))
                .padding(10.dp),
            textStyle = TextStyle(color = Palette.Text, fontSize = 11.sp, fontFamily = FontFamily.Monospace),
            cursorBrush = SolidColor(Palette.Accent),
            decorationBox = { inner ->
                if (manual.isBlank()) Text("vitals1:…", color = Palette.Dim, fontSize = 11.sp)
                inner()
            },
        )
        Spacer(Modifier.height(10.dp))
        PrimaryButton("Conectar con este código") {
            if (manual.isBlank()) vm.toast = "Pega primero el código" else vm.acceptPayload(manual)
        }
        Spacer(Modifier.height(10.dp))
        GhostButton("Cancelar") { vm.cancelScanning() }
    }
}

@Suppress("MissingPermission")
@Composable
private fun CameraPreview(onQr: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    DisposableEffect(Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        val executor = ContextCompat.getMainExecutor(context)
        var analyzer: QrAnalyzer? = null

        future.addListener({
            runCatching {
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analyzer = QrAnalyzer(onQr).also { analysis.setAnalyzer(executor, it) }
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            }
        }, executor)

        onDispose {
            runCatching { future.get().unbindAll() }
            analyzer?.close()
        }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

@Composable
private fun ScanOverlay() {
    val transition = rememberInfiniteTransition(label = "scan")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1900, easing = LinearEasing), RepeatMode.Restart),
        label = "line",
    )

    Canvas(Modifier.fillMaxSize()) {
        val inset = 26.dp.toPx()
        val arm = 22.dp.toPx()
        val stroke = 2.dp.toPx()
        val color = Palette.Accent
        val left = inset
        val top = inset
        val right = size.width - inset
        val bottom = size.height - inset

        drawLine(color, Offset(left, top), Offset(left + arm, top), stroke, StrokeCap.Round)
        drawLine(color, Offset(left, top), Offset(left, top + arm), stroke, StrokeCap.Round)
        drawLine(color, Offset(right, top), Offset(right - arm, top), stroke, StrokeCap.Round)
        drawLine(color, Offset(right, top), Offset(right, top + arm), stroke, StrokeCap.Round)
        drawLine(color, Offset(left, bottom), Offset(left + arm, bottom), stroke, StrokeCap.Round)
        drawLine(color, Offset(left, bottom), Offset(left, bottom - arm), stroke, StrokeCap.Round)
        drawLine(color, Offset(right, bottom), Offset(right - arm, bottom), stroke, StrokeCap.Round)
        drawLine(color, Offset(right, bottom), Offset(right, bottom - arm), stroke, StrokeCap.Round)

        val y = top + (bottom - top) * progress
        drawLine(
            color.copy(alpha = 0.75f),
            Offset(left + 6.dp.toPx(), y),
            Offset(right - 6.dp.toPx(), y),
            stroke,
            StrokeCap.Round,
        )
    }
}

private class QrAnalyzer(private val onQr: (String) -> Unit) : ImageAnalysis.Analyzer {

    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    )
    private val handled = AtomicBoolean(false)

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        val media = image.image
        if (media == null) {
            image.close()
            return
        }
        val input = InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees)
        scanner.process(input)
            .addOnSuccessListener { barcodes ->
                val raw = barcodes.firstOrNull { it.rawValue?.startsWith("vitals1:") == true }?.rawValue
                if (raw != null && handled.compareAndSet(false, true)) onQr(raw)
            }
            .addOnCompleteListener { image.close() }
    }

    fun close() {
        runCatching { scanner.close() }
    }
}

/* ---------------------------------- found ---------------------------------- */

@Composable
private fun FoundStep(vm: AppViewModel, payload: PairingPayload?) {
    if (payload == null) {
        IntroStep(vm)
        return
    }
    val remaining = (payload.expiresAt - System.currentTimeMillis() / 1000).coerceAtLeast(0)
    val expiry = if (remaining > 0) "${remaining / 60}m ${remaining % 60}s" else "caducado"

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        VitalsCard {
            CardHeader("Código leído")
            KeyValue("Host", payload.name.ifBlank { "host" })
            KeyValue("Direcciones", "${payload.endpoints.size} · ${AppViewModel.kindOf(payload.endpoints.first())}")
            KeyValue("Pin del certificado", payload.pin)
            KeyValue("Código", payload.code)
            KeyValue("Caduca", expiry)
        }
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Emparejar") { vm.pair() }
        Spacer(Modifier.height(10.dp))
        GhostButton("Escanear otro") { vm.beginScanning() }
    }
}

@Composable
private fun KeyValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Text(label, color = Palette.Muted, fontSize = 12.5.sp)
        Spacer(Modifier.width(16.dp))
        Text(
            value,
            color = Palette.Text,
            fontSize = if (label.startsWith("Pin") || label == "Código") 11.sp else 12.5.sp,
            fontFamily = if (label.startsWith("Pin") || label == "Código") FontFamily.Monospace else FontFamily.Default,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
        )
    }
}

/* -------------------------------- pairing ---------------------------------- */

@Composable
private fun PairingStep() {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = Palette.Accent, strokeWidth = 3.dp, modifier = Modifier.size(34.dp))
        Spacer(Modifier.height(18.dp))
        Text("Validando el código con el host…", color = Palette.Muted, fontSize = 13.sp)
    }
}

@Composable
private fun DoneStep(vm: AppViewModel) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(Palette.AccentDim)
                .border(1.dp, Palette.Accent.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(34.dp)) {
                val stroke = 2.5.dp.toPx()
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width * 0.16f, size.height * 0.52f)
                    lineTo(size.width * 0.4f, size.height * 0.76f)
                    lineTo(size.width * 0.86f, size.height * 0.26f)
                }
                drawPath(path, Palette.Accent, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("Emparejado", color = Palette.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            vm.session?.let { "${it.name} · ${plural(it.endpoints.size, "dirección", "direcciones")}" } ?: "",
            color = Palette.Muted,
            fontSize = 12.5.sp,
        )
        Spacer(Modifier.height(28.dp))
        PrimaryButton("Ver el host") { vm.enterApp() }
    }
}

@Composable
private fun ErrorStep(vm: AppViewModel) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0x12FF6B6B))
                .border(1.dp, Palette.Danger.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
                .padding(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("No se pudo conectar", color = Palette.Danger, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    vm.pairingError ?: "Error desconocido.",
                    color = Palette.Muted,
                    fontSize = 12.sp,
                )
                Text(
                    "Si el código caducó, genera otro con  vitals pair  en el host.",
                    color = Palette.Dim,
                    fontSize = 11.5.sp,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Reintentar") { if (vm.pending != null) vm.pair() else vm.beginScanning() }
        Spacer(Modifier.height(10.dp))
        GhostButton("Empezar de nuevo") { vm.backToIntro() }
    }
}

internal fun formatTime(epochSeconds: Long?): String {
    if (epochSeconds == null) return "nunca"
    val fmt = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())
    return fmt.format(Date(epochSeconds * 1000))
}
