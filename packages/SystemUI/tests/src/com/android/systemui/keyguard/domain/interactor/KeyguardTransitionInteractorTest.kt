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

package com.android.systemui.keyguard.domain.interactor

import com.android.systemui.RoboPilotTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectValues
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState.AOD
import com.android.systemui.keyguard.shared.model.KeyguardState.DOZING
import com.android.systemui.keyguard.shared.model.KeyguardState.GONE
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.shared.model.TransitionState.FINISHED
import com.android.systemui.keyguard.shared.model.TransitionState.RUNNING
import com.android.systemui.keyguard.shared.model.TransitionState.STARTED
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RoboPilotTest
@RunWith(AndroidJUnit4::class)
@kotlinx.coroutines.ExperimentalCoroutinesApi
class KeyguardTransitionInteractorTest : SysuiTestCase() {

    private lateinit var underTest: KeyguardTransitionInteractor
    private lateinit var repository: FakeKeyguardTransitionRepository

    @Before
    fun setUp() {
        repository = FakeKeyguardTransitionRepository()
        underTest = KeyguardTransitionInteractor(repository)
    }

    @Test
    fun transitionCollectorsReceivesOnlyAppropriateEvents() = runTest {
        val lockscreenToAodSteps by collectValues(underTest.lockscreenToAodTransition)
        val aodToLockscreenSteps by collectValues(underTest.aodToLockscreenTransition)

        val steps = mutableListOf<TransitionStep>()
        steps.add(TransitionStep(AOD, GONE, 0f, STARTED))
        steps.add(TransitionStep(AOD, GONE, 1f, FINISHED))
        steps.add(TransitionStep(AOD, LOCKSCREEN, 0f, STARTED))
        steps.add(TransitionStep(AOD, LOCKSCREEN, 0.5f, RUNNING))
        steps.add(TransitionStep(AOD, LOCKSCREEN, 1f, FINISHED))
        steps.add(TransitionStep(LOCKSCREEN, AOD, 0f, STARTED))
        steps.add(TransitionStep(LOCKSCREEN, AOD, 0.1f, RUNNING))
        steps.add(TransitionStep(LOCKSCREEN, AOD, 0.2f, RUNNING))

        steps.forEach {
            repository.sendTransitionStep(it)
            runCurrent()
        }

        assertThat(aodToLockscreenSteps).isEqualTo(steps.subList(2, 5))
        assertThat(lockscreenToAodSteps).isEqualTo(steps.subList(5, 8))
    }

    @Test
    fun dozeAmountTransitionTest() = runTest {
        val dozeAmountSteps by collectValues(underTest.dozeAmountTransition)

        val steps = mutableListOf<TransitionStep>()

        steps.add(TransitionStep(AOD, LOCKSCREEN, 0f, STARTED))
        steps.add(TransitionStep(AOD, LOCKSCREEN, 0.5f, RUNNING))
        steps.add(TransitionStep(AOD, LOCKSCREEN, 1f, FINISHED))
        steps.add(TransitionStep(LOCKSCREEN, AOD, 0f, STARTED))
        steps.add(TransitionStep(LOCKSCREEN, AOD, 0.8f, RUNNING))
        steps.add(TransitionStep(LOCKSCREEN, AOD, 0.9f, RUNNING))
        steps.add(TransitionStep(LOCKSCREEN, AOD, 1f, FINISHED))

        steps.forEach {
            repository.sendTransitionStep(it)
            runCurrent()
        }

        assertThat(dozeAmountSteps.subList(0, 3))
            .isEqualTo(
                listOf(
                    steps[0].copy(value = 1f - steps[0].value),
                    steps[1].copy(value = 1f - steps[1].value),
                    steps[2].copy(value = 1f - steps[2].value),
                )
            )
        assertThat(dozeAmountSteps.subList(3, 7)).isEqualTo(steps.subList(3, 7))
    }

