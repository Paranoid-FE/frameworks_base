/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.shade

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.test.filters.SmallTest
import com.android.keyguard.KeyguardSecurityContainerController
import com.android.keyguard.LockIconViewController
import com.android.keyguard.dagger.KeyguardBouncerComponent
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.classifier.FalsingCollectorFake
import com.android.systemui.classifier.FalsingManagerFake
import com.android.systemui.dock.DockManager
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.KeyguardUnlockAnimationController
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.keyguard.ui.viewmodel.KeyguardBouncerViewModel
import com.android.systemui.keyguard.ui.viewmodel.PrimaryBouncerToGoneTransitionViewModel
import com.android.systemui.multishade.data.remoteproxy.MultiShadeInputProxy
import com.android.systemui.multishade.data.repository.MultiShadeRepository
import com.android.systemui.multishade.domain.interactor.MultiShadeInteractor
import com.android.systemui.multishade.domain.interactor.MultiShadeMotionEventInteractor
import com.android.systemui.shade.NotificationShadeWindowView.InteractionEventHandler
import com.android.systemui.statusbar.LockscreenShadeTransitionController
import com.android.systemui.statusbar.NotificationInsetsController
import com.android.systemui.statusbar.NotificationShadeDepthController
import com.android.systemui.statusbar.NotificationShadeWindowController
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.notification.stack.AmbientState
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.phone.CentralSurfaces
import com.android.systemui.statusbar.phone.PhoneStatusBarViewController
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager
import com.android.systemui.statusbar.window.StatusBarWindowStateController
import com.android.systemui.unfold.UnfoldTransitionProgressProvider
import com.android.systemui.util.mockito.any
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.anyFloat
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations
import java.util.Optional

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper(setAsMainLooper = true)
class NotificationShadeWindowViewControllerTest : SysuiTestCase() {

    @Mock private lateinit var view: NotificationShadeWindowView
    @Mock private lateinit var sysuiStatusBarStateController: SysuiStatusBarStateController
    @Mock private lateinit var centralSurfaces: CentralSurfaces
    @Mock private lateinit var dockManager: DockManager
    @Mock private lateinit var notificationPanelViewController: NotificationPanelViewController
    @Mock private lateinit var notificationShadeDepthController: NotificationShadeDepthController
    @Mock private lateinit var notificationShadeWindowController: NotificationShadeWindowController
    @Mock private lateinit var keyguardUnlockAnimationController: KeyguardUnlockAnimationController
    @Mock private lateinit var shadeController: ShadeController
    @Mock private lateinit var ambientState: AmbientState
    @Mock private lateinit var keyguardBouncerViewModel: KeyguardBouncerViewModel
    @Mock private lateinit var stackScrollLayoutController: NotificationStackScrollLayoutController
    @Mock private lateinit var statusBarKeyguardViewManager: StatusBarKeyguardViewManager
    @Mock private lateinit var statusBarWindowStateController: StatusBarWindowStateController
    @Mock
    private lateinit var lockscreenShadeTransitionController: LockscreenShadeTransitionController
    @Mock private lateinit var lockIconViewController: LockIconViewController
    @Mock private lateinit var phoneStatusBarViewController: PhoneStatusBarViewController
    @Mock private lateinit var pulsingGestureListener: PulsingGestureListener
    @Mock private lateinit var notificationInsetsController: NotificationInsetsController
    @Mock lateinit var keyguardBouncerComponentFactory: KeyguardBouncerComponent.Factory
    @Mock lateinit var keyguardBouncerComponent: KeyguardBouncerComponent
    @Mock lateinit var keyguardSecurityContainerController: KeyguardSecurityContainerController
    @Mock
    private lateinit var unfoldTransitionProgressProvider: Optional<UnfoldTransitionProgressProvider>
    @Mock lateinit var keyguardTransitionInteractor: KeyguardTransitionInteractor
    @Mock
    lateinit var primaryBouncerToGoneTransitionViewModel: PrimaryBouncerToGoneTransitionViewModel

    private lateinit var interactionEventHandlerCaptor: ArgumentCaptor<InteractionEventHandler>
    private lateinit var interactionEventHandler: InteractionEventHandler

    private lateinit var underTest: NotificationShadeWindowViewController

    private lateinit var testScope: TestScope

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(view.bottom).thenReturn(VIEW_BOTTOM)
        whenever(view.findViewById<ViewGroup>(R.id.keyguard_bouncer_container))
            .thenReturn(mock(ViewGroup::class.java))
        whenever(keyguardBouncerComponentFactory.create(any(ViewGroup::class.java)))
            .thenReturn(keyguardBouncerComponent)
        whenever(keyguardBouncerComponent.securityContainerController)
            .thenReturn(keyguardSecurityContainerController)
        whenever(keyguardTransitionInteractor.lockscreenToDreamingTransition)
            .thenReturn(emptyFlow<TransitionStep>())

