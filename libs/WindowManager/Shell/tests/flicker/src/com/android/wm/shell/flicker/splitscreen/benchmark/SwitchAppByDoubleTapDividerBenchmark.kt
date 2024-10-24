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

package com.android.wm.shell.flicker.splitscreen.benchmark

import android.platform.test.annotations.IwTest
import android.platform.test.annotations.Presubmit
import android.tools.common.NavBar
import android.tools.common.Rotation
import android.tools.device.flicker.junit.FlickerParametersRunnerFactory
import android.tools.device.flicker.legacy.FlickerBuilder
import android.tools.device.flicker.legacy.FlickerTest
import android.tools.device.flicker.legacy.FlickerTestFactory
import android.tools.device.helpers.WindowUtils
import android.tools.device.traces.parsers.WindowManagerStateHelper
import androidx.test.filters.RequiresDevice
import com.android.wm.shell.flicker.splitscreen.SplitScreenBase
import com.android.wm.shell.flicker.splitscreen.SplitScreenUtils
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
open class SwitchAppByDoubleTapDividerBenchmark(override val flicker: FlickerTest) :
    SplitScreenBase(flicker) {
    protected val thisTransition: FlickerBuilder.() -> Unit
        get() = {
            setup { SplitScreenUtils.enterSplit(wmHelper, tapl, device, primaryApp, secondaryApp) }
            transitions {
                SplitScreenUtils.doubleTapDividerToSwitch(device)
                wmHelper.StateSyncBuilder().withAppTransitionIdle().waitForAndVerify()

                waitForLayersToSwitch(wmHelper)
                waitForWindowsToSwitch(wmHelper)
            }
        }

    override val transition: FlickerBuilder.() -> Unit
        get() = {
            withoutTracing(this)
            defaultSetup(this)
            defaultTeardown(this)
            thisTransition(this)
        }

    private fun waitForWindowsToSwitch(wmHelper: WindowManagerStateHelper) {
        wmHelper
            .StateSyncBuilder()
            .add("appWindowsSwitched") {
                val primaryAppWindow =
                    it.wmState.visibleWindows.firstOrNull { window ->
                        primaryApp.windowMatchesAnyOf(window)
                    }
                        ?: return@add false
                val secondaryAppWindow =
                    it.wmState.visibleWindows.firstOrNull { window ->
                        secondaryApp.windowMatchesAnyOf(window)
                    }
                        ?: return@add false

                if (isLandscape(flicker.scenario.endRotation)) {
                    return@add if (flicker.scenario.isTablet) {
                        secondaryAppWindow.frame.right <= primaryAppWindow.frame.left
                    } else {
                        primaryAppWindow.frame.right <= secondaryAppWindow.frame.left
                    }
                } else {
                    return@add if (flicker.scenario.isTablet) {
                        primaryAppWindow.frame.bottom <= secondaryAppWindow.frame.top
                    } else {
                        primaryAppWindow.frame.bottom <= secondaryAppWindow.frame.top
                    }
                }
            }
            .waitForAndVerify()
    }

    private fun waitForLayersToSwitch(wmHelper: WindowManagerStateHelper) {
        wmHelper
            .StateSyncBuilder()
            .add("appLayersSwitched") {
                val primaryAppLayer =
                    it.layerState.visibleLayers.firstOrNull { window ->
                        primaryApp.layerMatchesAnyOf(window)
                    }
                        ?: return@add false
                val secondaryAppLayer =
                    it.layerState.visibleLayers.firstOrNull { window ->
                        secondaryApp.layerMatchesAnyOf(window)
                    }
                        ?: return@add false

                val primaryVisibleRegion = primaryAppLayer.visibleRegion?.bounds ?: return@add false
                val secondaryVisibleRegion =
                    secondaryAppLayer.visibleRegion?.bounds ?: return@add false

                if (isLandscape(flicker.scenario.endRotation)) {
                    return@add if (flicker.scenario.isTablet) {
                        secondaryVisibleRegion.right <= primaryVisibleRegion.left
                    } else {
                        primaryVisibleRegion.right <= secondaryVisibleRegion.left
                    }
                } else {
                    return@add if (flicker.scenario.isTablet) {
                        primaryVisibleRegion.bottom <= secondaryVisibleRegion.top
                    } else {
                        primaryVisibleRegion.bottom <= secondaryVisibleRegion.top
                    }
                }
            }
            .waitForAndVerify()
    }

    private fun isLandscape(rotation: Rotation): Boolean {
        val displayBounds = WindowUtils.getDisplayBounds(rotation)
        return displayBounds.width > displayBounds.height
    }

    @IwTest(focusArea = "sysui")
    @Presubmit
    @Test
    open fun cujCompleted() {
        // TODO(b/246490534): Add validation for switched app after withAppTransitionIdle is
        // robust enough to get the correct end state.
    }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): List<FlickerTest> {
            return FlickerTestFactory.nonRotationTests(
                // TODO(b/176061063):The 3 buttons of nav bar do not exist in the hierarchy.
                supportedNavigationModes = listOf(NavBar.MODE_GESTURAL)
            )
        }
    }
}
