/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.android.systemui.shared.quickaffordance.shared.model

object KeyguardPreviewConstants {
    const val MESSAGE_ID_HIDE_SMART_SPACE = 1111
    const val KEY_HIDE_SMART_SPACE = "hide_smart_space"
    const val MESSAGE_ID_COLOR_OVERRIDE = 1234
    const val KEY_COLOR_OVERRIDE = "color_override" // ColorInt Encoded as string
    const val MESSAGE_ID_SLOT_SELECTED = 1337
    const val KEY_SLOT_ID = "slot_id"
    const val KEY_INITIALLY_SELECTED_SLOT_ID = "initially_selected_slot_id"
    const val KEY_HIGHLIGHT_QUICK_AFFORDANCES = "highlight_quick_affordances"
}