        val featureFlags = FakeFeatureFlags()
        featureFlags.set(Flags.TRACKPAD_GESTURE_COMMON, true)
        featureFlags.set(Flags.TRACKPAD_GESTURE_FEATURES, false)
        featureFlags.set(Flags.DUAL_SHADE, false)
        featureFlags.set(Flags.SPLIT_SHADE_SUBPIXEL_OPTIMIZATION, true)

        val inputProxy = MultiShadeInputProxy()
        testScope = TestScope()
        val multiShadeInteractor =
            MultiShadeInteractor(
                applicationScope = testScope.backgroundScope,
                repository =
                    MultiShadeRepository(
                        applicationContext = context,
                        inputProxy = inputProxy,
                    ),
                inputProxy = inputProxy,
            )
        underTest =
            NotificationShadeWindowViewController(
                lockscreenShadeTransitionController,
                FalsingCollectorFake(),
                sysuiStatusBarStateController,
                dockManager,
                notificationShadeDepthController,
                view,
                notificationPanelViewController,
                ShadeExpansionStateManager(),
                stackScrollLayoutController,
                statusBarKeyguardViewManager,
                statusBarWindowStateController,
                lockIconViewController,
                centralSurfaces,
                notificationShadeWindowController,
                unfoldTransitionProgressProvider,
                keyguardUnlockAnimationController,
                notificationInsetsController,
                ambientState,
                pulsingGestureListener,
                keyguardBouncerViewModel,
                keyguardBouncerComponentFactory,
                keyguardTransitionInteractor,
                primaryBouncerToGoneTransitionViewModel,
                featureFlags,
                { multiShadeInteractor },
                FakeSystemClock(),
                {
                    MultiShadeMotionEventInteractor(
                        applicationContext = context,
                        applicationScope = testScope.backgroundScope,
                        multiShadeInteractor = multiShadeInteractor,
                        featureFlags = featureFlags,
                        keyguardTransitionInteractor =
                            KeyguardTransitionInteractor(
                                repository = FakeKeyguardTransitionRepository(),
                            ),
                        falsingManager = FalsingManagerFake(),
                        shadeController = shadeController,
                    )
                },
            )
        underTest.setupExpandedStatusBar()

