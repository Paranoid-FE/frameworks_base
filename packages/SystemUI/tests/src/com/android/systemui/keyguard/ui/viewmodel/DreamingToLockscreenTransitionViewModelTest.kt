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

package com.android.systemui.keyguard.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.RoboPilotTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RoboPilotTest
@RunWith(AndroidJUnit4::class)
class DreamingToLockscreenTransitionViewModelTest : SysuiTestCase() {
    private lateinit var underTest: DreamingToLockscreenTransitionViewModel
    private lateinit var repository: FakeKeyguardTransitionRepository
    private lateinit var transitionAnimation: KeyguardTransitionAnimationFlow

    @Before
    fun setUp() {
        repository = FakeKeyguardTransitionRepository()
        val interactor = KeyguardTransitionInteractor(repository)
        underTest = DreamingToLockscreenTransitionViewModel(interactor)
    }

    @Test
    fun dreamOverlayTranslationY() =
        runTest(UnconfinedTestDispatcher()) {
            val values = mutableListOf<Float>()

            val pixels = 100
            val job =
                underTest.dreamOverlayTranslationY(pixels).onEach { values.add(it) }.launchIn(this)

            // Should start running here...
            repository.sendTransitionStep(step(0f, TransitionState.STARTED))
            repository.sendTransitionStep(step(0f))
            repository.sendTransitionStep(step(0.3f))
            repository.sendTransitionStep(step(0.5f))
            repository.sendTransitionStep(step(0.6f))
            // ...up to here
            repository.sendTransitionStep(step(0.8f))
            repository.sendTransitionStep(step(1f))

            assertThat(values.size).isEqualTo(5)
            values.forEach { assertThat(it).isIn(Range.closed(0f, 100f)) }

            job.cancel()
        }

    @Test
    fun dreamOverlayFadeOut() =
        runTest(UnconfinedTestDispatcher()) {
            val values = mutableListOf<Float>()

            val job = underTest.dreamOverlayAlpha.onEach { values.add(it) }.launchIn(this)

            // Should start running here...
            repository.sendTransitionStep(step(0f, TransitionState.STARTED))
            repository.sendTransitionStep(step(0f))
            repository.sendTransitionStep(step(0.1f))
            repository.sendTransitionStep(step(0.5f))
            // ...up to here
            repository.sendTransitionStep(step(1f))

            // Only two values should be present, since the dream overlay runs for a small fraction
            // of the overall animation time
            assertThat(values.size).isEqualTo(4)
            values.forEach { assertThat(it).isIn(Range.closed(0f, 1f)) }

            job.cancel()
        }

    @Test
    fun lockscreenFadeIn() =
        runTest(UnconfinedTestDispatcher()) {
            val values = mutableListOf<Float>()

            val job = underTest.lockscreenAlpha.onEach { values.add(it) }.launchIn(this)

            repository.sendTransitionStep(step(0f, TransitionState.STARTED))
            repository.sendTransitionStep(step(0f))
            repository.sendTransitionStep(step(0.1f))
            repository.sendTransitionStep(step(0.2f))
            repository.sendTransitionStep(step(0.3f))
            repository.sendTransitionStep(step(1f))

            assertThat(values.size).isEqualTo(4)
            values.forEach { assertThat(it).isIn(Range.closed(0f, 1f)) }

            job.cancel()
        }

    @Test
    fun lockscreenTranslationY() =
        runTest(UnconfinedTestDispatcher()) {
            val values = mutableListOf<Float>()

            val pixels = 100
            val job =
                underTest.lockscreenTranslationY(pixels).onEach { values.add(it) }.launchIn(this)

            repository.sendTransitionStep(step(0f, TransitionState.STARTED))
            repository.sendTransitionStep(step(0f))
            repository.sendTransitionStep(step(0.3f))
            repository.sendTransitionStep(step(0.5f))
            repository.sendTransitionStep(step(1f))

            assertThat(values.size).isEqualTo(5)
            values.forEach { assertThat(it).isIn(Range.closed(-100f, 0f)) }

            job.cancel()
        }

    private fun step(
        value: Float,
        state: TransitionState = TransitionState.RUNNING
    ): TransitionStep {
        return TransitionStep(
            from = KeyguardState.DREAMING,
            to = KeyguardState.LOCKSCREEN,
            value = value,
            transitionState = state,
            ownerName = "DreamingToLockscreenTransitionViewModelTest"
        )
    }
}
