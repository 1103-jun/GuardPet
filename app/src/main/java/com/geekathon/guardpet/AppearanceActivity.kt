package com.geekathon.guardpet

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.view.ViewGroup
import android.widget.FrameLayout
import com.geekathon.guardpet.friend.FriendClient
import dev.pranav.reef.ui.ReefTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 定制桌宠形象：主体类型、画面风格、特征描述、参考图上传。
 */
class AppearanceActivity : AppCompatActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val petSettings = PetSettings(this)
        val assets = PetAssetRepository(this)
        setContent {
            ReefTheme {
                val subjects = stringArrayResource(R.array.appearance_subject_types).toList()
                val styles = stringArrayResource(R.array.appearance_art_styles).toList()

                var subjectIndex by remember { mutableStateOf(0) }
                var styleIndex by remember { mutableStateOf(0) }
                var customSubject by remember { mutableStateOf("") }
                var customStyle by remember { mutableStateOf("") }
                var subjectExpanded by remember { mutableStateOf(false) }
                var styleExpanded by remember { mutableStateOf(false) }
                var features by remember { mutableStateOf("") }
                var freeIdea by remember {
                    mutableStateOf(
                        assets.customIdea().orEmpty().takeIf { it != "上传图片" }.orEmpty()
                    )
                }
                var quality by remember {
                    mutableStateOf(petSettings.appearanceMode == PetAppearanceGenerator.GenerationMode.QUALITY)
                }
                var dashKey by remember { mutableStateOf(petSettings.dashScopeApiKey) }
                var generating by remember { mutableStateOf(false) }
                var statusText by remember {
                    mutableStateOf(
                        if (assets.hasCustomAppearance()) {
                            getString(R.string.appearance_status_custom, assets.customIdea().orEmpty())
                        } else {
                            getString(R.string.appearance_status_idle)
                        }
                    )
                }
                var pendingUpload by remember { mutableStateOf<ByteArray?>(null) }
                var previewTick by remember { mutableIntStateOf(0) }
                val scope = rememberCoroutineScope()

                val pickImage = rememberLauncherForActivityResult(
                    ActivityResultContracts.GetContent()
                ) { uri: Uri? ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    runCatching {
                        contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    }.getOrNull()?.let { bytes ->
                        pendingUpload = bytes
                        statusText = getString(R.string.appearance_status_upload_ready)
                    }
                }

                fun refreshPetAfterAppearance() {
                    if (PetService.isRunning) {
                        startService(
                            Intent(this@AppearanceActivity, PetService::class.java)
                                .setAction(PetService.ACTION_REFRESH)
                        )
                    }
                    runCatching {
                        FriendClient.pushLocalAvatar(this@AppearanceActivity, force = true)
                    }
                    statusText = if (assets.hasCustomAppearance()) {
                        getString(R.string.appearance_status_custom, assets.customIdea().orEmpty())
                    } else {
                        getString(R.string.appearance_status_idle)
                    }
                    previewTick++
                    setResult(RESULT_OK)
                }

                fun buildGuide(): PetAppearanceAgent.PromptGuide {
                    val subjectRaw = subjects.getOrElse(subjectIndex) { "" }
                    val styleRaw = styles.getOrElse(styleIndex) { "" }
                    val subject = if (subjectRaw.contains("自定义")) {
                        customSubject.trim().ifBlank { subjectRaw }
                    } else {
                        subjectRaw
                    }
                    val style = if (styleRaw.contains("自定义")) {
                        customStyle.trim().ifBlank { styleRaw }
                    } else {
                        styleRaw
                    }
                    return PetAppearanceAgent.PromptGuide(
                        subjectType = subject,
                        artStyle = style,
                        coreFeatures = features,
                        freeIdea = freeIdea
                    )
                }

                fun runGenerate(block: () -> PetAppearanceGenerator.Result) {
                    if (generating) return
                    generating = true
                    statusText = getString(R.string.appearance_generating)
                    scope.launch {
                        val outcome = withContext(Dispatchers.IO) {
                            runCatching { block() }
                        }
                        generating = false
                        outcome.onSuccess { result ->
                            val label = when (result.source) {
                                PetAppearanceGenerator.Source.UPLOAD ->
                                    getString(R.string.appearance_label_upload)
                                else -> result.refinedPrompt.ifBlank { result.prompt }
                                    .ifBlank { "自定义形象" }
                            }
                            assets.installGeneratedAppearance(result.pngBytes, label.take(120))
                            Toast.makeText(
                                this@AppearanceActivity,
                                when (result.source) {
                                    PetAppearanceGenerator.Source.QWEN ->
                                        getString(R.string.appearance_success_qwen)
                                    PetAppearanceGenerator.Source.UPLOAD ->
                                        getString(R.string.appearance_success_upload)
                                    PetAppearanceGenerator.Source.LOCAL ->
                                        getString(R.string.appearance_success_local)
                                    PetAppearanceGenerator.Source.PLANNED ->
                                        getString(R.string.appearance_success_planned)
                                    PetAppearanceGenerator.Source.ONLINE ->
                                        getString(R.string.appearance_success_online)
                                    else -> getString(R.string.appearance_success_online)
                                },
                                Toast.LENGTH_LONG
                            ).show()
                            refreshPetAfterAppearance()
                        }.onFailure {
                            statusText = getString(
                                R.string.appearance_failed,
                                it.message ?: it.javaClass.simpleName
                            )
                            Toast.makeText(this@AppearanceActivity, statusText, Toast.LENGTH_LONG).show()
                        }
                    }
                }

                val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(scroll.nestedScrollConnection),
                    topBar = {
                        LargeTopAppBar(
                            title = {
                                Text(
                                    stringResource(R.string.appearance_title),
                                    style = MaterialTheme.typography.headlineLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = (-1).sp
                                    )
                                )
                            },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                                }
                            },
                            scrollBehavior = scroll
                        )
                    }
                ) { padding ->
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp)
                            .padding(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            stringResource(R.string.appearance_guide_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        AndroidView(
                            factory = { ctx ->
                                PetCanvas(ctx).apply {
                                    layoutParams = FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        (200 * resources.displayMetrics.density).toInt()
                                    )
                                    fitPreviewToCanvas()
                                    showForced(
                                        assets.fileFor(PetState.IDLE)
                                            ?: assets.randomFileFor(PetState.IDLE)
                                    )
                                }
                            },
                            update = { canvas ->
                                @Suppress("UNUSED_EXPRESSION")
                                previewTick
                                canvas.showForced(
                                    assets.fileFor(PetState.IDLE)
                                        ?: assets.randomFileFor(PetState.IDLE)
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                        )

                        Text(
                            stringResource(R.string.appearance_subject_label),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        ExposedDropdownMenuBox(
                            expanded = subjectExpanded,
                            onExpandedChange = { subjectExpanded = it }
                        ) {
                            OutlinedTextField(
                                value = subjects.getOrElse(subjectIndex) { "" },
                                onValueChange = {},
                                readOnly = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = subjectExpanded)
                                }
                            )
                            DropdownMenu(
                                expanded = subjectExpanded,
                                onDismissRequest = { subjectExpanded = false }
                            ) {
                                subjects.forEachIndexed { index, label ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            subjectIndex = index
                                            subjectExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                        if (subjects.getOrElse(subjectIndex) { "" }.contains("自定义")) {
                            OutlinedTextField(
                                value = customSubject,
                                onValueChange = { customSubject = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.appearance_custom_subject_hint)) },
                                singleLine = true
                            )
                        }

                        Text(
                            stringResource(R.string.appearance_style_label),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        ExposedDropdownMenuBox(
                            expanded = styleExpanded,
                            onExpandedChange = { styleExpanded = it }
                        ) {
                            OutlinedTextField(
                                value = styles.getOrElse(styleIndex) { "" },
                                onValueChange = {},
                                readOnly = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = styleExpanded)
                                }
                            )
                            DropdownMenu(
                                expanded = styleExpanded,
                                onDismissRequest = { styleExpanded = false }
                            ) {
                                styles.forEachIndexed { index, label ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            styleIndex = index
                                            styleExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                        if (styles.getOrElse(styleIndex) { "" }.contains("自定义")) {
                            OutlinedTextField(
                                value = customStyle,
                                onValueChange = { customStyle = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.appearance_custom_style_hint)) },
                                singleLine = true
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = !quality,
                                onClick = {
                                    quality = false
                                    petSettings.appearanceMode = PetAppearanceGenerator.GenerationMode.FAST
                                },
                                label = { Text(stringResource(R.string.appearance_mode_fast_short)) }
                            )
                            FilterChip(
                                selected = quality,
                                onClick = {
                                    quality = true
                                    petSettings.appearanceMode = PetAppearanceGenerator.GenerationMode.QUALITY
                                },
                                label = { Text(stringResource(R.string.appearance_mode_quality_short)) }
                            )
                        }

                        OutlinedTextField(
                            value = features,
                            onValueChange = { features = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.appearance_features_label)) },
                            placeholder = { Text(stringResource(R.string.appearance_features_hint)) }
                        )
                        OutlinedTextField(
                            value = freeIdea,
                            onValueChange = { freeIdea = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.appearance_free_label)) },
                            placeholder = { Text(stringResource(R.string.appearance_hint)) },
                            singleLine = false,
                            minLines = 2
                        )
                        if (quality) {
                            OutlinedTextField(
                                value = dashKey,
                                onValueChange = {
                                    dashKey = it
                                    petSettings.dashScopeApiKey = it
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.appearance_dashscope_hint)) },
                                singleLine = true
                            )
                        }

                        Text(
                            statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Button(
                            onClick = {
                                val upload = pendingUpload
                                val guide = buildGuide()
                                if (upload != null) {
                                    val creds = petSettings.appearanceCredentials()
                                    val mode = if (quality && creds.hasQwen()) {
                                        PetAppearanceGenerator.GenerationMode.QUALITY
                                    } else {
                                        PetAppearanceGenerator.GenerationMode.FAST
                                    }
                                    if (quality && !creds.hasQwen()) {
                                        Toast.makeText(
                                            this@AppearanceActivity,
                                            R.string.dashscope_key_missing,
                                            Toast.LENGTH_LONG
                                        ).show()
                                        return@Button
                                    }
                                    runGenerate {
                                        PetAppearanceGenerator.generateFromUpload(
                                            upload,
                                            "image/png",
                                            guide,
                                            creds,
                                            mode
                                        )
                                    }
                                } else {
                                    if (guide.composeIdeaOrEmpty().isBlank()) {
                                        Toast.makeText(
                                            this@AppearanceActivity,
                                            R.string.appearance_empty,
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        return@Button
                                    }
                                    val mode = if (quality) {
                                        PetAppearanceGenerator.GenerationMode.QUALITY
                                    } else {
                                        PetAppearanceGenerator.GenerationMode.FAST
                                    }
                                    val creds = petSettings.appearanceCredentials()
                                    if (mode == PetAppearanceGenerator.GenerationMode.QUALITY && !creds.hasQwen()) {
                                        Toast.makeText(
                                            this@AppearanceActivity,
                                            R.string.dashscope_key_missing,
                                            Toast.LENGTH_LONG
                                        ).show()
                                        return@Button
                                    }
                                    runGenerate {
                                        PetAppearanceGenerator.generateFromIdea(guide, creds, mode)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !generating
                        ) {
                            Text(stringResource(R.string.appearance_generate))
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { pickImage.launch("image/*") },
                                modifier = Modifier.weight(1f),
                                enabled = !generating
                            ) {
                                Text(stringResource(R.string.appearance_pick_image))
                            }
                            OutlinedButton(
                                onClick = {
                                    val bytes = pendingUpload
                                    if (bytes == null) {
                                        Toast.makeText(
                                            this@AppearanceActivity,
                                            R.string.appearance_need_upload,
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        return@OutlinedButton
                                    }
                                    runGenerate { PetAppearanceGenerator.applyUploadDirect(bytes) }
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !generating
                            ) {
                                Text(stringResource(R.string.appearance_apply_upload))
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                assets.clearCustomAppearance()
                                pendingUpload = null
                                Toast.makeText(
                                    this@AppearanceActivity,
                                    R.string.appearance_restored,
                                    Toast.LENGTH_SHORT
                                ).show()
                                refreshPetAfterAppearance()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !generating
                        ) {
                            Text(stringResource(R.string.appearance_restore))
                        }
                    }
                }
            }
        }
    }
}
