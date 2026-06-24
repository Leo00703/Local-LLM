@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.druk.lmplayground.screenshots

import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SignalCellular4Bar
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.ide.common.rendering.api.SessionParams
import com.android.resources.ScreenOrientation
import com.druk.lmplayground.R
import com.druk.lmplayground.conversation.ConversationBar
import com.druk.lmplayground.conversation.Message
import com.druk.lmplayground.conversation.PermanentSessionList
import com.druk.lmplayground.conversation.UserInput
import com.druk.lmplayground.data.ChatSessionEntity
import com.druk.lmplayground.data.SystemPromptEntity
import com.druk.lmplayground.models.Model
import com.druk.lmplayground.models.ModelInfo
import com.druk.lmplayground.models.ModelWithStatus
import com.druk.lmplayground.settings.SystemPromptsContent
import com.druk.lmplayground.settings.ToolsContent
import com.druk.lmplayground.storage.ModelsContent
import com.druk.lmplayground.storage.StorageInfo
import com.druk.lmplayground.theme.PlaygroundTheme
import com.druk.lmplayground.tools.Tool
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.time.LocalDate

/**
 * Landscape tablet marketing screenshots, English only.
 *
 * Mirrors the visual treatment of [StoreScreenshots] but targets the
 * Play Store `sevenInchScreenshots/` and `tenInchScreenshots/` slots
 * instead of `phoneScreenshots/`. Each non-hero scene wraps content in
 * a marketing header (icon + title + subtitle) plus a tablet device
 * frame positioned to extend past the canvas bottom — so the device's
 * lower portion gets naturally cropped, exactly like the phone shots.
 *
 * Scene 2 wraps the chat in [PermanentNavigationDrawer] so the
 * tablet-only permanent sidebar shows — the killer adaptive feature
 * these tablet shots exist to advertise.
 *
 * Filename pattern is `..._TabletStoreScreenshots_sceneN_xxx[VARIANT].png`
 * which the `organizeScreenshotsForPlayStore` Gradle task routes into
 * `fastlane/metadata/android/en-US/images/{sevenInch,tenInch}Screenshots/`.
 */