        interactionEventHandlerCaptor = ArgumentCaptor.forClass(InteractionEventHandler::class.java)
        verify(view).setInteractionEventHandler(interactionEventHandlerCaptor.capture())
        interactionEventHandler = interactionEventHandlerCaptor.value
    }

    // Note: So far, these tests only cover interactions with the status bar view controller. More
    // tests need to be added to test the rest of handleDispatchTouchEvent.

    @Test
    fun handleDispatchTouchEvent_nullStatusBarViewController_returnsFalse() =
        testScope.runTest {
            underTest.setStatusBarViewController(null)

            val returnVal = interactionEventHandler.handleDispatchTouchEvent(DOWN_EVENT)

            assertThat(returnVal).isFalse()
        }

    @Test
    fun handleDispatchTouchEvent_downTouchBelowView_sendsTouchToSb() =
        testScope.runTest {
            underTest.setStatusBarViewController(phoneStatusBarViewController)
            val ev = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, VIEW_BOTTOM + 4f, 0)
            whenever(phoneStatusBarViewController.sendTouchToView(ev)).thenReturn(true)

            val returnVal = interactionEventHandler.handleDispatchTouchEvent(ev)

            verify(phoneStatusBarViewController).sendTouchToView(ev)
            assertThat(returnVal).isTrue()
        }

    @Test
    fun handleDispatchTouchEvent_downTouchBelowViewThenAnotherTouch_sendsTouchToSb() =
        testScope.runTest {
            underTest.setStatusBarViewController(phoneStatusBarViewController)
            val downEvBelow =
                MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, VIEW_BOTTOM + 4f, 0)
            interactionEventHandler.handleDispatchTouchEvent(downEvBelow)

            val nextEvent =
                MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_MOVE, 0f, VIEW_BOTTOM + 5f, 0)
            whenever(phoneStatusBarViewController.sendTouchToView(nextEvent)).thenReturn(true)

            val returnVal = interactionEventHandler.handleDispatchTouchEvent(nextEvent)

            verify(phoneStatusBarViewController).sendTouchToView(nextEvent)
            assertThat(returnVal).isTrue()
        }

    @Test
    fun handleDispatchTouchEvent_downAndPanelCollapsedAndInSbBoundAndSbWindowShow_sendsTouchToSb() =
        testScope.runTest {
            underTest.setStatusBarViewController(phoneStatusBarViewController)
            whenever(statusBarWindowStateController.windowIsShowing()).thenReturn(true)
            whenever(notificationPanelViewController.isFullyCollapsed).thenReturn(true)
            whenever(phoneStatusBarViewController.touchIsWithinView(anyFloat(), anyFloat()))
                .thenReturn(true)
            whenever(phoneStatusBarViewController.sendTouchToView(DOWN_EVENT)).thenReturn(true)

            val returnVal = interactionEventHandler.handleDispatchTouchEvent(DOWN_EVENT)

            verify(phoneStatusBarViewController).sendTouchToView(DOWN_EVENT)
            assertThat(returnVal).isTrue()
        }

    @Test
    fun handleDispatchTouchEvent_panelNotCollapsed_returnsNull() =
        testScope.runTest {
            underTest.setStatusBarViewController(phoneStatusBarViewController)
            whenever(statusBarWindowStateController.windowIsShowing()).thenReturn(true)
            whenever(phoneStatusBarViewController.touchIsWithinView(anyFloat(), anyFloat()))
                .thenReturn(true)
            // Item we're testing
            whenever(notificationPanelViewController.isFullyCollapsed).thenReturn(false)

            val returnVal = interactionEventHandler.handleDispatchTouchEvent(DOWN_EVENT)

            verify(phoneStatusBarViewController, never()).sendTouchToView(DOWN_EVENT)
            assertThat(returnVal).isNull()
        }

    @Test
    fun handleDispatchTouchEvent_touchNotInSbBounds_returnsNull() =
        testScope.runTest {
            underTest.setStatusBarViewController(phoneStatusBarViewController)
            whenever(statusBarWindowStateController.windowIsShowing()).thenReturn(true)
            whenever(notificationPanelViewController.isFullyCollapsed).thenReturn(true)
            // Item we're testing
            whenever(phoneStatusBarViewController.touchIsWithinView(anyFloat(), anyFloat()))
                .thenReturn(false)

            val returnVal = interactionEventHandler.handleDispatchTouchEvent(DOWN_EVENT)

            verify(phoneStatusBarViewController, never()).sendTouchToView(DOWN_EVENT)
            assertThat(returnVal).isNull()
        }

    @Test
    fun handleDispatchTouchEvent_sbWindowNotShowing_noSendTouchToSbAndReturnsTrue() =
        testScope.runTest {
            underTest.setStatusBarViewController(phoneStatusBarViewController)
            whenever(notificationPanelViewController.isFullyCollapsed).thenReturn(true)
            whenever(phoneStatusBarViewController.touchIsWithinView(anyFloat(), anyFloat()))
                .thenReturn(true)
            // Item we're testing
            whenever(statusBarWindowStateController.windowIsShowing()).thenReturn(false)

            val returnVal = interactionEventHandler.handleDispatchTouchEvent(DOWN_EVENT)

            verify(phoneStatusBarViewController, never()).sendTouchToView(DOWN_EVENT)
            assertThat(returnVal).isTrue()
        }

    @Test
    fun handleDispatchTouchEvent_downEventSentToSbThenAnotherEvent_sendsTouchToSb() =
        testScope.runTest {
            underTest.setStatusBarViewController(phoneStatusBarViewController)
            whenever(statusBarWindowStateController.windowIsShowing()).thenReturn(true)
            whenever(notificationPanelViewController.isFullyCollapsed).thenReturn(true)
            whenever(phoneStatusBarViewController.touchIsWithinView(anyFloat(), anyFloat()))
                .thenReturn(true)

            // Down event first
            interactionEventHandler.handleDispatchTouchEvent(DOWN_EVENT)

            // Then another event
            val nextEvent = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_MOVE, 0f, 0f, 0)
            whenever(phoneStatusBarViewController.sendTouchToView(nextEvent)).thenReturn(true)

            val returnVal = interactionEventHandler.handleDispatchTouchEvent(nextEvent)

            verify(phoneStatusBarViewController).sendTouchToView(nextEvent)
            assertThat(returnVal).isTrue()
        }

    @Test
    fun shouldInterceptTouchEvent_statusBarKeyguardViewManagerShouldIntercept() {
        // down event should be intercepted by keyguardViewManager
        whenever(statusBarKeyguardViewManager.shouldInterceptTouchEvent(DOWN_EVENT))
            .thenReturn(true)

        // Then touch should not be intercepted
        val shouldIntercept = interactionEventHandler.shouldInterceptTouchEvent(DOWN_EVENT)
        assertThat(shouldIntercept).isTrue()
    }

    @Test
    fun testGetKeyguardMessageArea() =
        testScope.runTest {
            underTest.keyguardMessageArea
            verify(view).findViewById<ViewGroup>(R.id.keyguard_message_area)
        }

    companion object {
        private val DOWN_EVENT = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
        private const val VIEW_BOTTOM = 100
    }
}
