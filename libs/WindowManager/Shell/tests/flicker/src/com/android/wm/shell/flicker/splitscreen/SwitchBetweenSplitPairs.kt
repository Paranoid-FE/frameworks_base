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
 */

package com.android.wm.shell.flicker.splitscreen

import android.platform.test.annotations.FlakyTest
import android.platform.test.annotations.IwTest
import android.platform.test.annotations.Presubmit
import android.tools.device.flicker.junit.FlickerParametersRunnerFactory
import android.tools.device.flicker.legacy.FlickerBuilder
import android.tools.device.flicker.legacy.FlickerTest
import android.tools.device.flicker.legacy.FlickerTestFactory
import androidx.test.filters.RequiresDevice
import com.android.wm.shell.flicker.ICommonAssertions
import com.android.wm.shell.flicker.SPLIT_SCREEN_DIVIDER_COMPONENT
import com.android.wm.shell.flicker.appWindowBecomesInvisible
import com.android.wm.shell.flicker.appWindowBecomesVisible
import com.android.wm.shell.flicker.appWindowIsInvisibleAtEnd
import com.android.wm.shell.flicker.appWindowIsVisibleAtEnd
import com.android.wm.shell.flicker.appWindowIsVisibleAtStart
import com.android.wm.shell.flicker.layerBecomesInvisible
import com.android.wm.shell.flicker.layerBecomesVisible
import com.android.wm.shell.flicker.splitAppLayerBoundsIsVisibleAtEnd
import com.android.wm.shell.flicker.splitAppLayerBoundsSnapToDivider
import com.android.wm.shell.flicker.splitScreenDividerIsVisibleAtEnd
import com.android.wm.shell.flicker.splitScreenDividerIsVisibleAtStart
import com.android.wm.shell.flicker.splitscreen.benchmark.SwitchBetweenSplitPairsBenchmark
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test quick switch between two split pairs.
 *
 * To run this test: `atest WMShellFlickerTests:SwitchBetweenSplitPairs`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SwitchBetweenSplitPairs(override val flicker: FlickerTest) :
    SwitchBetweenSplitPairsBenchmark(flicker), ICommonAssertions {
    override val transition: FlickerBuilder.() -> Unit
        get() = {
            defaultSetup(this)
            defaultTeardown(this)
            thisTransition(this)
        }

    @IwTest(focusArea = "sysui")
    @Presubmit
    @Test
    override fun cujCompleted() {
        flicker.appWindowIsVisibleAtStart(thirdApp)
        flicker.appWindowIsVisibleAtStart(fourthApp)
        flicker.splitScreenDividerIsVisibleAtStart()

        flicker.appWindowIsVisibleAtEnd(primaryApp)
        flicker.appWindowIsVisibleAtEnd(secondaryApp)
        flicker.appWindowIsInvisibleAtEnd(thirdApp)
        flicker.appWindowIsInvisibleAtEnd(fourthApp)
        flicker.splitScreenDividerIsVisibleAtEnd()
    }

    @Presubmit
    @Test
    fun splitScreenDividerInvisibleAtMiddle() =
        flicker.assertLayers {
            this.isVisible(SPLIT_SCREEN_DIVIDER_COMPONENT)
                .then()
                .isInvisible(SPLIT_SCREEN_DIVIDER_COMPONENT)
                .then()
                .isVisible(SPLIT_SCREEN_DIVIDER_COMPONENT)
        }

    @FlakyTest(bugId = 247095572)
    @Test
    fun primaryAppLayerBecomesVisible() = flicker.layerBecomesVisible(primaryApp)

    @FlakyTest(bugId = 247095572)
    @Test
    fun secondaryAppLayerBecomesVisible() = flicker.layerBecomesVisible(secondaryApp)

    @FlakyTest(bugId = 247095572)
    @Test
    fun thirdAppLayerBecomesInvisible() = flicker.layerBecomesInvisible(thirdApp)

    @FlakyTest(bugId = 247095572)
    @Test
    fun fourthAppLayerBecomesInvisible() = flicker.layerBecomesInvisible(fourthApp)

    @Presubmit
    @Test
    fun primaryAppBoundsIsVisibleAtEnd() =
        flicker.splitAppLayerBoundsIsVisibleAtEnd(
            primaryApp,
            landscapePosLeft = tapl.isTablet,
            portraitPosTop = false
        )

    @Presubmit
    @Test
    fun secondaryAppBoundsIsVisibleAtEnd() =
        flicker.splitAppLayerBoundsIsVisibleAtEnd(
            secondaryApp,
            landscapePosLeft = !tapl.isTablet,
            portraitPosTop = true
        )

    @Presubmit
    @Test
    fun thirdAppBoundsIsVisibleAtBegin() =
        flicker.assertLayersStart {
            this.splitAppLayerBoundsSnapToDivider(
                thirdApp,
                landscapePosLeft = tapl.isTablet,
                portraitPosTop = false,
                flicker.scenario.startRotation
            )
        }

    @Presubmit
    @Test
    fun fourthAppBoundsIsVisibleAtBegin() =
        flicker.assertLayersStart {
            this.splitAppLayerBoundsSnapToDivider(
                fourthApp,
                landscapePosLeft = !tapl.isTablet,
                portraitPosTop = true,
                flicker.scenario.startRotation
            )
        }

    @Presubmit
    @Test
    fun primaryAppWindowBecomesVisible() = flicker.appWindowBecomesVisible(primaryApp)

    @Presubmit
    @Test
    fun secondaryAppWindowBecomesVisible() = flicker.appWindowBecomesVisible(secondaryApp)

    @Presubmit
    @Test
    fun thirdAppWindowBecomesVisible() = flicker.appWindowBecomesInvisible(thirdApp)

    @Presubmit
    @Test
    fun fourthAppWindowBecomesVisible() = flicker.appWindowBecomesInvisible(fourthApp)

    /** {@inheritDoc} */
    @FlakyTest(bugId = 251268711)
    @Test
    override fun entireScreenCovered() = super.entireScreenCovered()

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun navBarLayerIsVisibleAtStartAndEnd() = super.navBarLayerIsVisibleAtStartAndEnd()

    /** {@inheritDoc} */
    @FlakyTest(bugId = 206753786)
    @Test
    override fun navBarLayerPositionAtStartAndEnd() = super.navBarLayerPositionAtStartAndEnd()

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun navBarWindowIsAlwaysVisible() = super.navBarWindowIsAlwaysVisible()

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun statusBarLayerIsVisibleAtStartAndEnd() =
        super.statusBarLayerIsVisibleAtStartAndEnd()

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun statusBarLayerPositionAtStartAndEnd() = super.statusBarLayerPositionAtStartAndEnd()

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun statusBarWindowIsAlwaysVisible() = super.statusBarWindowIsAlwaysVisible()

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun taskBarLayerIsVisibleAtStartAndEnd() = super.taskBarLayerIsVisibleAtStartAndEnd()

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun taskBarWindowIsAlwaysVisible() = super.taskBarWindowIsAlwaysVisible()

    /** {@inheritDoc} */
    @FlakyTest
    @Test
    override fun visibleLayersShownMoreThanOneConsecutiveEntry() =
        super.visibleLayersShownMoreThanOneConsecutiveEntry()

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun visibleWindowsShownMoreThanOneConsecutiveEntry() =
        super.visibleWindowsShownMoreThanOneConsecutiveEntry()

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): List<FlickerTest> {
            return FlickerTestFactory.nonRotationTests()
        }
    }
}
