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

package com.android.wm.shell.flicker.pip

import android.platform.test.annotations.Postsubmit
import android.platform.test.annotations.RequiresDevice
import android.tools.device.flicker.junit.FlickerParametersRunnerFactory
import android.tools.device.flicker.legacy.FlickerBuilder
import android.tools.device.flicker.legacy.FlickerTest
import android.tools.device.flicker.legacy.FlickerTestFactory
import android.tools.device.flicker.rules.RemoveAllTasksButHomeRule
import com.android.server.wm.flicker.testapp.ActivityOptions
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/** Test the dragging of a PIP window. */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class PipDragTest(flicker: FlickerTest) : PipTransition(flicker) {
    private var isDraggedLeft: Boolean = true
    override val transition: FlickerBuilder.() -> Unit
        get() = {
            val stringExtras = mapOf(ActivityOptions.Pip.EXTRA_ENTER_PIP to "true")

            setup {
                tapl.setEnableRotation(true)
                // Launch the PIP activity and wait for it to enter PiP mode
                RemoveAllTasksButHomeRule.removeAllTasksButHome()
                pipApp.launchViaIntentAndWaitForPip(wmHelper, stringExtras = stringExtras)

                // determine the direction of dragging to test for
                isDraggedLeft = pipApp.isCloserToRightEdge(wmHelper)
            }
            teardown {
                // release the primary pointer after dragging without release
                pipApp.releasePipAfterDragging()

                pipApp.exit(wmHelper)
                tapl.setEnableRotation(false)
            }
            transitions { pipApp.dragPipWindowAwayFromEdgeWithoutRelease(wmHelper, 50) }
        }

    @Postsubmit
    @Test
    fun pipLayerMovesAwayFromEdge() {
        flicker.assertLayers {
            val pipLayerList = layers { pipApp.layerMatchesAnyOf(it) && it.isVisible }
            pipLayerList.zipWithNext { previous, current ->
                if (isDraggedLeft) {
                    previous.visibleRegion.isToTheRight(current.visibleRegion.region)
                } else {
                    current.visibleRegion.isToTheRight(previous.visibleRegion.region)
                }
            }
        }
    }

    companion object {
        /**
         * Creates the test configurations.
         *
         * See [FlickerTestFactory.nonRotationTests] for configuring screen orientation and
         * navigation modes.
         */
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): List<FlickerTest> {
            return FlickerTestFactory.nonRotationTests()
        }
    }
}