@RunWith(Parameterized::class)
class TabletStoreScreenshots(
    private val variant: TabletVariant,
    private val locale: String,
    private val localeName: String,
) {

    companion object {
        // Same 28 locales as the phone test class. Localizing tablet
        // shots means Play Store no longer falls back to English-only
        // tablet imagery for users in other languages.
        private val locales = listOf(
            "en" to "English",
            "es" to "Spanish",
            "pt" to "Portuguese",
            "fr" to "French",
            "de" to "German",
            "it" to "Italian",
            "pl" to "Polish",
            "uk" to "Ukrainian",
            "ro" to "Romanian",
            "tr" to "Turkish",
            "ar" to "Arabic",
            "zh" to "Chinese",
            "ja" to "Japanese",
            "ko" to "Korean",
            "in" to "Indonesian",
            "hi" to "Hindi",
            "vi" to "Vietnamese",
            "th" to "Thai",
            "nl" to "Dutch",
            "iw" to "Hebrew",
            "cs" to "Czech",
            "sv" to "Swedish",
            "bn" to "Bengali",
            "ms" to "Malay",
            "fil" to "Filipino",
            "nb" to "Norwegian",
            "da" to "Danish",
            "fi" to "Finnish",
        )

        @JvmStatic
        // Filename suffix becomes `[SevenInch_French]`, which the
        // `organizeScreenshotsForPlayStore` regex splits on `_` to
        // route each PNG to the right per-locale tablet folder.
        @Parameterized.Parameters(name = "{0}_{2}")
        fun variants(): List<Array<Any>> {
            val variants = listOf(TabletVariant.SevenInch, TabletVariant.TenInch)
            return variants.flatMap { variant ->
                locales.map { (loc, name) -> arrayOf<Any>(variant, loc, name) }
            }
        }
    }

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = variant.deviceConfig.copy(locale = locale),
        renderingMode = SessionParams.RenderingMode.NORMAL,
        showSystemUi = false,
        useDeviceResolution = true
    )

    // Mirror StoreScreenshots: Paparazzi's locale config doesn't flip
    // LayoutDirection for RTL languages the way a real device does, so
    // we provide the right direction explicitly for Arabic / Hebrew.
    // Combined PlaygroundTheme + LayoutDirection wrap. Mirrors what
    // StoreScreenshots does with a separate LocaleLayout helper, but
    // keeping it inside the same composable as the theme means the
    // existing scene bodies can just rename `PlaygroundTheme(...)` →
    // `LocaleTheme` with no brace-count change.
    //
    // Paparazzi's locale config doesn't flip LayoutDirection for RTL
    // languages the way a real device does, so we set it explicitly
    // here for Arabic / Hebrew.
    @Composable
    private fun LocaleTheme(content: @Composable () -> Unit) {
        val direction = if (locale == "ar" || locale == "iw") {
            LayoutDirection.Rtl
        } else {
            LayoutDirection.Ltr
        }
        CompositionLocalProvider(LocalLayoutDirection provides direction) {
            PlaygroundTheme(isDarkTheme = true) {
                content()
            }
        }
    }

    // ── Shared test data ────────────────────────────────────────────
    @Composable
    private fun gemma4Model(): ModelInfo {
        // Description in chat/params shots stays minimal (just size) —
        // ConversationBar renders "<name>  ·  <description>", so this
        // gives the production-style "Gemma 4 E2B  ·  3.11Gb" line
        // without the noisy provider/category prefix.
        return ModelInfo(
            name = "Gemma 4 E2B",
            filename = "gemma-4-E2B-it-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/model.gguf"),
            releaseDate = LocalDate.parse("2026-03-25"),
            description = "3.11Gb",
            logoRes = R.drawable.logo_google
        )
    }

    private fun sampleSessions(): List<ChatSessionEntity> {
        val now = System.currentTimeMillis()
        return listOf(
            ChatSessionEntity(
                id = "1",
                title = "Explain machine learning",
                modelFilename = "gemma-4-E2B-it-Q4_K_M.gguf",
                modelName = "Gemma 4 E2B",
                createdAt = now - 60_000,
                updatedAt = now - 30_000,
                pinned = true,
            ),
            ChatSessionEntity(
                id = "2",
                title = "Trip ideas for Iceland",
                modelFilename = "gemma-4-E2B-it-Q4_K_M.gguf",
                modelName = "Gemma 4 E2B",
                createdAt = now - 3_600_000,
                updatedAt = now - 1_800_000,
            ),
            ChatSessionEntity(
                id = "3",
                title = "Refactor Kotlin coroutine",
                modelFilename = "gemma-4-E2B-it-Q4_K_M.gguf",
                modelName = "Gemma 4 E2B",
                createdAt = now - 86_400_000,
                updatedAt = now - 86_400_000,
            ),
            ChatSessionEntity(
                id = "4",
                title = "Translate to Japanese",
                modelFilename = "gemma-4-E2B-it-Q4_K_M.gguf",
                modelName = "Gemma 4 E2B",
                createdAt = now - 2 * 86_400_000,
                updatedAt = now - 2 * 86_400_000,
            ),
            ChatSessionEntity(
                id = "5",
                title = "Recipe: pasta carbonara",
                modelFilename = "gemma-4-E2B-it-Q4_K_M.gguf",
                modelName = "Gemma 4 E2B",
                createdAt = now - 3 * 86_400_000,
                updatedAt = now - 3 * 86_400_000,
            ),
        )
    }

    // Inline slider mirrors StoreScreenshots' helper (production
    // ParamSlider is private to its package).
    @Composable
    private fun InlineParamSlider(
        label: String,
        value: Float,
        valueRange: ClosedFloatingPointRange<Float>,
        valueDisplay: String,
        subtitle: String? = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = label, style = MaterialTheme.typography.bodyMedium)
                    if (subtitle != null) {
                        Text(
                            text = " ($subtitle)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    text = valueDisplay,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = value,
                onValueChange = { },
                valueRange = valueRange,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    // ── Fake generation-params bottom sheet ─────────────────────────
    // Static visual stand-in for production's [GenerationParamsSheet]
    // (a ModalBottomSheet). We can't render the real sheet inside
    // Paparazzi because it relies on sheet state being expanded in a
    // coroutine; instead we mirror its content layout in a Surface
    // pinned to the bottom of the display, with rounded top corners
    // and a drag handle. Order and labels match the prod sheet so the
    // marketing shot reads as the real UI.
    @Composable
    private fun FakeGenerationParamsSheet() {
        // Mirrors Material 3 ModalBottomSheet on tablet:
        // ‑ Max width 640dp, centered horizontally (per
        //   BottomSheetDefaults.SheetMaxWidth).
        // ‑ Full height — same as a fully-expanded sheet with
        //   skipPartiallyExpanded=true reaching the top inset.
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(max = 640.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
            ) {
                Spacer(modifier = Modifier.height(12.dp))
                // Drag handle pill — matches Material 3's default
                // BottomSheetDefaults.DragHandle (32dp × 4dp, outline).
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(width = 32.dp, height = 4.dp)
                        .background(
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(2.dp),
                        )
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Header: title + Reset (mirrors prod row layout)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.generation_parameters),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.reset),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Current system prompt label + Clear
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.system_prompt_current),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.clear),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.screenshot_system_prompt_sample),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        minLines = 3,
                        maxLines = 3,
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))

                InlineParamSlider(
                    label = stringResource(R.string.context_size),
                    value = 4096f,
                    valueRange = 512f..8192f,
                    valueDisplay = "4096",
                )
                InlineParamSlider(
                    label = stringResource(R.string.thinking_budget),
                    value = 1024f,
                    valueRange = 64f..4096f,
                    valueDisplay = stringResource(R.string.tokens_value, 1024),
                )
                InlineParamSlider(
                    label = stringResource(R.string.temperature),
                    value = 0.7f,
                    valueRange = 0f..2f,
                    valueDisplay = "0.70",
                )
                InlineParamSlider(
                    label = stringResource(R.string.top_p),
                    value = 0.9f,
                    valueRange = 0f..1f,
                    valueDisplay = "0.90",
                )
                InlineParamSlider(
                    label = stringResource(R.string.repetition_penalty),
                    value = 1.1f,
                    valueRange = 1f..2f,
                    valueDisplay = "1.10",
                )

                Spacer(modifier = Modifier.height(8.dp))
                // Advanced section header (collapsed — matches prod
                // initial state where Advanced isn't expanded).
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.advanced),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = null,
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
            }
        }
    }

    // ── Fake status bar ─────────────────────────────────────────────
    // Mirrors the phone version's FakeStatusBar (10:10 + wifi/signal/
    // battery) so all marketing shots have consistent device chrome.
    // Sits inside the rounded display clip, so its top edges follow
    // the same corner radius as the rest of the content.
    @Composable
    private fun FakeStatusBar() {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .height(24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "10:10",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Wifi, null, Modifier.size(14.dp), tint = Color.White)
                Icon(Icons.Outlined.SignalCellular4Bar, null, Modifier.size(14.dp), tint = Color.White)
                Icon(Icons.Outlined.BatteryFull, null, Modifier.size(14.dp), tint = Color.White)
            }
        }
    }

    // ── System notification mock ────────────────────────────────────
    // Mirrors the real InferenceNotification (app icon + label,
    // "Response ready" title, "<model> · <tokens>" line, Copy/Share
    // actions) so the background-generation shot reads like the OS
    // heads-up card. Caller constrains the width so it floats like a
    // real tablet notification rather than spanning the display.
    @Composable
    private fun NotificationCard(
        appName: String,
        title: String,
        body: String,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.drawable.penrose_triangle),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = appName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Row(modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { }) {
                        Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.copy))
                    }
                    TextButton(onClick = { }) {
                        Icon(Icons.Outlined.Share, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.share))
                    }
                }
            }
        }
    }

    // ── Frame composables ───────────────────────────────────────────

    /**
     * Marketing wrapper: dark canvas, header at the top, tablet skin
     * positioned by absolute offset below. The skin's intrinsic
     * (aspect-fit) height typically overflows the canvas bottom; the
     * outer [clipToBounds] crops it. The header is at fixed offsets
     * so its layout never competes with the frame's required-size
     * constraints (which we ran into when both lived in the same
     * Column).
     *
     * Frame layout math:
     *   frameW = canvas_width
     *   frameH = frameW * (frame_h / frame_w)   ← intrinsic, may
     *                                             overflow canvas
     *   display_dx = frameW * (display_x / frame_w)
     *   display_dy = frameH * (display_y / frame_h)
     *   etc. — same fractional math the phone DeviceFrame uses.
     */
    @Composable
    private fun TabletStoreScreenshotFrame(
        icon: ImageVector,
        title: String,
        subtitle: String,
        content: @Composable () -> Unit
    ) {
        val spec = variant.frameSpec
        // 260dp clears the marketing header (icon 44 + spacers + title
        // ~48 + subtitle ~24 ≈ 176dp at top:36dp). Pixel C's frame
        // height is taller relative to canvas than Pixel Tablet's, so
        // its bezel sits higher — need extra breathing room here than
        // the 220dp that worked for Pixel Tablet.
        val frameTop = 260.dp
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0A0A0A))
                .clipToBounds(),
        ) {
            val canvasWidth = maxWidth
            val frameW = canvasWidth
            val frameH = frameW * (spec.frameHeight / spec.frameWidth)

            // Tablet frame — positioned via offset so it doesn't
            // compete with the header's layout pass.
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = frameTop)
                    .requiredSize(frameW, frameH)
            ) {
                // Skin first (under app content). Pixel C's skin has
                // an OPAQUE black display rect — the app content
                // drawn on top covers that opaque region. (Pixel
                // Tablet's display rect was alpha=0 so either order
                // worked; this z-order is the one that works for
                // every skin.)
                Image(
                    painter = painterResource(spec.frameRes),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds
                )
                // App content positioned at the skin's display rect
                // — fills the now-covered display window. Clipped with
                // rounded corners so the content visually matches the
                // tablet frame's rounded display corners.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            start = frameW * (spec.displayX / spec.frameWidth),
                            top = frameH * (spec.displayY / spec.frameHeight),
                            end = frameW * ((spec.frameWidth - spec.displayX - spec.displayW) / spec.frameWidth),
                            bottom = frameH * ((spec.frameHeight - spec.displayY - spec.displayH) / spec.frameHeight),
                        )
                        .clip(RoundedCornerShape(spec.displayCornerRadius))
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        FakeStatusBar()
                        content()
                    }
                }
            }

            // Marketing header at top, drawn last so it sits above
            // anything beneath.
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = title,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Serif,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    lineHeight = 48.sp,
                    letterSpacing = (-0.5).sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = subtitle,
                    fontSize = 20.sp,
                    color = Color(0xFFAAAAAA),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp)
                )
            }
        }
    }

    // ── Scenes ──────────────────────────────────────────────────────

    // Scene 0: Hero — full-canvas marketing slide, no device frame.
    // Layout balanced for landscape (smaller spacers than phone hero).
    @Test
    fun scene0_hero() {
        paparazzi.snapshot {
            LocaleTheme {
                val titleText = buildAnnotatedString {
                    append(stringResource(R.string.screenshot_hero_title_prefix))
                    append("\n")
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(stringResource(R.string.screenshot_hero_title_emphasis))
                    }
                    append(" ")
                    // Tablet shots use the `_tablet` suffix variant so
                    // marketing copy reads naturally on tablet listings.
                    append(stringResource(R.string.screenshot_hero_title_suffix_tablet))
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0A0A0A))
                        .padding(horizontal = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.weight(0.8f))
                    Image(
                        painter = painterResource(R.drawable.penrose_triangle),
                        contentDescription = null,
                        modifier = Modifier.size(120.dp)
                    )
                    Spacer(modifier = Modifier.height(40.dp))
                    Text(
                        text = titleText,
                        fontSize = 64.sp,
                        fontWeight = FontWeight.Normal,
                        fontFamily = FontFamily.Serif,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        lineHeight = 74.sp,
                        letterSpacing = (-0.5).sp
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = stringResource(R.string.screenshot_hero_subtitle),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFBBBBBB),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = stringResource(R.string.screenshot_hero_providers_label),
                        fontSize = 16.sp,
                        color = Color(0xFF888888),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    val logos = listOf(
                        R.drawable.logo_google,
                        R.drawable.logo_meta,
                        R.drawable.logo_qwen,
                        R.drawable.logo_microsoft,
                        R.drawable.logo_mistral,
                        R.drawable.logo_nvidia,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy((-20).dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        logos.forEach { logo ->
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF0A0A0A))
                            ) {
                                Image(
                                    painter = painterResource(logo),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(6.dp)
                                        .clip(CircleShape)
                                        .background(Color.White)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.weight(0.8f))
                }
            }
        }
    }

    // Scene 1: Choose model — model picker card inside the tablet
    // frame. Reuses the same screenshot strings as the phone shot.
    @Test
    fun scene1_chooseModel() {
        paparazzi.snapshot {
            LocaleTheme {
                val efficient = stringResource(R.string.model_category_efficient)
                val lightweight = stringResource(R.string.model_category_lightweight)
                val general = stringResource(R.string.model_category_general)
                val compactReasoning = stringResource(R.string.model_category_compact_reasoning)
                val enterprise = stringResource(R.string.model_category_enterprise)

                val models = listOf(
                    ModelWithStatus(ModelInfo("Gemma 4 E2B", "g4e2b.gguf", Uri.parse("https://x.com/m"), LocalDate.parse("2026-03-25"), "Google · $efficient · 3.11Gb", R.drawable.logo_google), true),
                    ModelWithStatus(ModelInfo("Llama 3.2 3B", "llama32.gguf", Uri.parse("https://x.com/m"), LocalDate.parse("2025-09-25"), "Meta · $general · 1.88Gb", R.drawable.logo_meta), true),
                    ModelWithStatus(ModelInfo("Qwen 3.5 2B", "q35-2b.gguf", Uri.parse("https://x.com/m"), LocalDate.parse("2026-02-27"), "Alibaba · $general · 1.07Gb", R.drawable.logo_qwen), true),
                    ModelWithStatus(ModelInfo("Phi-4 mini", "phi4.gguf", Uri.parse("https://x.com/m"), LocalDate.parse("2025-01-15"), "Microsoft · $lightweight · 2.49Gb", R.drawable.logo_microsoft), true),
                    ModelWithStatus(ModelInfo("Ministral 3B", "min3b.gguf", Uri.parse("https://x.com/m"), LocalDate.parse("2025-10-16"), "Mistral · $enterprise · 1.85Gb", R.drawable.logo_mistral), true),
                    ModelWithStatus(ModelInfo("Nemotron 3 Nano", "nem4b.gguf", Uri.parse("https://x.com/m"), LocalDate.parse("2025-12-15"), "NVIDIA · $compactReasoning · 2.84Gb", R.drawable.logo_nvidia), true),
                )

                TabletStoreScreenshotFrame(
                    icon = Icons.Outlined.AutoAwesome,
                    title = stringResource(R.string.screenshot_choose_model),
                    subtitle = stringResource(R.string.screenshot_choose_model_sub),
                ) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            ConversationBar(
                                modelInfo = null,
                                modelStatus = null,
                                compact = true,
                                showNavIcon = false,
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(24.dp),
                                contentAlignment = Alignment.TopCenter
                            ) {
                                Card(
                                    modifier = Modifier
                                        .widthIn(max = 640.dp)
                                        .fillMaxWidth(),
                                    shape = RoundedCornerShape(20.dp)
                                ) {
                                    Column {
                                        models.filter { it.isDownloaded }.forEach { m ->
                                            Model(model = m.model) { }
                                        }
                                        HorizontalDivider(
                                            modifier = Modifier.padding(horizontal = 8.dp)
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        TextButton(
                                            onClick = { },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.AutoAwesome,
                                                contentDescription = null,
                                                modifier = Modifier.padding(end = 8.dp)
                                            )
                                            Text(stringResource(R.string.browse_more_models))
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Scene 2: Chat with permanent sidebar — the tablet-defining shot.
    @Test
    fun scene2_chat() {
        paparazzi.snapshot {
            LocaleTheme {
                val question = stringResource(R.string.screenshot_chat_question)
                val response = stringResource(R.string.screenshot_chat_response)
                val gemma4 = gemma4Model()
                val sessions = sampleSessions()

                TabletStoreScreenshotFrame(
                    icon = Icons.Outlined.WifiOff,
                    title = stringResource(R.string.screenshot_chat),
                    subtitle = stringResource(R.string.screenshot_chat_sub),
                ) {
                    PermanentNavigationDrawer(
                        drawerContent = {
                            PermanentSessionList(
                                sessions = sessions,
                                folders = emptyList(),
                                currentSessionId = sessions[0].id,
                                onSessionSelected = {},
                                onDeleteSession = {},
                                onRenameSession = { _, _ -> },
                                onPinSession = { _, _ -> },
                                onCreateFolder = {},
                                onRenameFolder = { _, _ -> },
                                onDeleteFolder = {},
                                onMoveSessionToFolder = { _, _ -> },
                                onSettingsClicked = {},
                            )
                        },
                    ) {
                        Surface(modifier = Modifier.fillMaxSize()) {
                            Column {
                                ConversationBar(
                                    modelInfo = gemma4,
                                    modelStatus = null,
                                    compact = true,
                                    showNavIcon = false,
                                )
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 8.dp)
                                ) {
                                    com.druk.lmplayground.conversation.Message(
                                        msg = Message("User", question),
                                        isUserMe = true,
                                        showActions = false
                                    )
                                    com.druk.lmplayground.conversation.Message(
                                        msg = Message(
                                            author = "Assistant",
                                            content = "<think>Let me explain machine learning clearly and concisely.</think>\n$response",
                                            thinkingDurationSeconds = 3,
                                            thinkingTokens = 42,
                                            responseTokens = 156,
                                            responseDurationSeconds = 4.2f
                                        ),
                                        isUserMe = false,
                                        showActions = true
                                    )
                                }
                                UserInput(
                                    integrateWithSurface = true,
                                    onMessageSent = {},
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Scene 3: Generation parameters as a bottom sheet, fully
    // expanded. Same content as the prior plain-surface version, just
    // wrapped in a sheet-styled container (rounded top corners +
    // drag handle) so it reads as the production
    // [GenerationParamsSheet]. No chat backdrop / scrim — sheet takes
    // the entire display so the params get full marketing focus.
    @Test
    fun scene3_generationParams() {
        paparazzi.snapshot {
            LocaleTheme {
                TabletStoreScreenshotFrame(
                    icon = Icons.Outlined.Tune,
                    title = stringResource(R.string.screenshot_fine_tune),
                    subtitle = stringResource(R.string.screenshot_fine_tune_sub),
                ) {
                    FakeGenerationParamsSheet()
                }
            }
        }
    }

    // Scene 4: Models download
    @Test
    fun scene4_modelsDownload() {
        paparazzi.snapshot {
            LocaleTheme {
                val efficient = stringResource(R.string.model_category_efficient)
                val lightweight = stringResource(R.string.model_category_lightweight)
                val general = stringResource(R.string.model_category_general)
                val ultraLight = stringResource(R.string.model_category_ultra_lightweight)
                val thinking = stringResource(R.string.model_category_thinking)
                val reasoning = stringResource(R.string.model_category_reasoning)

                val models = listOf(
                    ModelWithStatus(ModelInfo("LFM2.5 350M", "lfm350.gguf", Uri.parse("https://x.com/m"), LocalDate.parse("2026-03-25"), "Liquid AI · $ultraLight · 267Mb", R.drawable.logo_liquid), true),
                    ModelWithStatus(ModelInfo("Gemma 4 E2B", "g4e2b.gguf", Uri.parse("https://x.com/m"), LocalDate.parse("2026-03-25"), "Google · $efficient · 3.11Gb", R.drawable.logo_google), true),
                    ModelWithStatus(ModelInfo("Qwen 3.5 0.8B", "q35-08.gguf", Uri.parse("https://x.com/m"), LocalDate.parse("2026-02-27"), "Alibaba · $lightweight · 466Mb", R.drawable.logo_qwen), true),
                    ModelWithStatus(ModelInfo("Qwen 3.5 2B", "q35-2b.gguf", Uri.parse("https://x.com/m"), LocalDate.parse("2026-02-27"), "Alibaba · $general · 1.07Gb", R.drawable.logo_qwen), false),
                    ModelWithStatus(ModelInfo("Nemotron 3 Nano", "nem4b.gguf", Uri.parse("https://x.com/m"), LocalDate.parse("2025-12-15"), "NVIDIA · $reasoning · 2.84Gb", R.drawable.logo_nvidia), false),
                    ModelWithStatus(ModelInfo("LFM2.5 1.2B", "lfm12.gguf", Uri.parse("https://x.com/m"), LocalDate.parse("2025-01-09"), "Liquid AI · $thinking · 731Mb", R.drawable.logo_liquid), false),
                )

                TabletStoreScreenshotFrame(
                    icon = Icons.Outlined.CloudDownload,
                    title = stringResource(R.string.screenshot_download_models),
                    subtitle = stringResource(R.string.screenshot_download_models_sub),
                ) {
                    // Marketing variant of the Models screen:
                    // ‑ Real TopAppBar (back arrow + "Models") so the
                    //   screenshot reads as the live screen, not a
                    //   detail pane.
                    // ‑ maxContentWidth = Dp.Infinity so the grid fills
                    //   the full display width. On 10" landscape
                    //   (~1140dp) the 340dp adaptive cells naturally
                    //   produce 3 columns; on 7" landscape (~880dp)
                    //   we still get a nice 2-column spread.
                    val snackbarHostState = remember { SnackbarHostState() }
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        topBar = {
                            TopAppBar(
                                title = { Text(stringResource(R.string.models)) },
                                navigationIcon = {
                                    IconButton(onClick = { }) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = stringResource(R.string.back),
                                        )
                                    }
                                },
                            )
                        },
                    ) { padding ->
                        ModelsContent(
                            storageInfo = StorageInfo(
                                path = "/storage/emulated/0/Models",
                                usedBytes = 3_800_000_000L,
                                totalBytes = 64_000_000_000L,
                                availableBytes = 42_000_000_000L,
                                isCustomFolder = false
                            ),
                            allModels = models,
                            downloadingModels = emptyMap(),
                            snackbarMessage = null,
                            pendingMigration = null,
                            migrationProgress = null,
                            deviceLanguage = "en",
                            snackbarHostState = snackbarHostState,
                            onChangeFolderClick = { },
                            onDeleteModel = { },
                            onDownloadModel = { },
                            onCancelDownload = { },
                            onSnackbarDismiss = { },
                            onConfirmMigration = { },
                            onSkipMigration = { },
                            onCancelMigration = { },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding),
                            maxContentWidth = Dp.Infinity,
                        )
                    }
                }
            }
        }
    }

    // Scene 5: Reusable system prompts — adaptive multi-column grid
    // (the tablet-only layout the phone shot can't show).
    @Test
    fun scene5_systemPrompts() {
        paparazzi.snapshot {
            LocaleTheme {
                val prompts = listOf(
                    SystemPromptEntity(id = "1", text = stringResource(R.string.screenshot_system_prompt_sample), createdAt = 0L),
                    SystemPromptEntity(id = "2", text = stringResource(R.string.screenshot_prompt_sample_code), createdAt = 0L),
                    SystemPromptEntity(id = "3", text = stringResource(R.string.screenshot_prompt_sample_translator), createdAt = 0L),
                    SystemPromptEntity(id = "4", text = stringResource(R.string.screenshot_prompt_sample_brainstorm), createdAt = 0L),
                )
                TabletStoreScreenshotFrame(
                    icon = Icons.Outlined.Description,
                    title = stringResource(R.string.screenshot_prompts),
                    subtitle = stringResource(R.string.screenshot_prompts_sub),
                ) {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        topBar = {
                            TopAppBar(
                                title = { Text(stringResource(R.string.system_prompts)) },
                                navigationIcon = {
                                    IconButton(onClick = { }) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = stringResource(R.string.back),
                                        )
                                    }
                                },
                            )
                        },
                    ) { padding ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            SystemPromptsContent(
                                prompts = prompts,
                                onSelect = { },
                                modifier = Modifier
                                    .widthIn(max = 760.dp)
                                    .fillMaxSize(),
                                onNewPrompt = { },
                            )
                        }
                    }
                }
            }
        }
    }

    // Scene 6: Tools — per-tool toggles in Settings, centered to match
    // the tablet content width used by the other scenes.
    @Test
    fun scene6_tools() {
        paparazzi.snapshot {
            LocaleTheme {
                val tools = listOf(
                    StubTool("web_search"),
                    StubTool("web_fetch"),
                    StubTool("run_javascript"),
                )
                val enabled = mapOf(
                    "web_search" to true,
                    "web_fetch" to true,
                    "run_javascript" to true,
                )
                TabletStoreScreenshotFrame(
                    icon = Icons.Outlined.Build,
                    title = stringResource(R.string.screenshot_tools),
                    subtitle = stringResource(R.string.screenshot_tools_sub),
                ) {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        topBar = {
                            TopAppBar(
                                title = { Text(stringResource(R.string.tools)) },
                                navigationIcon = {
                                    IconButton(onClick = { }) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = stringResource(R.string.back),
                                        )
                                    }
                                },
                            )
                        },
                    ) { padding ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            ToolsContent(
                                tools = tools,
                                enabledStates = enabled,
                                onToolEnabledChanged = { _, _ -> },
                                modifier = Modifier
                                    .widthIn(max = 720.dp)
                                    .fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }

    // Scene 7: Background generation notification over the dimmed
    // sidebar chat — the reply kept running after the user left.
    @Test
    fun scene7_notification() {
        paparazzi.snapshot {
            LocaleTheme {
                val gemma4 = gemma4Model()
                val question = stringResource(R.string.screenshot_chat_question)
                val response = stringResource(R.string.screenshot_chat_response)
                val sessions = sampleSessions()
                val tokens = pluralStringResource(R.plurals.inference_notification_tokens, 342, 342)

                TabletStoreScreenshotFrame(
                    icon = Icons.Outlined.Notifications,
                    title = stringResource(R.string.screenshot_notification),
                    subtitle = stringResource(R.string.screenshot_notification_sub),
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        PermanentNavigationDrawer(
                            drawerContent = {
                                PermanentSessionList(
                                    sessions = sessions,
                                    folders = emptyList(),
                                    currentSessionId = sessions[0].id,
                                    onSessionSelected = {},
                                    onDeleteSession = {},
                                    onRenameSession = { _, _ -> },
                                    onPinSession = { _, _ -> },
                                    onCreateFolder = {},
                                    onRenameFolder = { _, _ -> },
                                    onDeleteFolder = {},
                                    onMoveSessionToFolder = { _, _ -> },
                                    onSettingsClicked = {},
                                )
                            },
                        ) {
                            Surface(modifier = Modifier.fillMaxSize()) {
                                Column {
                                    ConversationBar(
                                        modelInfo = gemma4,
                                        modelStatus = null,
                                        compact = true,
                                        showNavIcon = false,
                                    )
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(horizontal = 8.dp)
                                    ) {
                                        com.druk.lmplayground.conversation.Message(
                                            msg = Message("User", question),
                                            isUserMe = true,
                                            showActions = false
                                        )
                                        com.druk.lmplayground.conversation.Message(
                                            msg = Message(author = "Assistant", content = response),
                                            isUserMe = false,
                                            showActions = false
                                        )
                                    }
                                    UserInput(
                                        integrateWithSurface = true,
                                        onMessageSent = {},
                                    )
                                }
                            }
                        }
                        // Scrim — the app is in the background.
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.6f))
                        )
                        // Heads-up notification floating near the top.
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = 16.dp),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            Box(
                                modifier = Modifier
                                    .widthIn(max = 460.dp)
                                    .fillMaxWidth()
                            ) {
                                NotificationCard(
                                    appName = stringResource(R.string.inference_notification_title),
                                    title = stringResource(R.string.inference_notification_ready_title),
                                    body = stringResource(
                                        R.string.inference_notification_tokens_line,
                                        "Gemma 4 E2B",
                                        tokens
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// Marketing stand-in for a real Tool — ToolsContent keys its UI copy
// off Tool.name, so only the three real names matter here.
private class StubTool(override val name: String) : Tool {
    override val description: String = ""
    override val parametersSchema: String = ""
    override fun execute(arguments: String): String = ""
}

/**
 * Pixels and corner radius for a tablet skin's display rectangle
 * within its frame WebP — these are needed to position the app
 * content correctly behind the skin's bezel.
 */
data class TabletFrameSpec(
    @DrawableRes val frameRes: Int,
    val frameWidth: Float,
    val frameHeight: Float,
    val displayX: Float,
    val displayY: Float,
    val displayW: Float,
    val displayH: Float,
    val displayCornerRadius: androidx.compose.ui.unit.Dp,
)

/**
 * Landscape tablet device configs + matching skin frame specs.
 *
 * Paparazzi's [DeviceConfig.orientation] picks max(screenWidth,
 * screenHeight) as the visual width in LANDSCAPE — so we just flip
 * NEXUS_7's orientation (it ships PORTRAIT) and leave PIXEL_TABLET
 * untouched (already LANDSCAPE) instead of swapping dimensions.
 *
 * Frame specs are extracted from the Android Studio SDK skin files
 * under `$ANDROID_HOME/skins/<device>/layout` — the `parts.device.display`
 * size combined with the `layouts.landscape.part2` offset.
 *
 * Each variant's [displayName] is what Paparazzi appends to the
 * snapshot filename as `[SevenInch]` / `[TenInch]`, which the
 * `organizeScreenshotsForPlayStore` Gradle task pattern-matches to
 * decide which fastlane subdirectory the PNG lands in.
 */
enum class TabletVariant(
    val displayName: String,
    val deviceConfig: DeviceConfig,
    val frameSpec: TabletFrameSpec,
) {
    // 7" slot: 1920×1200 landscape canvas (NEXUS_7 flipped) inside a
    // Pixel C skin (3096×2215 landscape, display 2560×1800 at 268,207).
    SevenInch(
        "SevenInch",
        DeviceConfig.NEXUS_7.copy(
            orientation = ScreenOrientation.LANDSCAPE,
            locale = "en",
        ),
        TabletFrameSpec(
            frameRes = R.drawable.device_frame_pixel_c,
            frameWidth = 3096f,
            frameHeight = 2215f,
            // The Pixel C skin's actual display rect is at (268, 207)
            // with thick silver bezels around it. Marketing look:
            //  - 140f sides → ~115 canvas px of clearly visible silver
            //    bezel on left/right.
            //  - 40f top → tight enough to cover the camera dot in
            //    the Pixel C skin's top bezel.
            //  Asymmetric, but uniform-bezel + camera-covered are
            //  mutually exclusive on this skin.
            displayX = 120f,
            displayY = 36f,
            displayW = 2862f,
            displayH = 2135f,
            displayCornerRadius = 12.dp,
        ),
    ),
    // 10" slot: PIXEL_TABLET ships LANDSCAPE with 2560×1600 visual
    // canvas. Wrapped in the same Pixel C skin — its silver/aluminum
    // aesthetic complements the marketing dark theme.
    TenInch(
        "TenInch",
        DeviceConfig.PIXEL_TABLET.copy(
            locale = "en",
        ),
        TabletFrameSpec(
            frameRes = R.drawable.device_frame_pixel_c,
            frameWidth = 3096f,
            frameHeight = 2215f,
            // The Pixel C skin's actual display rect is at (268, 207)
            // with thick silver bezels around it. Marketing look:
            //  - 140f sides → ~115 canvas px of clearly visible silver
            //    bezel on left/right.
            //  - 40f top → tight enough to cover the camera dot in
            //    the Pixel C skin's top bezel.
            //  Asymmetric, but uniform-bezel + camera-covered are
            //  mutually exclusive on this skin.
            displayX = 140f,
            displayY = 40f,
            displayW = 2816f,
            displayH = 2135f,
            displayCornerRadius = 28.dp,
        ),
    );

    override fun toString(): String = displayName
}
