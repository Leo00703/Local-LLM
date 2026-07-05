package com.druk.lmplayground.settings

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.ui.graphics.vector.ImageVector
import com.druk.lmplayground.R

/**
 * User-facing UI metadata for a tool, keyed by [com.druk.lmplayground.tools.Tool.name].
 * The registry Tool.name / Tool.description are written for the model, so the UI
 * uses these plain-language strings plus an icon instead.
 */
data class ToolUiInfo(
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int,
    @StringRes val exampleRes: Int,
    val icon: ImageVector,
)

/**
 * Single source of truth for how tools are presented (title, description,
 * example, icon). Shared by Settings -> Tools and the model params sheet's Tools
 * tab, so a new tool only has to be added HERE once and both screens pick it up.
 * A tool with no entry falls back to its raw registry name with no icon.
 */
val TOOL_UI_CATALOG: Map<String, ToolUiInfo> = mapOf(
    "web_search" to ToolUiInfo(
        R.string.tool_web_search_title, R.string.tool_web_search_desc,
        R.string.tool_web_search_example, Icons.Outlined.Search,
    ),
    "web_fetch" to ToolUiInfo(
        R.string.tool_web_fetch_title, R.string.tool_web_fetch_desc,
        R.string.tool_web_fetch_example, Icons.Outlined.Public,
    ),
    "run_javascript" to ToolUiInfo(
        R.string.tool_run_javascript_title, R.string.tool_run_javascript_desc,
        R.string.tool_run_javascript_example, Icons.Outlined.Code,
    ),
    "calculator" to ToolUiInfo(
        R.string.tool_calculator_title, R.string.tool_calculator_desc,
        R.string.tool_calculator_example, Icons.Outlined.Calculate,
    ),
    "convert_units" to ToolUiInfo(
        R.string.tool_convert_units_title, R.string.tool_convert_units_desc,
        R.string.tool_convert_units_example, Icons.Outlined.SwapHoriz,
    ),
    "current_datetime" to ToolUiInfo(
        R.string.tool_datetime_title, R.string.tool_datetime_desc,
        R.string.tool_datetime_example, Icons.Outlined.Schedule,
    ),
    "device_info" to ToolUiInfo(
        R.string.tool_device_info_title, R.string.tool_device_info_desc,
        R.string.tool_device_info_example, Icons.Outlined.Smartphone,
    ),
    "wikipedia" to ToolUiInfo(
        R.string.tool_wikipedia_title, R.string.tool_wikipedia_desc,
        R.string.tool_wikipedia_example, Icons.Outlined.MenuBook,
    ),
    "location" to ToolUiInfo(
        R.string.tool_location_title, R.string.tool_location_desc,
        R.string.tool_location_example, Icons.Outlined.LocationOn,
    ),
    "memory" to ToolUiInfo(
        R.string.tool_memory_title, R.string.tool_memory_desc,
        R.string.tool_memory_example, Icons.Outlined.Bookmark,
    ),
)