    @Test
    fun keyguardStateTests() = runTest {
        val finishedSteps by collectValues(underTest.finishedKeyguardState)

        val steps = mutableListOf<TransitionStep>()

        steps.add(TransitionStep(AOD, LOCKSCREEN, 0f, STARTED))
        steps.add(TransitionStep(AOD, LOCKSCREEN, 0.5f, RUNNING))
        steps.add(TransitionStep(AOD, LOCKSCREEN, 1f, FINISHED))
        steps.add(TransitionStep(LOCKSCREEN, AOD, 0f, STARTED))
        steps.add(TransitionStep(LOCKSCREEN, AOD, 0.9f, RUNNING))
        steps.add(TransitionStep(LOCKSCREEN, AOD, 1f, FINISHED))
        steps.add(TransitionStep(AOD, GONE, 1f, STARTED))

        steps.forEach {
            repository.sendTransitionStep(it)
            runCurrent()
        }

        assertThat(finishedSteps).isEqualTo(listOf(LOCKSCREEN, AOD))
    }

    @Test
    fun finishedKeyguardTransitionStepTests() = runTest {
        val finishedSteps by collectValues(underTest.finishedKeyguardTransitionStep)

        val steps = mutableListOf<TransitionStep>()

        steps.add(TransitionStep(AOD, LOCKSCREEN, 0f, STARTED))
        steps.add(TransitionStep(AOD, LOCKSCREEN, 0.5f, RUNNING))
        steps.add(TransitionStep(AOD, LOCKSCREEN, 1f, FINISHED))
        steps.add(TransitionStep(LOCKSCREEN, AOD, 0f, STARTED))
        steps.add(TransitionStep(LOCKSCREEN, AOD, 0.9f, RUNNING))
        steps.add(TransitionStep(LOCKSCREEN, AOD, 1f, FINISHED))
        steps.add(TransitionStep(AOD, GONE, 1f, STARTED))

        steps.forEach {
            repository.sendTransitionStep(it)
            runCurrent()
        }

        assertThat(finishedSteps).isEqualTo(listOf(steps[2], steps[5]))
    }

    @Test
    fun startedKeyguardTransitionStepTests() = runTest {
        val startedSteps by collectValues(underTest.startedKeyguardTransitionStep)

        val steps = mutableListOf<TransitionStep>()

        steps.add(TransitionStep(AOD, LOCKSCREEN, 0f, STARTED))
        steps.add(TransitionStep(AOD, LOCKSCREEN, 0.5f, RUNNING))
        steps.add(TransitionStep(AOD, LOCKSCREEN, 1f, FINISHED))
        steps.add(TransitionStep(LOCKSCREEN, AOD, 0f, STARTED))
        steps.add(TransitionStep(LOCKSCREEN, AOD, 0.9f, RUNNING))
        steps.add(TransitionStep(LOCKSCREEN, AOD, 1f, FINISHED))
        steps.add(TransitionStep(AOD, GONE, 1f, STARTED))

        steps.forEach {
            repository.sendTransitionStep(it)
            runCurrent()
        }

        assertThat(startedSteps).isEqualTo(listOf(steps[0], steps[3], steps[6]))
    }

    @Test
    fun transitionValue() = runTest {
        val startedSteps by collectValues(underTest.transitionValue(state = DOZING))

        val toSteps =
            listOf(
                TransitionStep(AOD, DOZING, 0f, STARTED),
                TransitionStep(AOD, DOZING, 0.5f, RUNNING),
                TransitionStep(AOD, DOZING, 1f, FINISHED),
            )
        toSteps.forEach {
            repository.sendTransitionStep(it)
            runCurrent()
        }

        val fromSteps =
            listOf(
                TransitionStep(DOZING, LOCKSCREEN, 0f, STARTED),
                TransitionStep(DOZING, LOCKSCREEN, 0.5f, RUNNING),
                TransitionStep(DOZING, LOCKSCREEN, 1f, FINISHED),
            )
        fromSteps.forEach {
            repository.sendTransitionStep(it)
            runCurrent()
        }

        assertThat(startedSteps).isEqualTo(listOf(0f, 0.5f, 1f, 1f, 0.5f, 0f))
    }
}
