package com.druk.lmplayground

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics

// The three Penrose-triangle facets and the group transform, taken verbatim
// from res/drawable/penrose_triangle.xml (viewport 700×700). Drawn lightest →
// darkest, matching the original z-order so the 3D shading reads correctly.
private val PENROSE_PATHS = listOf(
    "M220.9,0.71L406.45,330.97L200.19,331.08L160.63,401.79L525.84,401.85L303.04,0.5L220.9,0.71z",
    "M220.89,0.68L0.5,401.79L40.91,474.52L223.74,143.19L325.77,331.07L406.58,331.07L220.89,0.68z",
    "M223.79,143.25L264.08,217.47L160.45,401.85L525.86,402.04L487.39,476.54L40.92,474.49L223.79,143.25z",
)

/**
 * The app's Penrose logo, recoloured to follow the current theme accent. The
 * three facets become a light / mid / shadow shade of [MaterialTheme]'s primary
 * colour (via [lerp] toward white/black), preserving the original 3D shading in
 * the chosen hue. Render it in a square [modifier] (e.g. `Modifier.size(104.dp)`).
 */
@Composable
fun ThemedAppLogo(
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val facets = listOf(
        lerp(primary, Color.White, 0.58f),  // light face
        primary,                            // mid face
        lerp(primary, Color.Black, 0.62f),  // shadow face
    )
    val paths = remember {
        PENROSE_PATHS.map { data ->
            PathParser().parsePathString(data).toPath().apply {
                fillType = PathFillType.EvenOdd
            }
        }
    }
    val semantics = if (contentDescription != null) {
        Modifier.semantics {
            this.contentDescription = contentDescription
            this.role = Role.Image
        }
    } else {
        Modifier
    }
    Canvas(modifier = modifier.then(semantics)) {
        // viewport (700) → canvas, then the group's translate + scale-about-pivot.
        val vp = size.minDimension / 700f
        scale(vp, vp, pivot = Offset.Zero) {
            translate(left = 86.82f, top = 111.48f) {
                scale(0.85f, 0.85f, pivot = Offset(263.18f, 238.52f)) {
                    paths.forEachIndexed { i, p -> drawPath(p, facets[i]) }
                }
            }
        }
    }
}
