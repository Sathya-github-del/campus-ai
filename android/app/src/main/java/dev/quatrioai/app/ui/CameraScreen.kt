package dev.quatrioai.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dev.quatrioai.app.R
import dev.quatrioai.app.core.OcrText
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Ask with a photo: photograph a question (a page, a screen, a note) or pick a screenshot, read its
 * text on-device with ML Kit, and hand the text back to the question box to edit. No image is uploaded.
 * Re-skinned for the meter identity (amber viewfinder brackets and shutter); the capture/OCR flow is unchanged.
 */
@Composable
fun CameraScreen(onText: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var hasPermission by remember { mutableStateOf(context.hasCameraPermission()) }
    var askedOnce by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
            // ~2MP is plenty for printed text and keeps OCR fast on low-end phones.
            imageCaptureResolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(Size(1920, 1440), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                .build()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            controller.unbind()
            recognizer.close()
        }
    }

    fun finish(result: Result<String>) {
        busy = false
        result.fold(
            onSuccess = { raw ->
                val text = OcrText.clean(raw)
                if (text.isBlank()) problem = "Couldn't find any text in that photo. Try again closer, in good light, holding steady."
                else onText(text)
            },
            onFailure = { e ->
                if ((e as? MlKitException)?.errorCode == MlKitException.UNAVAILABLE) {
                    // Model not downloaded yet (normally fetched at install time): ask Play services for it now.
                    runCatching {
                        ModuleInstall.getClient(context)
                            .installModules(ModuleInstallRequest.newBuilder().addApi(recognizer).build())
                    }
                    problem = "The text reader is still being set up on your phone. Check your internet and try again in a minute."
                } else {
                    problem = "Couldn't read that photo. Please try again."
                }
            },
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        askedOnce = true
    }
    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            busy = true
            problem = null
            scope.launch {
                finish(runCatching { recognizer.process(InputImage.fromFilePath(context, uri)).await().text })
            }
        }
    }
    LaunchedEffect(Unit) { if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA) }
    // Back from system settings with the permission granted: pick it up without restarting the screen.
    LifecycleResumeEffect(Unit) {
        hasPermission = context.hasCameraPermission()
        onPauseOrDispose { }
    }
    LaunchedEffect(hasPermission) { if (hasPermission) controller.bindToLifecycle(lifecycleOwner) }

    Box(Modifier.fillMaxSize().background(Ink.Panel)) {
        if (hasPermission) {
            AndroidView(
                factory = { ctx -> PreviewView(ctx).also { it.controller = controller } },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            PermissionNeeded(
                askedOnce = askedOnce,
                onAllow = {
                    if (askedOnce) context.openAppSettings() else permissionLauncher.launch(Manifest.permission.CAMERA)
                },
                onPick = { pickLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            )
        }

        Column(Modifier.fillMaxSize()) {
            // Dark scrims keep the white controls readable over a bright page.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().background(Color(0x99000000)).statusBarsPadding(),
            ) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_back), contentDescription = "Back", tint = Color.White)
                }
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text("Fit the question in the frame", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text("Read on your phone. The photo is never uploaded.", color = Ink.PaperDim, style = MaterialTheme.typography.bodySmall)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(28.dp)) {
                if (hasPermission) ViewfinderBrackets(Modifier.fillMaxSize())
            }
            Column(Modifier.fillMaxWidth().background(Color(0x99000000)).navigationBarsPadding()) {
                problem?.let {
                    Surface(color = Color(0xCC202020), shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                        Text(it, color = Color.White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
                    }
                }
                if (busy) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        Text("  Reading the text…", color = Color.White)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 24.dp, top = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { pickLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        enabled = !busy,
                    ) {
                        Icon(painterResource(R.drawable.ic_gallery), contentDescription = "Choose a photo instead", tint = Color.White)
                    }
                    FilledIconButton(
                        onClick = {
                            busy = true
                            problem = null
                            capture(context, controller, recognizer, ::finish)
                        },
                        enabled = hasPermission && !busy,
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Lit.Tangerine, contentColor = Color(0xFF2B1600),
                            disabledContainerColor = Ink.Raised3, disabledContentColor = Ink.PaperFaint,
                        ),
                        modifier = Modifier.size(80.dp).border(3.dp, Color.White.copy(alpha = 0.85f), CircleShape).padding(6.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_camera), contentDescription = "Take photo", modifier = Modifier.size(32.dp))
                    }
                    Spacer(Modifier.size(48.dp))
                }
            }
        }
    }
}

@Composable
private fun PermissionNeeded(askedOnce: Boolean, onAllow: () -> Unit, onPick: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Ask with a photo", color = Ink.Paper, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(10.dp))
        Text(
            "Allow the camera to snap a page, a screen or a note. The photo is read on your phone and is never uploaded.",
            color = Ink.PaperDim, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onAllow,
            colors = ButtonDefaults.buttonColors(containerColor = Lit.Tangerine, contentColor = Color(0xFF2B1600)),
        ) { Text(if (askedOnce) "Open settings" else "Allow camera") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onPick, border = BorderStroke(1.dp, Ink.Line)) { Text("Choose a photo instead", color = Ink.Paper) }
    }
}

/** Amber corner brackets marking where to put the question. */
@Composable
private fun ViewfinderBrackets(modifier: Modifier) {
    Canvas(modifier) {
        val len = size.minDimension * 0.12f
        val w = 4.dp.toPx()
        val c = Lit.Amber
        val cap = StrokeCap.Round
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(c, Offset(x, y), Offset(x + dx * len, y), w, cap)
            drawLine(c, Offset(x, y), Offset(x, y + dy * len), w, cap)
        }
        corner(0f, 0f, 1f, 1f)
        corner(size.width, 0f, -1f, 1f)
        corner(0f, size.height, 1f, -1f)
        corner(size.width, size.height, -1f, -1f)
    }
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
private fun capture(
    context: Context,
    controller: LifecycleCameraController,
    recognizer: TextRecognizer,
    done: (Result<String>) -> Unit,
) {
    controller.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
        override fun onCaptureSuccess(image: ImageProxy) {
            val media = image.image
            if (media == null) {
                image.close()
                done(Result.failure(IllegalStateException("no image")))
                return
            }
            recognizer.process(InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees))
                .addOnCompleteListener { task ->
                    image.close()
                    done(if (task.isSuccessful) Result.success(task.result.text) else Result.failure(task.exception ?: Exception()))
                }
        }

        override fun onError(exception: ImageCaptureException) = done(Result.failure(exception))
    })
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}

private fun Context.hasCameraPermission() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun Context.openAppSettings() {
    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
