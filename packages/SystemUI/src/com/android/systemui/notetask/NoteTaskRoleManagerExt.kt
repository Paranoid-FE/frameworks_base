/*
 * Copyright (C) 2023 The Android Open Source Project
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
 */

package com.android.systemui.notetask

import android.app.role.RoleManager
import android.app.role.RoleManager.ROLE_NOTES
import android.content.Context
import android.content.pm.ShortcutInfo
import android.graphics.drawable.Icon
import android.os.PersistableBundle
import android.os.UserHandle
import com.android.systemui.R
import com.android.systemui.notetask.shortcut.LaunchNoteTaskActivity

/** Extension functions for [RoleManager] used **internally** by note task. */
@InternalNoteTaskApi
internal object NoteTaskRoleManagerExt {

    /**
     * Gets package name of the default (first) app holding the [role]. If none, returns either an
     * empty string or null.
     */
    fun RoleManager.getDefaultRoleHolderAsUser(role: String, user: UserHandle): String? =
        getRoleHoldersAsUser(role, user).firstOrNull()

    /** Creates a [ShortcutInfo] for [ROLE_NOTES]. */
    fun RoleManager.createNoteShortcutInfoAsUser(
        context: Context,
        user: UserHandle,
    ): ShortcutInfo {
        val extras = PersistableBundle()
        getDefaultRoleHolderAsUser(ROLE_NOTES, user)?.let { packageName ->
            // Set custom app badge using the icon from ROLES_NOTES default app.
            extras.putString(NoteTaskController.EXTRA_SHORTCUT_BADGE_OVERRIDE_PACKAGE, packageName)
        }

        val icon = Icon.createWithResource(context, R.drawable.ic_note_task_shortcut_widget)

        return ShortcutInfo.Builder(context, NoteTaskController.SHORTCUT_ID)
            .setIntent(LaunchNoteTaskActivity.newIntent(context = context))
            .setShortLabel(context.getString(R.string.note_task_button_label))
            .setLongLived(true)
            .setIcon(icon)
            .setExtras(extras)
            .build()
    }
}
