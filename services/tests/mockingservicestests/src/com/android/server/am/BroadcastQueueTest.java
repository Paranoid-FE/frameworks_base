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

package com.android.server.am;

import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_FINISH_RECEIVER;
import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_START_RECEIVER;
import static android.os.UserHandle.USER_SYSTEM;

import static com.android.server.am.ActivityManagerDebugConfig.LOG_WRITER_INFO;
import static com.android.server.am.BroadcastProcessQueue.reasonToString;
import static com.android.server.am.BroadcastRecord.deliveryStateToString;
import static com.android.server.am.BroadcastRecord.isReceiverEquals;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.BackgroundStartPrivileges;
import android.app.BroadcastOptions;
import android.app.IApplicationThread;
import android.app.UidObserver;
import android.app.usage.UsageEvents.Event;
import android.app.usage.UsageStatsManagerInternal;
import android.content.ComponentName;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerExemptionManager;
import android.os.SystemClock;
import android.os.TestLooperManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;

import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.server.AlarmManagerInternal;
import com.android.server.DropBoxManagerInternal;
import com.android.server.LocalServices;
import com.android.server.am.ActivityManagerService.Injector;
import com.android.server.appop.AppOpsService;
import com.android.server.wm.ActivityTaskManagerService;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.mockito.ArgumentMatcher;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.verification.VerificationMode;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/**
 * Common tests for {@link BroadcastQueue} implementations.
 */
@MediumTest
@RunWith(Parameterized.class)
@SuppressWarnings("GuardedBy")
public class BroadcastQueueTest {
    private static final String TAG = "BroadcastQueueTest";

    @Rule
    public final ApplicationExitInfoTest.ServiceThreadRule
            mServiceThreadRule = new ApplicationExitInfoTest.ServiceThreadRule();

    private final Impl mImpl;

    private enum Impl {
        DEFAULT,
        MODERN,
    }

    private Context mContext;
    private HandlerThread mHandlerThread;
    private TestLooperManager mLooper;
    private AtomicInteger mNextPid;

    @Mock
    private AppOpsService mAppOpsService;
    @Mock
    private ProcessList mProcessList;
    @Mock
    private DropBoxManagerInternal mDropBoxManagerInt;
    @Mock
    private PackageManagerInternal mPackageManagerInt;
    @Mock
    private UsageStatsManagerInternal mUsageStatsManagerInt;
    @Mock
    private AlarmManagerInternal mAlarmManagerInt;

    private ActivityManagerService mAms;
    private BroadcastQueue mQueue;
    BroadcastConstants mConstants;
    private BroadcastSkipPolicy mSkipPolicy;
    private UidObserver mUidObserver;

    /**
     * Desired behavior of the next
     * {@link ActivityManagerService#startProcessLocked} call.
     */
    private AtomicReference<ProcessStartBehavior> mNextProcessStartBehavior = new AtomicReference<>(
            ProcessStartBehavior.SUCCESS);

    /**
     * Map from PID to registered registered runtime receivers.
     */
    private SparseArray<ReceiverList> mRegisteredReceivers = new SparseArray<>();

    /**
     * Collection of all active processes during current test run.
     */
    private List<ProcessRecord> mActiveProcesses = new ArrayList<>();

    /**
     * Collection of scheduled broadcasts, in the order they were dispatched.
     */
    private List<Pair<Integer, String>> mScheduledBroadcasts = new ArrayList<>();

    @Parameters(name = "impl={0}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][] { {Impl.DEFAULT}, {Impl.MODERN} });
    }

    public BroadcastQueueTest(Impl impl) {
        mImpl = impl;
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();

        // Pause all event processing until a test chooses to resume
        mLooper = Objects.requireNonNull(InstrumentationRegistry.getInstrumentation()
                .acquireLooperManager(mHandlerThread.getLooper()));

        mNextPid = new AtomicInteger(100);

        LocalServices.removeServiceForTest(DropBoxManagerInternal.class);
        LocalServices.addService(DropBoxManagerInternal.class, mDropBoxManagerInt);
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.addService(PackageManagerInternal.class, mPackageManagerInt);
        LocalServices.removeServiceForTest(AlarmManagerInternal.class);
        LocalServices.addService(AlarmManagerInternal.class, mAlarmManagerInt);
        doReturn(new ComponentName("", "")).when(mPackageManagerInt).getSystemUiServiceComponent();
        doNothing().when(mPackageManagerInt).setPackageStoppedState(any(), anyBoolean(), anyInt());
        doAnswer((invocation) -> {
            return getUidForPackage(invocation.getArgument(0));
        }).when(mPackageManagerInt).getPackageUid(any(), anyLong(), eq(UserHandle.USER_SYSTEM));

        final ActivityManagerService realAms = new ActivityManagerService(
                new TestInjector(mContext), mServiceThreadRule.getThread());
        realAms.mActivityTaskManager = new ActivityTaskManagerService(mContext);
        realAms.mActivityTaskManager.initialize(null, null, mContext.getMainLooper());
        realAms.mAtmInternal = spy(realAms.mActivityTaskManager.getAtmInternal());
        realAms.mOomAdjuster = spy(realAms.mOomAdjuster);
        realAms.mPackageManagerInt = mPackageManagerInt;
        realAms.mUsageStatsService = mUsageStatsManagerInt;
        realAms.mProcessesReady = true;
        mAms = spy(realAms);
        doAnswer((invocation) -> {
            Log.v(TAG, "Intercepting startProcessLocked() for "
                    + Arrays.toString(invocation.getArguments()));
            assertHealth();
            final ProcessStartBehavior behavior = mNextProcessStartBehavior
                    .getAndSet(ProcessStartBehavior.SUCCESS);
            if (behavior == ProcessStartBehavior.FAIL_NULL) {
                return null;
            }
            final String processName = invocation.getArgument(0);
            final ApplicationInfo ai = invocation.getArgument(1);
            final ProcessRecord res = makeActiveProcessRecord(ai, processName,
                    ProcessBehavior.NORMAL, UnaryOperator.identity());
            final ProcessRecord deliverRes;
            switch (behavior) {
                case SUCCESS_PREDECESSOR:
                case FAIL_TIMEOUT_PREDECESSOR:
                    // Create a different process that will be linked to the
                    // returned process via a predecessor/successor relationship
                    mActiveProcesses.remove(res);
                    deliverRes = makeActiveProcessRecord(ai, processName,
                          ProcessBehavior.NORMAL, UnaryOperator.identity());
                    deliverRes.mPredecessor = res;
                    res.mSuccessor = deliverRes;
                    break;
                default:
                    deliverRes = res;
                    break;
            }
            mHandlerThread.getThreadHandler().post(() -> {
                synchronized (mAms) {
                    switch (behavior) {
                        case SUCCESS:
                        case SUCCESS_PREDECESSOR:
                            mQueue.onApplicationAttachedLocked(deliverRes);
                            break;
                        case FAIL_TIMEOUT:
                        case FAIL_TIMEOUT_PREDECESSOR:
                            mActiveProcesses.remove(deliverRes);
                            mQueue.onApplicationTimeoutLocked(deliverRes);
                            break;
                        default:
                            throw new UnsupportedOperationException();
                    }
                }
            });
            return res;
        }).when(mAms).startProcessLocked(any(), any(), anyBoolean(), anyInt(),
                any(), anyInt(), anyBoolean(), anyBoolean());

        doAnswer((invocation) -> {
            final String processName = invocation.getArgument(0);
            final int uid = invocation.getArgument(1);
            for (ProcessRecord r : mActiveProcesses) {
                if (Objects.equals(r.processName, processName) && r.uid == uid) {
                    return r;
                }
            }
            return null;
        }).when(mAms).getProcessRecordLocked(any(), anyInt());
        doNothing().when(mAms).appNotResponding(any(), any());

        doAnswer((invocation) -> {
            mUidObserver = invocation.getArgument(0);
            return null;
        }).when(mAms).registerUidObserver(any(), anyInt(), anyInt(), any());

        mConstants = new BroadcastConstants(Settings.Global.BROADCAST_FG_CONSTANTS);
        mConstants.TIMEOUT = 100;
        mConstants.ALLOW_BG_ACTIVITY_START_TIMEOUT = 0;

        mSkipPolicy = spy(new BroadcastSkipPolicy(mAms));
        doReturn(null).when(mSkipPolicy).shouldSkipMessage(any(), any());
        doReturn(false).when(mSkipPolicy).disallowBackgroundStart(any());

        final BroadcastHistory emptyHistory = new BroadcastHistory(mConstants) {
            public void addBroadcastToHistoryLocked(BroadcastRecord original) {
                // Ignored
            }
        };

        if (mImpl == Impl.DEFAULT) {
            mQueue = new BroadcastQueueImpl(mAms, mHandlerThread.getThreadHandler(), TAG,
                    mConstants, mSkipPolicy, emptyHistory, false,
                    ProcessList.SCHED_GROUP_DEFAULT);
        } else if (mImpl == Impl.MODERN) {
            mQueue = new BroadcastQueueModernImpl(mAms, mHandlerThread.getThreadHandler(),
                    mConstants, mConstants, mSkipPolicy, emptyHistory);
        } else {
            throw new UnsupportedOperationException();
        }

        mQueue.start(mContext.getContentResolver());
    }

    @After
    public void tearDown() throws Exception {
        mHandlerThread.quit();

        // Verify that all processes have finished handling broadcasts
        for (ProcessRecord app : mActiveProcesses) {
            assertEquals(app.toShortString(), 0,
                    app.mReceivers.numberOfCurReceivers());
            assertEquals(app.toShortString(), ProcessList.SCHED_GROUP_UNDEFINED,
                    mQueue.getPreferredSchedulingGroupLocked(app));
        }
    }

    private class TestInjector extends Injector {
        TestInjector(Context context) {
            super(context);
        }

        @Override
        public AppOpsService getAppOpsService(File recentAccessesFile, File storageFile,
                Handler handler) {
            return mAppOpsService;
        }

        @Override
        public Handler getUiHandler(ActivityManagerService service) {
            return mHandlerThread.getThreadHandler();
        }

        @Override
        public ProcessList getProcessList(ActivityManagerService service) {
            return mProcessList;
        }
    }

    private enum ProcessStartBehavior {
        /** Process starts successfully */
        SUCCESS,
        /** Process starts successfully via predecessor */
        SUCCESS_PREDECESSOR,
        /** Process fails by reporting timeout */
        FAIL_TIMEOUT,
        /** Process fails by reporting timeout via predecessor */
        FAIL_TIMEOUT_PREDECESSOR,
        /** Process fails by immediately returning null */
        FAIL_NULL,
    }

    private enum ProcessBehavior {
        /** Process broadcasts normally */
        NORMAL,
        /** Wedge and never confirm broadcast receipt */
        WEDGE,
        /** Process broadcast by requesting abort */
        ABORT,
        /** Appear to behave completely dead */
        DEAD,
    }

    private ProcessRecord makeActiveProcessRecord(String packageName) throws Exception {
        return makeActiveProcessRecord(packageName, packageName, ProcessBehavior.NORMAL,
                UserHandle.USER_SYSTEM);
    }

    private ProcessRecord makeActiveProcessRecord(String packageName,
            ProcessBehavior behavior) throws Exception {
        return makeActiveProcessRecord(packageName, packageName, behavior, UserHandle.USER_SYSTEM);
    }

    private ProcessRecord makeActiveProcessRecord(String packageName, String processName,
            ProcessBehavior behavior, int userId) throws Exception {
        final ApplicationInfo ai = makeApplicationInfo(packageName, processName, userId);
        return makeActiveProcessRecord(ai, ai.processName, behavior,
                UnaryOperator.identity());
    }

    private ProcessRecord makeActiveProcessRecord(ApplicationInfo ai, String processName,
            ProcessBehavior behavior, UnaryOperator<Bundle> extrasOperator) throws Exception {
        final boolean wedge = (behavior == ProcessBehavior.WEDGE);
        final boolean abort = (behavior == ProcessBehavior.ABORT);
        final boolean dead = (behavior == ProcessBehavior.DEAD);

        final ProcessRecord r = spy(new ProcessRecord(mAms, ai, processName, ai.uid));
        r.setPid(mNextPid.getAndIncrement());
        mActiveProcesses.add(r);

        final IApplicationThread thread;
        if (dead) {
            thread = mock(IApplicationThread.class, (invocation) -> {
                throw new DeadObjectException();
            });
        } else {
            thread = mock(IApplicationThread.class);
        }
        final IBinder threadBinder = new Binder();
        doReturn(threadBinder).when(thread).asBinder();
        r.makeActive(thread, mAms.mProcessStats);

        final IIntentReceiver receiver = mock(IIntentReceiver.class);
        final IBinder receiverBinder = new Binder();
        doReturn(receiverBinder).when(receiver).asBinder();
        final ReceiverList receiverList = new ReceiverList(mAms, r, r.getPid(), r.info.uid,
                UserHandle.getUserId(r.info.uid), receiver);
        mRegisteredReceivers.put(r.getPid(), receiverList);

        doReturn(42L).when(r).getCpuDelayTime();

        doAnswer((invocation) -> {
            Log.v(TAG, "Intercepting killLocked() for "
                    + Arrays.toString(invocation.getArguments()));
            mActiveProcesses.remove(r);
            mRegisteredReceivers.remove(r.getPid());
            return invocation.callRealMethod();
        }).when(r).killLocked(any(), any(), anyInt(), anyInt(), anyBoolean(), anyBoolean());

        // If we're entirely dead, rely on default behaviors above
        if (dead) return r;

        doAnswer((invocation) -> {
            Log.v(TAG, "Intercepting scheduleReceiver() for "
                    + Arrays.toString(invocation.getArguments()) + " package " + ai.packageName);
            assertHealth();
            final Intent intent = invocation.getArgument(0);
            final Bundle extras = invocation.getArgument(5);
            mScheduledBroadcasts.add(makeScheduledBroadcast(r, intent));
            if (!wedge) {
                assertTrue(r.mReceivers.numberOfCurReceivers() > 0);
                assertNotEquals(ProcessList.SCHED_GROUP_UNDEFINED,
                        mQueue.getPreferredSchedulingGroupLocked(r));
                mHandlerThread.getThreadHandler().post(() -> {
                    synchronized (mAms) {
                        mQueue.finishReceiverLocked(r, Activity.RESULT_OK, null,
                                extrasOperator.apply(extras), abort, false);
                    }
                });
            }
            return null;
        }).when(thread).scheduleReceiver(any(), any(), any(), anyInt(), any(), any(), anyBoolean(),
                anyBoolean(), anyInt(), anyInt(), anyInt(), any());

        doAnswer((invocation) -> {
            Log.v(TAG, "Intercepting scheduleRegisteredReceiver() for "
                    + Arrays.toString(invocation.getArguments()) + " package " + ai.packageName);
            assertHealth();
            final Intent intent = invocation.getArgument(1);
            final Bundle extras = invocation.getArgument(4);
            final boolean ordered = invocation.getArgument(5);
            mScheduledBroadcasts.add(makeScheduledBroadcast(r, intent));
            if (!wedge && ordered) {
                assertTrue(r.mReceivers.numberOfCurReceivers() > 0);
                assertNotEquals(ProcessList.SCHED_GROUP_UNDEFINED,
                        mQueue.getPreferredSchedulingGroupLocked(r));
                mHandlerThread.getThreadHandler().post(() -> {
                    synchronized (mAms) {
                        mQueue.finishReceiverLocked(r, Activity.RESULT_OK,
                                null, extrasOperator.apply(extras), abort, false);
                    }
                });
            }
            return null;
        }).when(thread).scheduleRegisteredReceiver(any(), any(), anyInt(), any(), any(),
                anyBoolean(), anyBoolean(), anyBoolean(), anyInt(), anyInt(), anyInt(), any());

        return r;
    }

    private Pair<Integer, String> makeScheduledBroadcast(ProcessRecord app, Intent intent) {
        return Pair.create(app.getPid(), intent.getAction());
    }

    static ApplicationInfo makeApplicationInfo(String packageName) {
        return makeApplicationInfo(packageName, packageName, UserHandle.USER_SYSTEM);
    }

    static ApplicationInfo makeApplicationInfo(String packageName, String processName, int userId) {
        final ApplicationInfo ai = new ApplicationInfo();
        ai.packageName = packageName;
        ai.processName = processName;
        ai.uid = getUidForPackage(packageName, userId);
        return ai;
    }

    static ResolveInfo withPriority(ResolveInfo info, int priority) {
        info.priority = priority;
        return info;
    }

    static ResolveInfo makeManifestReceiver(String packageName, String name) {
        return makeManifestReceiver(packageName, name, UserHandle.USER_SYSTEM);
    }

    static ResolveInfo makeManifestReceiver(String packageName, String name, int userId) {
        return makeManifestReceiver(packageName, packageName, name, userId);
    }

    static ResolveInfo makeManifestReceiver(String packageName, String processName, String name,
            int userId) {
        final ResolveInfo ri = new ResolveInfo();
        ri.activityInfo = new ActivityInfo();
        ri.activityInfo.packageName = packageName;
        ri.activityInfo.processName = processName;
        ri.activityInfo.name = name;
        ri.activityInfo.applicationInfo = makeApplicationInfo(packageName, processName, userId);
        return ri;
    }

    private BroadcastFilter makeRegisteredReceiver(ProcessRecord app) {
        return makeRegisteredReceiver(app, 0);
    }

    private BroadcastFilter makeRegisteredReceiver(ProcessRecord app, int priority) {
        final ReceiverList receiverList = mRegisteredReceivers.get(app.getPid());
        final IntentFilter filter = new IntentFilter();
        filter.setPriority(priority);
        final BroadcastFilter res = new BroadcastFilter(filter, receiverList,
                receiverList.app.info.packageName, null, null, null, receiverList.uid,
                receiverList.userId, false, false, true);
        receiverList.add(res);
        return res;
    }

    private BroadcastRecord makeBroadcastRecord(Intent intent, ProcessRecord callerApp,
            List<Object> receivers) {
        return makeBroadcastRecord(intent, callerApp, BroadcastOptions.makeBasic(),
                receivers, false, null, null, UserHandle.USER_SYSTEM);
    }

    private BroadcastRecord makeBroadcastRecord(Intent intent, ProcessRecord callerApp,
            int userId, List<Object> receivers) {
        return makeBroadcastRecord(intent, callerApp, BroadcastOptions.makeBasic(),
                receivers, false, null, null, userId);
    }

    private BroadcastRecord makeBroadcastRecord(Intent intent, ProcessRecord callerApp,
            BroadcastOptions options, List<Object> receivers) {
        return makeBroadcastRecord(intent, callerApp, options,
                receivers, false, null, null, UserHandle.USER_SYSTEM);
    }

    private BroadcastRecord makeBroadcastRecord(Intent intent, ProcessRecord callerApp,
            List<Object> receivers, IIntentReceiver resultTo) {
        return makeBroadcastRecord(intent, callerApp, BroadcastOptions.makeBasic(),
                receivers, false, resultTo, null, UserHandle.USER_SYSTEM);
    }

    private BroadcastRecord makeOrderedBroadcastRecord(Intent intent, ProcessRecord callerApp,
            List<Object> receivers, IIntentReceiver resultTo, Bundle resultExtras) {
        return makeBroadcastRecord(intent, callerApp, BroadcastOptions.makeBasic(),
                receivers, true, resultTo, resultExtras, UserHandle.USER_SYSTEM);
    }

    private BroadcastRecord makeBroadcastRecord(Intent intent, ProcessRecord callerApp,
            BroadcastOptions options, List<Object> receivers, boolean ordered,
            IIntentReceiver resultTo, Bundle resultExtras, int userId) {
        return new BroadcastRecord(mQueue, intent, callerApp, callerApp.info.packageName, null,
                callerApp.getPid(), callerApp.info.uid, false, null, null, null, null,
                AppOpsManager.OP_NONE, options, receivers, callerApp, resultTo,
                Activity.RESULT_OK, null, resultExtras, ordered, false, false, userId,
                BackgroundStartPrivileges.NONE, false, null);
    }

    private void assertHealth() {
        if (mImpl == Impl.MODERN) {
            // If this fails, it'll throw a clear reason message
            ((BroadcastQueueModernImpl) mQueue).assertHealthLocked();
        }
    }

    private static Map<String, Object> asMap(Bundle bundle) {
        final Map<String, Object> map = new HashMap<>();
        if (bundle != null) {
            for (String key : bundle.keySet()) {
                map.put(key, bundle.get(key));
            }
        }
        return map;
    }

    private ArgumentMatcher<Intent> componentEquals(String packageName, String className) {
        return (test) -> {
            final ComponentName cn = test.getComponent();
            return (cn != null)
                    && Objects.equals(cn.getPackageName(), packageName)
                    && Objects.equals(cn.getClassName(), className);
        };
    }

    private ArgumentMatcher<Intent> filterAndExtrasEquals(Intent intent) {
        return (test) -> {
            return intent.filterEquals(test)
                    && Objects.equals(asMap(intent.getExtras()), asMap(test.getExtras()));
        };
    }

    private ArgumentMatcher<Intent> filterEquals(Intent intent) {
        return (test) -> {
            return intent.filterEquals(test);
        };
    }

    private ArgumentMatcher<Intent> filterEqualsIgnoringComponent(Intent intent) {
        final Intent intentClean = new Intent(intent);
        intentClean.setComponent(null);
        return (test) -> {
            final Intent testClean = new Intent(test);
            testClean.setComponent(null);
            return intentClean.filterEquals(testClean);
        };
    }

    private ArgumentMatcher<Bundle> bundleEquals(Bundle bundle) {
        return (test) -> {
            // TODO: check values in addition to keys
            return Objects.equals(test.keySet(), bundle.keySet());
        };
    }

    private @NonNull Bundle clone(@Nullable Bundle b) {
        return (b != null) ? new Bundle(b) : new Bundle();
    }

    private void enqueueBroadcast(BroadcastRecord r) {
        synchronized (mAms) {
            mQueue.enqueueBroadcastLocked(r);
        }
    }

    /**
     * Un-pause our handler to process pending events, wait for our queue to go
     * idle, and then re-pause the handler.
     */
    private void waitForIdle() throws Exception {
        mLooper.release();
        mQueue.waitForIdle(LOG_WRITER_INFO);
        mLooper = Objects.requireNonNull(InstrumentationRegistry.getInstrumentation()
                .acquireLooperManager(mHandlerThread.getLooper()));
    }

    private void verifyScheduleReceiver(ProcessRecord app, Intent intent) throws Exception {
        verifyScheduleReceiver(times(1), app, intent, UserHandle.USER_SYSTEM);
    }

    private void verifyScheduleReceiver(VerificationMode mode, ProcessRecord app, Intent intent)
            throws Exception {
        verifyScheduleReceiver(mode, app, intent, UserHandle.USER_SYSTEM);
    }

    private void verifyScheduleReceiver(VerificationMode mode, ProcessRecord app, Intent intent,
            ComponentName component) throws Exception {
        final Intent targetedIntent = new Intent(intent);
        targetedIntent.setComponent(component);
        verify(app.getThread(), mode).scheduleReceiver(
                argThat(filterEquals(targetedIntent)), any(), any(),
                anyInt(), any(), any(), eq(false), anyBoolean(), eq(UserHandle.USER_SYSTEM),
                anyInt(), anyInt(), any());
    }

    private void verifyScheduleReceiver(VerificationMode mode, ProcessRecord app,
            Intent intent, int userId) throws Exception {
        verify(app.getThread(), mode).scheduleReceiver(
                argThat(filterEqualsIgnoringComponent(intent)), any(), any(),
                anyInt(), any(), any(), anyBoolean(), anyBoolean(), eq(userId),
                anyInt(), anyInt(), any());
    }

    private void verifyScheduleReceiver(VerificationMode mode, ProcessRecord app,
            int userId) throws Exception {
        verify(app.getThread(), mode).scheduleReceiver(
                any(), any(), any(),
                anyInt(), any(), any(), anyBoolean(), anyBoolean(), eq(userId),
                anyInt(), anyInt(), any());
    }

    private void verifyScheduleRegisteredReceiver(ProcessRecord app, Intent intent)
            throws Exception {
        verifyScheduleRegisteredReceiver(times(1), app, intent, UserHandle.USER_SYSTEM);
    }

    private void verifyScheduleRegisteredReceiver(VerificationMode mode, ProcessRecord app,
            Intent intent) throws Exception {
        verifyScheduleRegisteredReceiver(mode, app, intent, UserHandle.USER_SYSTEM);
    }

    private void verifyScheduleRegisteredReceiver(VerificationMode mode, ProcessRecord app,
            Intent intent, int userId) throws Exception {
        verify(app.getThread(), mode).scheduleRegisteredReceiver(
                any(), argThat(filterEqualsIgnoringComponent(intent)),
                anyInt(), any(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
                eq(userId), anyInt(), anyInt(), any());
    }

    private void verifyScheduleRegisteredReceiver(VerificationMode mode, ProcessRecord app,
            int userId) throws Exception {
        verify(app.getThread(), mode).scheduleRegisteredReceiver(
                any(), any(),
                anyInt(), any(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
                eq(userId), anyInt(), anyInt(), any());
    }

    static final int USER_GUEST = 11;

    static final String PACKAGE_ANDROID = "android";
    static final String PACKAGE_PHONE = "com.android.phone";
    static final String PACKAGE_RED = "com.example.red";
    static final String PACKAGE_GREEN = "com.example.green";
    static final String PACKAGE_BLUE = "com.example.blue";
    static final String PACKAGE_YELLOW = "com.example.yellow";
    static final String PACKAGE_ORANGE = "com.example.orange";

    static final String PROCESS_SYSTEM = "system";

    static final String CLASS_RED = "com.example.red.Red";
    static final String CLASS_GREEN = "com.example.green.Green";
    static final String CLASS_BLUE = "com.example.blue.Blue";
    static final String CLASS_YELLOW = "com.example.yellow.Yellow";
    static final String CLASS_ORANGE = "com.example.orange.Orange";

    static int getUidForPackage(@NonNull String packageName) {
        switch (packageName) {
            case PACKAGE_ANDROID: return android.os.Process.SYSTEM_UID;
            case PACKAGE_PHONE: return android.os.Process.PHONE_UID;
            case PACKAGE_RED: return android.os.Process.FIRST_APPLICATION_UID + 1;
            case PACKAGE_GREEN: return android.os.Process.FIRST_APPLICATION_UID + 2;
            case PACKAGE_BLUE: return android.os.Process.FIRST_APPLICATION_UID + 3;
            case PACKAGE_YELLOW: return android.os.Process.FIRST_APPLICATION_UID + 4;
            case PACKAGE_ORANGE: return android.os.Process.FIRST_APPLICATION_UID + 5;
            default: throw new IllegalArgumentException();
        }
    }

    static int getUidForPackage(@NonNull String packageName, int userId) {
        return UserHandle.getUid(userId, getUidForPackage(packageName));
    }

    /**
     * Baseline verification of common debugging infrastructure, mostly to make
     * sure it doesn't crash.
     */
    @Test
    public void testDebugging() throws Exception {
        // To maximize test coverage, dump current state; we're not worried
        // about the actual output, just that we don't crash
        mQueue.dumpDebug(new ProtoOutputStream(),
                ActivityManagerServiceDumpBroadcastsProto.BROADCAST_QUEUE);
        mQueue.dumpLocked(FileDescriptor.err, new PrintWriter(Writer.nullWriter()),
                null, 0, true, true, true, null, false);
        mQueue.dumpToDropBoxLocked(TAG);

        BroadcastQueue.logv(TAG);
        BroadcastQueue.logw(TAG);

        assertNotNull(mQueue.toString());
        assertNotNull(mQueue.describeStateLocked());

        for (int i = Byte.MIN_VALUE; i < Byte.MAX_VALUE; i++) {
            assertNotNull(deliveryStateToString(i));
            assertNotNull(reasonToString(i));
        }
    }

    /**
     * Verify dispatch of simple broadcast to single manifest receiver in
     * already-running warm app.
     */
    @Test
    public void testSimple_Manifest_Warm() throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);
        final ProcessRecord receiverApp = makeActiveProcessRecord(PACKAGE_GREEN);

        final Intent intent = new Intent(Intent.ACTION_TIMEZONE_CHANGED);
        enqueueBroadcast(makeBroadcastRecord(intent, callerApp,
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN))));

        waitForIdle();
        verifyScheduleReceiver(receiverApp, intent);
    }

    /**
     * Verify dispatch of multiple broadcasts to multiple manifest receivers in
     * already-running warm apps.
     */
    @Test
    public void testSimple_Manifest_Warm_Multiple() throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);

        final ProcessRecord receiverGreenApp = makeActiveProcessRecord(PACKAGE_GREEN);
        final ProcessRecord receiverBlueApp = makeActiveProcessRecord(PACKAGE_BLUE);

        final Intent timezone = new Intent(Intent.ACTION_TIMEZONE_CHANGED);
        enqueueBroadcast(makeBroadcastRecord(timezone, callerApp,
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN),
                        makeManifestReceiver(PACKAGE_BLUE, CLASS_BLUE))));

        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        enqueueBroadcast(makeBroadcastRecord(airplane, callerApp,
                List.of(makeManifestReceiver(PACKAGE_BLUE, CLASS_BLUE))));

        waitForIdle();
        verifyScheduleReceiver(receiverGreenApp, timezone);
        verifyScheduleReceiver(receiverBlueApp, timezone);
        verifyScheduleReceiver(receiverBlueApp, airplane);
    }

    /**
     * Verify dispatch of multiple broadcast to multiple manifest receivers in
     * apps that require cold starts.
     */
    @Test
    public void testSimple_Manifest_ColdThenWarm() throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);

        // We purposefully dispatch into green twice; the first time cold and
        // the second time it should already be running

        final Intent timezone = new Intent(Intent.ACTION_TIMEZONE_CHANGED);
        enqueueBroadcast(makeBroadcastRecord(timezone, callerApp,
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN),
                        makeManifestReceiver(PACKAGE_BLUE, CLASS_BLUE))));

        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        enqueueBroadcast(makeBroadcastRecord(airplane, callerApp,
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN))));

        waitForIdle();
        final ProcessRecord receiverGreenApp = mAms.getProcessRecordLocked(PACKAGE_GREEN,
                getUidForPackage(PACKAGE_GREEN));
        final ProcessRecord receiverBlueApp = mAms.getProcessRecordLocked(PACKAGE_BLUE,
                getUidForPackage(PACKAGE_BLUE));
        verifyScheduleReceiver(receiverGreenApp, timezone);
        verifyScheduleReceiver(receiverGreenApp, airplane);
        verifyScheduleReceiver(receiverBlueApp, timezone);
    }

    /**
     * Verify dispatch of simple broadcast to single registered receiver in
     * already-running warm app.
     */
    @Test
    public void testSimple_Registered() throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);
        final ProcessRecord receiverApp = makeActiveProcessRecord(PACKAGE_GREEN);

        final Intent intent = new Intent(Intent.ACTION_TIMEZONE_CHANGED);
        enqueueBroadcast(makeBroadcastRecord(intent, callerApp,
                List.of(makeRegisteredReceiver(receiverApp))));

        waitForIdle();
        verifyScheduleRegisteredReceiver(receiverApp, intent);
    }

    /**
     * Verify dispatch of multiple broadcasts to multiple registered receivers
     * in already-running warm apps.
     */
    @Test
    public void testSimple_Registered_Multiple() throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);

        final ProcessRecord receiverGreenApp = makeActiveProcessRecord(PACKAGE_GREEN);
        final ProcessRecord receiverBlueApp = makeActiveProcessRecord(PACKAGE_BLUE);

        final Intent timezone = new Intent(Intent.ACTION_TIMEZONE_CHANGED);
        enqueueBroadcast(makeBroadcastRecord(timezone, callerApp,
                List.of(makeRegisteredReceiver(receiverGreenApp),
                        makeRegisteredReceiver(receiverBlueApp))));

        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        enqueueBroadcast(makeBroadcastRecord(airplane, callerApp,
                List.of(makeRegisteredReceiver(receiverBlueApp))));

        waitForIdle();
        verifyScheduleRegisteredReceiver(receiverGreenApp, timezone);
        verifyScheduleRegisteredReceiver(receiverBlueApp, timezone);
        verifyScheduleRegisteredReceiver(receiverBlueApp, airplane);
    }

    /**
     * Verify dispatch of multiple broadcasts mixed to both manifest and
     * registered receivers, to both warm and cold apps.
     */
    @Test
    public void testComplex() throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);

        final ProcessRecord receiverGreenApp = makeActiveProcessRecord(PACKAGE_GREEN);
        final ProcessRecord receiverYellowApp = makeActiveProcessRecord(PACKAGE_YELLOW);

        final Intent timezone = new Intent(Intent.ACTION_TIMEZONE_CHANGED);
        enqueueBroadcast(makeBroadcastRecord(timezone, callerApp,
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN),
                        makeRegisteredReceiver(receiverGreenApp),
                        makeManifestReceiver(PACKAGE_BLUE, CLASS_BLUE),
                        makeRegisteredReceiver(receiverYellowApp))));

        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        airplane.setComponent(new ComponentName(PACKAGE_YELLOW, CLASS_YELLOW));
        final BroadcastOptions options = BroadcastOptions.makeBasic();
        options.recordResponseEventWhileInBackground(42L);
        enqueueBroadcast(makeBroadcastRecord(airplane, callerApp, options,
                List.of(makeManifestReceiver(PACKAGE_YELLOW, CLASS_YELLOW))));

        waitForIdle();
        final ProcessRecord receiverBlueApp = mAms.getProcessRecordLocked(PACKAGE_BLUE,
                getUidForPackage(PACKAGE_BLUE));
        verifyScheduleReceiver(receiverGreenApp, timezone);
        verifyScheduleRegisteredReceiver(receiverGreenApp, timezone);
        verifyScheduleReceiver(receiverBlueApp, timezone);
        verifyScheduleRegisteredReceiver(receiverYellowApp, timezone);
        verifyScheduleReceiver(receiverYellowApp, airplane);

        for (ProcessRecord receiverApp : new ProcessRecord[] {
                receiverGreenApp, receiverBlueApp, receiverYellowApp
        }) {
            // Confirm expected OOM adjustments; we were invoked once to upgrade
            // and once to downgrade
            assertEquals(String.valueOf(receiverApp), ActivityManager.PROCESS_STATE_RECEIVER,
                    receiverApp.mState.getReportedProcState());
            verify(mAms, times(2)).enqueueOomAdjTargetLocked(eq(receiverApp));

            if ((mImpl == Impl.DEFAULT) && (receiverApp == receiverBlueApp)) {
                // Nuance: the default implementation doesn't ask for manifest
                // cold-started apps to be thawed, but the modern stack does
            } else {
                // Confirm that app was thawed
                verify(mAms.mOomAdjuster, atLeastOnce()).unfreezeTemporarily(
                        eq(receiverApp), eq(OOM_ADJ_REASON_START_RECEIVER));

                // Confirm that we added package to process
                verify(receiverApp, atLeastOnce()).addPackage(eq(receiverApp.info.packageName),
                        anyLong(), any());
            }

            // Confirm that we've reported package as being used
            verify(mAms, atLeastOnce()).notifyPackageUse(eq(receiverApp.info.packageName),
                    eq(PackageManager.NOTIFY_PACKAGE_USE_BROADCAST_RECEIVER));

            // Confirm that we unstopped manifest receivers
            verify(mAms.mPackageManagerInt, atLeastOnce()).setPackageStoppedState(
                    eq(receiverApp.info.packageName), eq(false), eq(UserHandle.USER_SYSTEM));
        }

        // Confirm that we've reported expected usage events
        verify(mAms.mUsageStatsService).reportBroadcastDispatched(eq(callerApp.uid),
                eq(PACKAGE_YELLOW), eq(UserHandle.SYSTEM), eq(42L), anyLong(), anyInt());
        verify(mAms.mUsageStatsService).reportEvent(eq(PACKAGE_YELLOW), eq(UserHandle.USER_SYSTEM),
                eq(Event.APP_COMPONENT_USED));
    }

    /**
     * Verify that we detect and ANR a wedged process when delivering to a
     * manifest receiver.
     */
    @Test
    public void testWedged_Manifest() throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);
        final ProcessRecord receiverApp = makeActiveProcessRecord(PACKAGE_GREEN,
                ProcessBehavior.WEDGE);

        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        enqueueBroadcast(makeBroadcastRecord(airplane, callerApp,
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN))));

        waitForIdle();
        verify(mAms).appNotResponding(eq(receiverApp), any());
    }

    /**
     * Verify that we detect and ANR a wedged process when delivering an ordered
     * broadcast, and that we deliver final result.
     */
    @Test
    public void testWedged_Registered_Ordered() throws Exception {
        // Legacy stack doesn't detect these ANRs; likely an oversight
        Assume.assumeTrue(mImpl == Impl.MODERN);

        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);
        final ProcessRecord receiverApp = makeActiveProcessRecord(PACKAGE_GREEN,
                ProcessBehavior.WEDGE);

        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        final IIntentReceiver resultTo = mock(IIntentReceiver.class);
        enqueueBroadcast(makeOrderedBroadcastRecord(airplane, callerApp,
                List.of(makeRegisteredReceiver(receiverApp)), resultTo, null));

        waitForIdle();
        verify(mAms).appNotResponding(eq(receiverApp), any());
        verifyScheduleRegisteredReceiver(callerApp, airplane);
    }

    /**
     * Verify that we detect and ANR a wedged process when delivering an
     * unordered broadcast with a {@code resultTo}.
     */
    @Test
    public void testWedged_Registered_ResultTo() throws Exception {
        // Legacy stack doesn't detect these ANRs; likely an oversight
        Assume.assumeTrue(mImpl == Impl.MODERN);

        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);
        final ProcessRecord receiverApp = makeActiveProcessRecord(PACKAGE_GREEN,
                ProcessBehavior.WEDGE);

        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        final IIntentReceiver resultTo = mock(IIntentReceiver.class);
        enqueueBroadcast(makeBroadcastRecord(airplane, callerApp,
                List.of(makeRegisteredReceiver(receiverApp)), resultTo));

        waitForIdle();
        verify(mAms).appNotResponding(eq(receiverApp), any());
        verifyScheduleRegisteredReceiver(callerApp, airplane);
    }

    /**
     * Verify that we handle registered receivers in a process that always
     * responds with {@link DeadObjectException}, recovering to restart the
     * process and deliver their next broadcast.
     */
    @Test
    public void testDead_Registered() throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);
        final ProcessRecord receiverApp = makeActiveProcessRecord(PACKAGE_GREEN,
                ProcessBehavior.DEAD);

        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        enqueueBroadcast(makeBroadcastRecord(airplane, callerApp,
                List.of(makeRegisteredReceiver(receiverApp))));
        final Intent timezone = new Intent(Intent.ACTION_TIMEZONE_CHANGED);
        enqueueBroadcast(makeBroadcastRecord(timezone, callerApp,
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN))));
        waitForIdle();

        // First broadcast should have already been dead
        verifyScheduleRegisteredReceiver(receiverApp, airplane);
        // The old receiverApp should be killed gently
        assertTrue(receiverApp.isKilled());

        // Second broadcast in new process should work fine
        final ProcessRecord restartedReceiverApp = mAms.getProcessRecordLocked(PACKAGE_GREEN,
                getUidForPackage(PACKAGE_GREEN));
        assertNotEquals(receiverApp, restartedReceiverApp);
        verifyScheduleReceiver(restartedReceiverApp, timezone);
    }

    /**
     * Verify that we handle manifest receivers in a process that always
     * responds with {@link DeadObjectException}, recovering to restart the
     * process and deliver their next broadcast.
     */
    @Test
    public void testDead_Manifest() throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);
        final ProcessRecord receiverApp = makeActiveProcessRecord(PACKAGE_GREEN,
                ProcessBehavior.DEAD);

        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        enqueueBroadcast(makeBroadcastRecord(airplane, callerApp,
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN))));
        final Intent timezone = new Intent(Intent.ACTION_TIMEZONE_CHANGED);
        enqueueBroadcast(makeBroadcastRecord(timezone, callerApp,
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN))));
        waitForIdle();

        // First broadcast should have already been dead
        verifyScheduleReceiver(receiverApp, airplane);
        // The old receiverApp should be killed gently
        assertTrue(receiverApp.isKilled());

        // Second broadcast in new process should work fine
        final ProcessRecord restartedReceiverApp = mAms.getProcessRecordLocked(PACKAGE_GREEN,
                getUidForPackage(PACKAGE_GREEN));
        assertNotEquals(receiverApp, restartedReceiverApp);
        verifyScheduleReceiver(restartedReceiverApp, timezone);
    }

    /**
     * Verify that we handle the system failing to start a process.
     */
    @Test
    public void testFailStartProcess() throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);

        // Send broadcast while process starts are failing
        mNextProcessStartBehavior.set(ProcessStartBehavior.FAIL_NULL);
        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        enqueueBroadcast(makeBroadcastRecord(airplane, callerApp,
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN))));

        // Confirm that queue goes idle, with no processes
        waitForIdle();
        assertEquals(1, mActiveProcesses.size());

        // Send more broadcasts with working process starts
        final Intent timezone = new Intent(Intent.ACTION_TIMEZONE_CHANGED);
        enqueueBroadcast(makeBroadcastRecord(timezone, callerApp,
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN),
                        makeManifestReceiver(PACKAGE_YELLOW, CLASS_YELLOW))));

        // Confirm that we only saw second broadcast
        waitForIdle();
        assertEquals(3, mActiveProcesses.size());
        final ProcessRecord receiverGreenApp = mAms.getProcessRecordLocked(PACKAGE_GREEN,
                getUidForPackage(PACKAGE_GREEN));
        final ProcessRecord receiverYellowApp = mAms.getProcessRecordLocked(PACKAGE_YELLOW,
                getUidForPackage(PACKAGE_YELLOW));
        verifyScheduleReceiver(never(), receiverGreenApp, airplane);
        verifyScheduleReceiver(times(1), receiverGreenApp, timezone);
        verifyScheduleReceiver(times(1), receiverYellowApp, timezone);
    }

    /**
     * Verify that we cleanup a disabled component, skipping a pending dispatch
     * of broadcast to that component.
     */
    @Test
    public void testCleanup() throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);
        final ProcessRecord receiverApp = makeActiveProcessRecord(PACKAGE_GREEN);

        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        enqueueBroadcast(makeBroadcastRecord(airplane, callerApp, new ArrayList<>(
                List.of(makeRegisteredReceiver(receiverApp),
                        makeManifestReceiver(PACKAGE_GREEN, CLASS_RED),
                        makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN),
                        makeManifestReceiver(PACKAGE_GREEN, CLASS_BLUE)))));

        synchronized (mAms) {
            mQueue.cleanupDisabledPackageReceiversLocked(PACKAGE_GREEN, Set.of(CLASS_GREEN),
                    UserHandle.USER_SYSTEM);

            // Also try clearing out other unrelated things that should leave
            // the final receiver intact
            mQueue.cleanupDisabledPackageReceiversLocked(PACKAGE_RED, null,
                    UserHandle.USER_SYSTEM);
            mQueue.cleanupDisabledPackageReceiversLocked(null, null, USER_GUEST);

            // To maximize test coverage, dump current state; we're not worried
            // about the actual output, just that we don't crash
            mQueue.dumpDebug(new ProtoOutputStream(),
                    ActivityManagerServiceDumpBroadcastsProto.BROADCAST_QUEUE);
            mQueue.dumpLocked(FileDescriptor.err, new PrintWriter(Writer.nullWriter()),
                    null, 0, true, true, true, null, false);
        }

        waitForIdle();
        verifyScheduleRegisteredReceiver(receiverApp, airplane);
        verifyScheduleReceiver(times(1), receiverApp, airplane,
                new ComponentName(PACKAGE_GREEN, CLASS_RED));
        verifyScheduleReceiver(never(), receiverApp, airplane,
                new ComponentName(PACKAGE_GREEN, CLASS_GREEN));
        verifyScheduleReceiver(times(1), receiverApp, airplane,
                new ComponentName(PACKAGE_GREEN, CLASS_BLUE));
    }

    @Test
    public void testCleanup_userRemoved() throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(
                PACKAGE_RED, PACKAGE_RED, ProcessBehavior.NORMAL,
                USER_GUEST);

        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        final Intent timeZone = new Intent(Intent.ACTION_TIMEZONE_CHANGED);
        enqueueBroadcast(makeBroadcastRecord(airplane, callerApp, USER_GUEST, new ArrayList<>(
                List.of(makeRegisteredReceiver(callerApp),
                        makeManifestReceiver(PACKAGE_GREEN, CLASS_RED, USER_GUEST),
                        makeManifestReceiver(PACKAGE_BLUE, CLASS_GREEN, USER_GUEST),
                        makeManifestReceiver(PACKAGE_YELLOW, CLASS_BLUE, USER_GUEST)))));
        enqueueBroadcast(makeBroadcastRecord(timeZone, callerApp, USER_GUEST, new ArrayList<>(
                List.of(makeRegisteredReceiver(callerApp),
                        makeManifestReceiver(PACKAGE_GREEN, CLASS_RED, USER_GUEST),
                        makeManifestReceiver(PACKAGE_BLUE, CLASS_GREEN, USER_GUEST),
                        makeManifestReceiver(PACKAGE_YELLOW, CLASS_BLUE, USER_GUEST)))));

        synchronized (mAms) {
            mQueue.cleanupDisabledPackageReceiversLocked(null, null, USER_GUEST);
        }

        waitForIdle();
        // Legacy stack does not remove registered receivers as part of
        // cleanUpDisabledPackageReceiversLocked() call, so verify this only on modern queue.
        if (mImpl == Impl.MODERN) {
            verifyScheduleReceiver(never(), callerApp, USER_GUEST);
            verifyScheduleRegisteredReceiver(never(), callerApp, USER_GUEST);
        }
        for (String pkg : new String[] {
                PACKAGE_GREEN, PACKAGE_BLUE, PACKAGE_YELLOW
        }) {
            assertNull(mAms.getProcessRecordLocked(pkg, getUidForPackage(pkg, USER_GUEST)));
        }
    }

    /**
     * Verify that killing a running process skips registered receivers.
     */
    @Test
    public void testKill() throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);
        final ProcessRecord oldApp = makeActiveProcessRecord(PACKAGE_GREEN);

        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        enqueueBroadcast(makeBroadcastRecord(airplane, callerApp, new ArrayList<>(
                List.of(makeRegisteredReceiver(oldApp),
                        makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN)))));
        synchronized (mAms) {
            oldApp.killLocked(TAG, 42, false);
            mQueue.onApplicationCleanupLocked(oldApp);
        }
        waitForIdle();

        // Confirm that we cold-started after the kill
        final ProcessRecord newApp = mAms.getProcessRecordLocked(PACKAGE_GREEN,
                getUidForPackage(PACKAGE_GREEN));
        assertNotEquals(oldApp, newApp);

        // Confirm that we saw no registered receiver traffic
        final IApplicationThread oldThread = oldApp.getThread();
        verify(oldThread, never()).scheduleRegisteredReceiver(any(), any(), anyInt(), any(), any(),
                anyBoolean(), anyBoolean(), anyBoolean(), anyInt(), anyInt(), anyInt(), any());
        final IApplicationThread newThread = newApp.getThread();
        verify(newThread, never()).scheduleRegisteredReceiver(any(), any(), anyInt(), any(), any(),
                anyBoolean(), anyBoolean(), anyBoolean(), anyInt(), anyInt(), anyInt(), any());

        // Confirm that we saw final manifest broadcast
        verifyScheduleReceiver(times(1), newApp, airplane,
                new ComponentName(PACKAGE_GREEN, CLASS_GREEN));
    }

    @Test
    public void testCold_Success() throws Exception {
        doCold(ProcessStartBehavior.SUCCESS);
    }

    @Test
    public void testCold_Success_Predecessor() throws Exception {
        doCold(ProcessStartBehavior.SUCCESS_PREDECESSOR);
    }

    @Test
    public void testCold_Fail_Null() throws Exception {
        doCold(ProcessStartBehavior.FAIL_NULL);
    }

    @Test
    public void testCold_Fail_Timeout() throws Exception {
        doCold(ProcessStartBehavior.FAIL_TIMEOUT);
    }

    @Test
    public void testCold_Fail_Timeout_Predecessor() throws Exception {
        doCold(ProcessStartBehavior.FAIL_TIMEOUT_PREDECESSOR);
    }

    private void doCold(ProcessStartBehavior behavior) throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);

        mNextProcessStartBehavior.set(behavior);
        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        enqueueBroadcast(makeBroadcastRecord(airplane, callerApp,
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN))));
        waitForIdle();

        // Regardless of success/failure of above, we should always be able to
        // recover and begin sending future broadcasts
        final Intent timezone = new Intent(Intent.ACTION_TIMEZONE_CHANGED);
        enqueueBroadcast(makeBroadcastRecord(timezone, callerApp,
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN))));
        waitForIdle();

        final ProcessRecord receiverApp = mAms.getProcessRecordLocked(PACKAGE_GREEN,
                getUidForPackage(PACKAGE_GREEN));
        verifyScheduleReceiver(receiverApp, timezone);
    }

    /**
     * Verify that we skip broadcasts to an app being backed up.
     */
    @Test
    public void testBackup() throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);
        final ProcessRecord receiverApp = makeActiveProcessRecord(PACKAGE_GREEN);
        receiverApp.setInFullBackup(true);

        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        enqueueBroadcast(makeBroadcastRecord(airplane, callerApp,
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN))));

        waitForIdle();
        verifyScheduleReceiver(never(), receiverApp, airplane,
                new ComponentName(PACKAGE_GREEN, CLASS_GREEN));
    }

    /**
     * Verify that an ordered broadcast collects results from everyone along the
     * chain, and is delivered to final destination.
     */
    @Test
    public void testOrdered() throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);

        // Purposefully warm-start the middle apps to make sure we dispatch to
        // both cold and warm apps in expected order
        makeActiveProcessRecord(makeApplicationInfo(PACKAGE_BLUE), PACKAGE_BLUE,
                ProcessBehavior.NORMAL, (extras) -> {
                    extras = clone(extras);
                    extras.putBoolean(PACKAGE_BLUE, true);
                    return extras;
                });
        makeActiveProcessRecord(makeApplicationInfo(PACKAGE_YELLOW), PACKAGE_YELLOW,
                ProcessBehavior.NORMAL, (extras) -> {
                    extras = clone(extras);
                    extras.putBoolean(PACKAGE_YELLOW, true);
                    return extras;
                });

        final IIntentReceiver orderedResultTo = mock(IIntentReceiver.class);
        final Bundle orderedExtras = new Bundle();
        orderedExtras.putBoolean(PACKAGE_RED, true);

        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        enqueueBroadcast(makeOrderedBroadcastRecord(airplane, callerApp,
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN),
                        makeManifestReceiver(PACKAGE_BLUE, CLASS_BLUE),
                        makeManifestReceiver(PACKAGE_YELLOW, CLASS_YELLOW)),
                orderedResultTo, orderedExtras));

        waitForIdle();
        final IApplicationThread greenThread = mAms.getProcessRecordLocked(PACKAGE_GREEN,
                getUidForPackage(PACKAGE_GREEN)).getThread();
        final IApplicationThread blueThread = mAms.getProcessRecordLocked(PACKAGE_BLUE,
                getUidForPackage(PACKAGE_BLUE)).getThread();
        final IApplicationThread yellowThread = mAms.getProcessRecordLocked(PACKAGE_YELLOW,
                getUidForPackage(PACKAGE_YELLOW)).getThread();
        final IApplicationThread redThread = mAms.getProcessRecordLocked(PACKAGE_RED,
                getUidForPackage(PACKAGE_RED)).getThread();

        // Verify that we called everyone in specific order, and that each of
        // them observed the expected extras at that stage
        final InOrder inOrder = inOrder(greenThread, blueThread, yellowThread, redThread);
        final Bundle expectedExtras = new Bundle();
        expectedExtras.putBoolean(PACKAGE_RED, true);
        inOrder.verify(greenThread).scheduleReceiver(
                argThat(filterEqualsIgnoringComponent(airplane)), any(), any(),
                eq(Activity.RESULT_OK), any(), argThat(bundleEquals(expectedExtras)),
                eq(true), eq(false), eq(UserHandle.USER_SYSTEM), anyInt(), anyInt(), any());
        inOrder.verify(blueThread).scheduleReceiver(
                argThat(filterEqualsIgnoringComponent(airplane)), any(), any(),
                eq(Activity.RESULT_OK), any(), argThat(bundleEquals(expectedExtras)),
                eq(true), eq(false), eq(UserHandle.USER_SYSTEM), anyInt(), anyInt(), any());
        expectedExtras.putBoolean(PACKAGE_BLUE, true);
        inOrder.verify(yellowThread).scheduleReceiver(
                argThat(filterEqualsIgnoringComponent(airplane)), any(), any(),
                eq(Activity.RESULT_OK), any(), argThat(bundleEquals(expectedExtras)),
                eq(true), eq(false), eq(UserHandle.USER_SYSTEM), anyInt(), anyInt(), any());
        expectedExtras.putBoolean(PACKAGE_YELLOW, true);
        inOrder.verify(redThread).scheduleRegisteredReceiver(
                any(), argThat(filterEquals(airplane)),
                eq(Activity.RESULT_OK), any(), argThat(bundleEquals(expectedExtras)),
                eq(false), anyBoolean(), eq(true), eq(UserHandle.USER_SYSTEM), anyInt(),
                anyInt(), any());

        // Finally, verify that we thawed the final receiver
        verify(mAms.mOomAdjuster).unfreezeTemporarily(eq(callerApp),
                eq(OOM_ADJ_REASON_FINISH_RECEIVER));
    }

    /**
     * Verify that an ordered broadcast can be aborted partially through
     * dispatch, and is then delivered to final destination.
     */
    @Test
    public void testOrdered_Aborting() throws Exception {
        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        doOrdered_Aborting(airplane);
    }

    /**
     * Verify that an ordered broadcast marked with
     * {@link Intent#FLAG_RECEIVER_NO_ABORT} cannot be aborted partially through
     * dispatch, and is delivered to everyone in order.
     */
    @Test
    public void testOrdered_Aborting_NoAbort() throws Exception {
        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        airplane.addFlags(Intent.FLAG_RECEIVER_NO_ABORT);
        doOrdered_Aborting(airplane);
    }

    public void doOrdered_Aborting(@NonNull Intent intent) throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);

        // Create a process that aborts any ordered broadcasts
        makeActiveProcessRecord(makeApplicationInfo(PACKAGE_GREEN), PACKAGE_GREEN,
                ProcessBehavior.ABORT, (extras) -> {
                    extras = clone(extras);
                    extras.putBoolean(PACKAGE_GREEN, true);
                    return extras;
                });
        makeActiveProcessRecord(PACKAGE_BLUE);

        final IIntentReceiver orderedResultTo = mock(IIntentReceiver.class);

        enqueueBroadcast(makeOrderedBroadcastRecord(intent, callerApp,
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN),
                        makeManifestReceiver(PACKAGE_BLUE, CLASS_BLUE)),
                orderedResultTo, null));

        waitForIdle();
        final IApplicationThread greenThread = mAms.getProcessRecordLocked(PACKAGE_GREEN,
                getUidForPackage(PACKAGE_GREEN)).getThread();
        final IApplicationThread blueThread = mAms.getProcessRecordLocked(PACKAGE_BLUE,
                getUidForPackage(PACKAGE_BLUE)).getThread();
        final IApplicationThread redThread = mAms.getProcessRecordLocked(PACKAGE_RED,
                getUidForPackage(PACKAGE_RED)).getThread();

        final Bundle expectedExtras = new Bundle();
        expectedExtras.putBoolean(PACKAGE_GREEN, true);

        // Verify that we always invoke the first receiver, but then we might
        // have invoked or skipped the second receiver depending on the intent
        // flag policy; we always deliver to final receiver regardless of abort
        final InOrder inOrder = inOrder(greenThread, blueThread, redThread);
        inOrder.verify(greenThread).scheduleReceiver(
                argThat(filterEqualsIgnoringComponent(intent)), any(), any(),
                eq(Activity.RESULT_OK), any(), any(), eq(true), eq(false),
                eq(UserHandle.USER_SYSTEM), anyInt(), anyInt(), any());
        if ((intent.getFlags() & Intent.FLAG_RECEIVER_NO_ABORT) != 0) {
            inOrder.verify(blueThread).scheduleReceiver(
                    argThat(filterEqualsIgnoringComponent(intent)), any(), any(),
                    eq(Activity.RESULT_OK), any(), any(), eq(true), eq(false),
                    eq(UserHandle.USER_SYSTEM), anyInt(), anyInt(), any());
        } else {
            inOrder.verify(blueThread, never()).scheduleReceiver(
                    any(), any(), any(), anyInt(), any(), any(),
                    anyBoolean(), anyBoolean(), anyInt(), anyInt(), anyInt(), any());
        }
        inOrder.verify(redThread).scheduleRegisteredReceiver(
                any(), argThat(filterEquals(intent)),
                eq(Activity.RESULT_OK), any(), argThat(bundleEquals(expectedExtras)),
                eq(false), anyBoolean(), eq(true), eq(UserHandle.USER_SYSTEM),
                anyInt(), anyInt(), any());
    }

    /**
     * Verify that we immediately dispatch final result for empty lists.
     */
    @Test
    public void testOrdered_Empty() throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);
        final IApplicationThread callerThread = callerApp.getThread();

        final IIntentReceiver orderedResultTo = mock(IIntentReceiver.class);
        final Bundle orderedExtras = new Bundle();
        orderedExtras.putBoolean(PACKAGE_RED, true);

        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        enqueueBroadcast(makeOrderedBroadcastRecord(airplane, callerApp, null,
                orderedResultTo, orderedExtras));

        waitForIdle();
        verify(callerThread).scheduleRegisteredReceiver(
                any(), argThat(filterEquals(airplane)),
                eq(Activity.RESULT_OK), any(), argThat(bundleEquals(orderedExtras)),
                eq(false), anyBoolean(), eq(true), eq(UserHandle.USER_SYSTEM),
                anyInt(), anyInt(), any());
    }

    /**
     * Verify that we deliver results for unordered broadcasts.
     */
    @Test
    public void testUnordered_ResultTo() throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);
        final IApplicationThread callerThread = callerApp.getThread();

        final IIntentReceiver resultTo = mock(IIntentReceiver.class);
        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        enqueueBroadcast(makeBroadcastRecord(airplane, callerApp,
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN),
                        makeManifestReceiver(PACKAGE_BLUE, CLASS_BLUE)), resultTo));

        waitForIdle();
        verify(callerThread).scheduleRegisteredReceiver(
                any(), argThat(filterEquals(airplane)),
                eq(Activity.RESULT_OK), any(), any(),
                eq(false), anyBoolean(), eq(true), eq(UserHandle.USER_SYSTEM),
                anyInt(), anyInt(), any());
    }

    /**
     * Verify that we're not surprised by a process attempting to finishing a
     * broadcast when none is in progress.
     */
    @Test
    public void testUnexpected() throws Exception {
        final ProcessRecord app = makeActiveProcessRecord(PACKAGE_RED);
        mQueue.finishReceiverLocked(app, Activity.RESULT_OK, null, null, false, false);
    }

    @Test
    public void testBackgroundActivityStarts() throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);
        final ProcessRecord receiverApp = makeActiveProcessRecord(PACKAGE_GREEN);

        final BackgroundStartPrivileges backgroundStartPrivileges =
                BackgroundStartPrivileges.allowBackgroundActivityStarts(new Binder());
        final Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        final BroadcastRecord r = new BroadcastRecord(mQueue, intent, callerApp,
                callerApp.info.packageName, null, callerApp.getPid(), callerApp.info.uid, false,
                null, null, null, null, AppOpsManager.OP_NONE, BroadcastOptions.makeBasic(),
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN)), null, null,
                Activity.RESULT_OK, null, null, false, false, false, UserHandle.USER_SYSTEM,
                backgroundStartPrivileges, false, null);
        enqueueBroadcast(r);

        waitForIdle();
        verify(receiverApp).addOrUpdateBackgroundStartPrivileges(eq(r),
                eq(backgroundStartPrivileges));
        verify(receiverApp).removeBackgroundStartPrivileges(eq(r));
    }

    @Test
    public void testOptions_TemporaryAppAllowlist() throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);
        final ProcessRecord receiverApp = makeActiveProcessRecord(PACKAGE_GREEN);

        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        final BroadcastOptions options = BroadcastOptions.makeBasic();
        options.setTemporaryAppAllowlist(1_000,
                PowerExemptionManager.TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED,
                PowerExemptionManager.REASON_VPN, TAG);
        enqueueBroadcast(makeBroadcastRecord(airplane, callerApp, options,
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN))));

        waitForIdle();
        verify(mAms).tempAllowlistUidLocked(eq(receiverApp.uid), eq(1_000L),
                eq(options.getTemporaryAppAllowlistReasonCode()), any(),
                eq(options.getTemporaryAppAllowlistType()), eq(callerApp.uid));
    }

    /**
     * Verify that sending broadcasts to the {@code system} process are handled
     * as a singleton process.
     */
    @Test
    public void testSystemSingleton() throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_PHONE);
        final ProcessRecord systemApp = makeActiveProcessRecord(PACKAGE_ANDROID, PROCESS_SYSTEM,
                ProcessBehavior.NORMAL, USER_SYSTEM);

        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        enqueueBroadcast(makeBroadcastRecord(airplane, callerApp, USER_SYSTEM,
                List.of(makeManifestReceiver(PACKAGE_ANDROID, PROCESS_SYSTEM,
                        CLASS_GREEN, USER_SYSTEM))));
        enqueueBroadcast(makeBroadcastRecord(airplane, callerApp, USER_GUEST,
                List.of(makeManifestReceiver(PACKAGE_ANDROID, PROCESS_SYSTEM,
                        CLASS_GREEN, USER_GUEST))));
        waitForIdle();

        // Confirm we dispatched both users to same singleton instance
        verifyScheduleReceiver(times(1), systemApp, airplane, USER_SYSTEM);
        verifyScheduleReceiver(times(1), systemApp, airplane, USER_GUEST);
    }

    /**
     * Verify that when dispatching we respect tranches of priority.
     */
    @Test
    public void testPriority() throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);
        final ProcessRecord receiverBlueApp = makeActiveProcessRecord(PACKAGE_BLUE);
        final ProcessRecord receiverGreenApp = makeActiveProcessRecord(PACKAGE_GREEN);
        final ProcessRecord receiverYellowApp = makeActiveProcessRecord(PACKAGE_YELLOW);

        // Enqueue a normal broadcast that will go to several processes, and
        // then enqueue a foreground broadcast that risks reordering
        final Intent timezone = new Intent(Intent.ACTION_TIMEZONE_CHANGED);
        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        airplane.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        enqueueBroadcast(makeBroadcastRecord(timezone, callerApp,
                List.of(makeRegisteredReceiver(receiverBlueApp, 10),
                        makeRegisteredReceiver(receiverGreenApp, 10),
                        makeManifestReceiver(PACKAGE_BLUE, CLASS_BLUE),
                        makeManifestReceiver(PACKAGE_YELLOW, CLASS_YELLOW),
                        makeRegisteredReceiver(receiverYellowApp, -10))));
        enqueueBroadcast(makeBroadcastRecord(airplane, callerApp,
                List.of(makeRegisteredReceiver(receiverBlueApp))));
        waitForIdle();

        // Ignore the final foreground broadcast
        mScheduledBroadcasts.remove(makeScheduledBroadcast(receiverBlueApp, airplane));
        assertEquals(5, mScheduledBroadcasts.size());

        // We're only concerned about enforcing ordering between tranches;
        // within a tranche we're okay with reordering
        assertEquals(
                Set.of(makeScheduledBroadcast(receiverBlueApp, timezone),
                        makeScheduledBroadcast(receiverGreenApp, timezone)),
                Set.of(mScheduledBroadcasts.remove(0),
                        mScheduledBroadcasts.remove(0)));
        assertEquals(
                Set.of(makeScheduledBroadcast(receiverBlueApp, timezone),
                        makeScheduledBroadcast(receiverYellowApp, timezone)),
                Set.of(mScheduledBroadcasts.remove(0),
                        mScheduledBroadcasts.remove(0)));
        assertEquals(
                Set.of(makeScheduledBroadcast(receiverYellowApp, timezone)),
                Set.of(mScheduledBroadcasts.remove(0)));
    }

    /**
     * Verify prioritized receivers work as expected with deferrable broadcast - broadcast to
     * app in cached state should be deferred and the rest should be delivered as per the priority
     * order.
     */
    @Test
    public void testPrioritized_withDeferrableBroadcasts() throws Exception {
        // Legacy stack doesn't support deferral
        Assume.assumeTrue(mImpl == Impl.MODERN);

        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);
        final ProcessRecord receiverGreenApp = makeActiveProcessRecord(PACKAGE_GREEN);
        final ProcessRecord receiverBlueApp = makeActiveProcessRecord(PACKAGE_BLUE);
        final ProcessRecord receiverYellowApp = makeActiveProcessRecord(PACKAGE_YELLOW);
        final ProcessRecord receiverOrangeApp = makeActiveProcessRecord(PACKAGE_ORANGE);

        mUidObserver.onUidCachedChanged(getUidForPackage(PACKAGE_GREEN), true);
        mUidObserver.onUidCachedChanged(getUidForPackage(PACKAGE_BLUE), true);

        final Intent timeTick = new Intent(Intent.ACTION_TIME_TICK);
        final BroadcastOptions opts = BroadcastOptions.makeBasic()
                .setDeferralPolicy(BroadcastOptions.DEFERRAL_POLICY_UNTIL_ACTIVE);
        final List receivers = List.of(
                makeRegisteredReceiver(callerApp, 10),
                makeRegisteredReceiver(receiverGreenApp, 9),
                makeRegisteredReceiver(receiverBlueApp, 8),
                makeRegisteredReceiver(receiverYellowApp, 8),
                makeRegisteredReceiver(receiverOrangeApp, 7)
        );
        enqueueBroadcast(makeBroadcastRecord(timeTick, callerApp, opts, receivers));
        waitForIdle();

        // Green ignored since it's in cached state
        verifyScheduleRegisteredReceiver(never(), receiverGreenApp, timeTick);
        // Blue ignored since it's in cached state
        verifyScheduleRegisteredReceiver(never(), receiverBlueApp, timeTick);

        final IApplicationThread redThread = mAms.getProcessRecordLocked(PACKAGE_RED,
                getUidForPackage(PACKAGE_RED)).getThread();
        final IApplicationThread yellowThread = mAms.getProcessRecordLocked(PACKAGE_YELLOW,
                getUidForPackage(PACKAGE_YELLOW)).getThread();
        final IApplicationThread orangeThread = mAms.getProcessRecordLocked(PACKAGE_ORANGE,
                getUidForPackage(PACKAGE_ORANGE)).getThread();

        // Verify apps that are not in cached state will receive the broadcast in the order
        // we expect.
        final InOrder inOrder = inOrder(redThread, yellowThread, orangeThread);
        inOrder.verify(redThread).scheduleRegisteredReceiver(
                any(), argThat(filterEqualsIgnoringComponent(timeTick)),
                anyInt(), any(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
                eq(UserHandle.USER_SYSTEM), anyInt(), anyInt(), any());
        inOrder.verify(yellowThread).scheduleRegisteredReceiver(
                any(), argThat(filterEqualsIgnoringComponent(timeTick)),
                anyInt(), any(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
                eq(UserHandle.USER_SYSTEM), anyInt(), anyInt(), any());
        inOrder.verify(orangeThread).scheduleRegisteredReceiver(
                any(), argThat(filterEqualsIgnoringComponent(timeTick)),
                anyInt(), any(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
                eq(UserHandle.USER_SYSTEM), anyInt(), anyInt(), any());

        // Shift blue to be active and confirm that deferred broadcast is delivered
        mUidObserver.onUidCachedChanged(getUidForPackage(PACKAGE_BLUE), false);
        waitForIdle();
        verifyScheduleRegisteredReceiver(times(1), receiverBlueApp, timeTick);

        // Shift green to be active and confirm that deferred broadcast is delivered
        mUidObserver.onUidCachedChanged(getUidForPackage(PACKAGE_GREEN), false);
        waitForIdle();
        verifyScheduleRegisteredReceiver(times(1), receiverGreenApp, timeTick);
    }

    /**
     * Verify that we handle replacing a pending broadcast.
     */
    @Test
    public void testReplacePending() throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);
        final IApplicationThread callerThread = callerApp.getThread();

        final Intent timezoneFirst = new Intent(Intent.ACTION_TIMEZONE_CHANGED);
        timezoneFirst.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        timezoneFirst.putExtra(Intent.EXTRA_TIMEZONE, "GMT+5");
        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        final Intent timezoneSecond = new Intent(Intent.ACTION_TIMEZONE_CHANGED);
        timezoneSecond.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        timezoneSecond.putExtra(Intent.EXTRA_TIMEZONE, "GMT-5");

        final IIntentReceiver resultToFirst = mock(IIntentReceiver.class);
        final IIntentReceiver resultToSecond = mock(IIntentReceiver.class);

        enqueueBroadcast(makeOrderedBroadcastRecord(timezoneFirst, callerApp,
                List.of(makeManifestReceiver(PACKAGE_BLUE, CLASS_BLUE),
                        makeManifestReceiver(PACKAGE_BLUE, CLASS_GREEN)),
                resultToFirst, null));
        enqueueBroadcast(makeBroadcastRecord(airplane, callerApp,
                List.of(makeManifestReceiver(PACKAGE_BLUE, CLASS_RED))));
        enqueueBroadcast(makeOrderedBroadcastRecord(timezoneSecond, callerApp,
                List.of(makeManifestReceiver(PACKAGE_BLUE, CLASS_GREEN)),
                resultToSecond, null));

        waitForIdle();
        final IApplicationThread blueThread = mAms.getProcessRecordLocked(PACKAGE_BLUE,
                getUidForPackage(PACKAGE_BLUE)).getThread();
        final InOrder inOrder = inOrder(callerThread, blueThread);

        // First broadcast is canceled
        inOrder.verify(callerThread).scheduleRegisteredReceiver(
                any(), argThat(filterAndExtrasEquals(timezoneFirst)),
                eq(Activity.RESULT_CANCELED), any(), any(),
                eq(false), anyBoolean(), eq(true), eq(UserHandle.USER_SYSTEM),
                anyInt(), anyInt(), any());

        // We deliver second broadcast to app
        timezoneSecond.setClassName(PACKAGE_BLUE, CLASS_GREEN);
        inOrder.verify(blueThread).scheduleReceiver(
                argThat(filterAndExtrasEquals(timezoneSecond)), any(), any(),
                anyInt(), any(), any(), eq(true), eq(false), anyInt(),
                anyInt(), anyInt(), any());

        // Second broadcast is finished
        timezoneSecond.setComponent(null);
        inOrder.verify(callerThread).scheduleRegisteredReceiver(
                any(), argThat(filterAndExtrasEquals(timezoneSecond)),
                eq(Activity.RESULT_OK), any(), any(),
                eq(false), anyBoolean(), eq(true), eq(UserHandle.USER_SYSTEM),
                anyInt(), anyInt(), any());

        // Since we "replaced" the first broadcast in its original position,
        // only now do we see the airplane broadcast
        airplane.setClassName(PACKAGE_BLUE, CLASS_RED);
        inOrder.verify(blueThread).scheduleReceiver(
                argThat(filterEquals(airplane)), any(), any(),
                anyInt(), any(), any(), eq(false), eq(false), anyInt(),
                anyInt(), anyInt(), any());
    }

    @Test
    public void testReplacePending_withPrioritizedBroadcasts() throws Exception {
        mConstants.MAX_RUNNING_ACTIVE_BROADCASTS = 1;
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_GREEN);

        final Intent userPresent = new Intent(Intent.ACTION_USER_PRESENT)
                .addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);

        final List receivers = List.of(
                withPriority(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN), 100),
                withPriority(makeManifestReceiver(PACKAGE_GREEN, CLASS_RED), 50),
                withPriority(makeManifestReceiver(PACKAGE_GREEN, CLASS_YELLOW), 10),
                withPriority(makeManifestReceiver(PACKAGE_GREEN, CLASS_BLUE), 0));

        // Enqueue the broadcast a few times and verify that broadcast queues are not stuck
        // and are emptied eventually.
        for (int i = 0; i < 6; ++i) {
            enqueueBroadcast(makeBroadcastRecord(userPresent, callerApp, receivers));
        }
        waitForIdle();
    }

    @Test
    public void testReplacePending_withUrgentBroadcast() throws Exception {
        // The behavior is same with the legacy queue but AMS takes care of finding
        // the right queue and replacing the broadcast.
        Assume.assumeTrue(mImpl == Impl.MODERN);

        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);

        final Intent timeTickFirst = new Intent(Intent.ACTION_TIME_TICK);
        timeTickFirst.putExtra(Intent.EXTRA_INDEX, "one");
        timeTickFirst.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        timeTickFirst.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);

        final Intent timeTickSecond = new Intent(Intent.ACTION_TIME_TICK);
        timeTickFirst.putExtra(Intent.EXTRA_INDEX, "second");
        timeTickSecond.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);

        final Intent timeTickThird = new Intent(Intent.ACTION_TIME_TICK);
        timeTickFirst.putExtra(Intent.EXTRA_INDEX, "third");
        timeTickThird.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);

        enqueueBroadcast(makeBroadcastRecord(timeTickFirst, callerApp,
                List.of(makeManifestReceiver(PACKAGE_BLUE, CLASS_BLUE))));
        enqueueBroadcast(makeBroadcastRecord(timeTickSecond, callerApp,
                List.of(makeManifestReceiver(PACKAGE_BLUE, CLASS_BLUE))));
        enqueueBroadcast(makeBroadcastRecord(timeTickThird, callerApp,
                List.of(makeManifestReceiver(PACKAGE_BLUE, CLASS_BLUE))));

        waitForIdle();
        final IApplicationThread blueThread = mAms.getProcessRecordLocked(PACKAGE_BLUE,
                getUidForPackage(PACKAGE_BLUE)).getThread();
        final InOrder inOrder = inOrder(blueThread);

        // First broadcast is delivered.
        timeTickFirst.setClassName(PACKAGE_BLUE, CLASS_BLUE);
        inOrder.verify(blueThread).scheduleReceiver(
                argThat(filterAndExtrasEquals(timeTickFirst)), any(), any(),
                anyInt(), any(), any(), eq(false), eq(false), anyInt(),
                anyInt(), anyInt(), any());

        // Second broadcast should be replaced by third broadcast.
        timeTickThird.setClassName(PACKAGE_BLUE, CLASS_BLUE);
        inOrder.verify(blueThread).scheduleReceiver(
                argThat(filterAndExtrasEquals(timeTickThird)), any(), any(),
                anyInt(), any(), any(), eq(false), eq(false), anyInt(),
                anyInt(), anyInt(), any());
    }

    @Test
    public void testIdleAndBarrier() throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);
        final ProcessRecord receiverApp = makeActiveProcessRecord(PACKAGE_GREEN);

        final long beforeFirst;
        final long afterFirst;
        final long afterSecond;

        beforeFirst = SystemClock.uptimeMillis() - 10;
        assertTrue(mQueue.isIdleLocked());
        assertTrue(mQueue.isBeyondBarrierLocked(beforeFirst));

        final Intent timezone = new Intent(Intent.ACTION_TIMEZONE_CHANGED);
        enqueueBroadcast(makeBroadcastRecord(timezone, callerApp,
                List.of(makeRegisteredReceiver(receiverApp))));
        afterFirst = SystemClock.uptimeMillis();

        assertFalse(mQueue.isIdleLocked());
        assertTrue(mQueue.isBeyondBarrierLocked(beforeFirst));
        assertFalse(mQueue.isBeyondBarrierLocked(afterFirst));

        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        enqueueBroadcast(makeBroadcastRecord(airplane, callerApp,
                List.of(makeRegisteredReceiver(receiverApp))));
        afterSecond = SystemClock.uptimeMillis() + 10;

        assertFalse(mQueue.isIdleLocked());
        assertTrue(mQueue.isBeyondBarrierLocked(beforeFirst));
        assertFalse(mQueue.isBeyondBarrierLocked(afterFirst));
        assertFalse(mQueue.isBeyondBarrierLocked(afterSecond));

        mLooper.release();

        mQueue.waitForBarrier(LOG_WRITER_INFO);
        assertTrue(mQueue.isBeyondBarrierLocked(afterFirst));

        mQueue.waitForIdle(LOG_WRITER_INFO);
        assertTrue(mQueue.isIdleLocked());
        assertTrue(mQueue.isBeyondBarrierLocked(beforeFirst));
        assertTrue(mQueue.isBeyondBarrierLocked(afterFirst));
        assertTrue(mQueue.isBeyondBarrierLocked(afterSecond));
    }

    @Test
    public void testWaitForBroadcastDispatch() throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);
        final ProcessRecord receiverApp = makeActiveProcessRecord(PACKAGE_GREEN);

        final Intent timeTick = new Intent(Intent.ACTION_TIME_TICK);
        assertTrue(mQueue.isDispatchedLocked(timeTick));

        final Intent timezone = new Intent(Intent.ACTION_TIMEZONE_CHANGED);
        enqueueBroadcast(makeBroadcastRecord(timezone, callerApp,
                List.of(makeRegisteredReceiver(receiverApp))));

        assertTrue(mQueue.isDispatchedLocked(timeTick));
        assertFalse(mQueue.isDispatchedLocked(timezone));

        enqueueBroadcast(makeBroadcastRecord(timeTick, callerApp,
                List.of(makeRegisteredReceiver(receiverApp))));

        assertFalse(mQueue.isDispatchedLocked(timeTick));
        assertFalse(mQueue.isDispatchedLocked(timezone));

        mLooper.release();

        mQueue.waitForDispatched(timeTick, LOG_WRITER_INFO);
        assertTrue(mQueue.isDispatchedLocked(timeTick));

        mQueue.waitForDispatched(timezone, LOG_WRITER_INFO);
        assertTrue(mQueue.isDispatchedLocked(timezone));
    }

    /**
     * Verify that we OOM adjust for manifest receivers.
     */
    @Test
    public void testOomAdjust_Manifest() throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);

        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        enqueueBroadcast(makeBroadcastRecord(airplane, callerApp,
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN),
                        makeManifestReceiver(PACKAGE_GREEN, CLASS_BLUE),
                        makeManifestReceiver(PACKAGE_GREEN, CLASS_RED))));

        waitForIdle();
        verify(mAms, atLeastOnce()).enqueueOomAdjTargetLocked(any());
    }

    /**
     * Verify that we OOM adjust for ordered broadcast receivers.
     */
    @Test
    public void testOomAdjust_Ordered() throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);

        final IIntentReceiver orderedResultTo = mock(IIntentReceiver.class);
        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        enqueueBroadcast(makeOrderedBroadcastRecord(airplane, callerApp,
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN),
                        makeManifestReceiver(PACKAGE_GREEN, CLASS_BLUE),
                        makeManifestReceiver(PACKAGE_GREEN, CLASS_RED)), orderedResultTo, null));

        waitForIdle();
        verify(mAms, atLeastOnce()).enqueueOomAdjTargetLocked(any());
    }

    /**
     * Verify that we OOM adjust for resultTo broadcast receivers.
     */
    @Test
    public void testOomAdjust_ResultTo() throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);

        final IIntentReceiver resultTo = mock(IIntentReceiver.class);
        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        enqueueBroadcast(makeBroadcastRecord(airplane, callerApp,
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN),
                        makeManifestReceiver(PACKAGE_GREEN, CLASS_BLUE),
                        makeManifestReceiver(PACKAGE_GREEN, CLASS_RED)), resultTo));

        waitForIdle();
        verify(mAms, atLeastOnce()).enqueueOomAdjTargetLocked(any());
    }

    /**
     * Verify that we never OOM adjust for registered receivers.
     */
    @Test
    public void testOomAdjust_Registered() throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);
        final ProcessRecord receiverApp = makeActiveProcessRecord(PACKAGE_GREEN);

        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        enqueueBroadcast(makeBroadcastRecord(airplane, callerApp,
                List.of(makeRegisteredReceiver(receiverApp),
                        makeRegisteredReceiver(receiverApp),
                        makeRegisteredReceiver(receiverApp))));

        waitForIdle();
        verify(mAms, never()).enqueueOomAdjTargetLocked(any());
    }

    /**
     * Confirm how many times a pathological broadcast pattern results in OOM
     * adjusts; watches for performance regressions.
     */
    @Test
    public void testOomAdjust_TriggerCount() throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);

        // Send 8 broadcasts, 4 receivers in the first process,
        // and 2 alternating in each of the remaining processes
        synchronized (mAms) {
            for (int i = 0; i < 8; i++) {
                final Intent intent = new Intent(Intent.ACTION_TIMEZONE_CHANGED);
                mQueue.enqueueBroadcastLocked(makeBroadcastRecord(intent, callerApp,
                        List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN),
                                makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN),
                                makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN),
                                makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN),
                                makeManifestReceiver(PACKAGE_BLUE, CLASS_BLUE),
                                makeManifestReceiver(PACKAGE_YELLOW, CLASS_YELLOW),
                                makeManifestReceiver(PACKAGE_BLUE, CLASS_BLUE),
                                makeManifestReceiver(PACKAGE_YELLOW, CLASS_YELLOW))));
            }
        }
        waitForIdle();

        final int expectedTimes;
        switch (mImpl) {
            // Original stack requested for every single receiver; yikes
            case DEFAULT: expectedTimes = 64; break;
            // Modern stack requests once each time we promote a process to
            // running; we promote "green" twice, and "blue" and "yellow" once
            case MODERN: expectedTimes = 4; break;
            default: throw new UnsupportedOperationException();
        }

        verify(mAms, times(expectedTimes))
                .updateOomAdjPendingTargetsLocked(eq(OOM_ADJ_REASON_START_RECEIVER));
    }

    /**
     * Verify that expected events are triggered when a broadcast is finished.
     */
    @Test
    public void testNotifyFinished() throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);

        final Intent intent = new Intent(Intent.ACTION_TIMEZONE_CHANGED);
        final BroadcastRecord record = makeBroadcastRecord(intent, callerApp,
                List.of(makeManifestReceiver(PACKAGE_GREEN, CLASS_GREEN)));
        enqueueBroadcast(record);

        waitForIdle();
        verify(mAms).notifyBroadcastFinishedLocked(eq(record));
        verify(mAms).addBroadcastStatLocked(eq(Intent.ACTION_TIMEZONE_CHANGED), eq(PACKAGE_RED),
                eq(1), eq(0), anyLong());
    }

    /**
     * Verify that we skip broadcasts if {@link BroadcastSkipPolicy} decides it should be skipped.
     */
    @Test
    public void testSkipPolicy() throws Exception {
        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);
        final ProcessRecord receiverGreenApp = makeActiveProcessRecord(PACKAGE_GREEN);
        final ProcessRecord receiverBlueApp = makeActiveProcessRecord(PACKAGE_BLUE);

        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        final Object greenReceiver = makeRegisteredReceiver(receiverGreenApp);
        final Object blueReceiver = makeRegisteredReceiver(receiverBlueApp);
        final Object yellowReceiver = makeManifestReceiver(PACKAGE_YELLOW, CLASS_YELLOW);
        final Object orangeReceiver = makeManifestReceiver(PACKAGE_ORANGE, CLASS_ORANGE);
        enqueueBroadcast(makeBroadcastRecord(airplane, callerApp,
                List.of(greenReceiver, blueReceiver, yellowReceiver, orangeReceiver)));

        doAnswer(invocation -> {
            final BroadcastRecord r = invocation.getArgument(0);
            final Object o = invocation.getArgument(1);
            if (airplane.getAction().equals(r.intent.getAction())
                    && (isReceiverEquals(o, greenReceiver)
                            || isReceiverEquals(o, orangeReceiver))) {
                return "test skipped receiver";
            }
            return null;
        }).when(mSkipPolicy).shouldSkipMessage(any(BroadcastRecord.class), any());

        waitForIdle();
        // Verify that only blue and yellow receiver apps received the broadcast.
        verifyScheduleRegisteredReceiver(never(), receiverGreenApp, USER_SYSTEM);
        verifyScheduleRegisteredReceiver(receiverBlueApp, airplane);
        final ProcessRecord receiverYellowApp = mAms.getProcessRecordLocked(PACKAGE_YELLOW,
                getUidForPackage(PACKAGE_YELLOW));
        verifyScheduleReceiver(receiverYellowApp, airplane);
        final ProcessRecord receiverOrangeApp = mAms.getProcessRecordLocked(PACKAGE_ORANGE,
                getUidForPackage(PACKAGE_ORANGE));
        assertNull(receiverOrangeApp);
    }

    /**
     * Verify broadcasts to runtime receivers in cached processes are deferred
     * until that process leaves the cached state.
     */
    @Test
    public void testDeferralPolicy_UntilActive() throws Exception {
        // Legacy stack doesn't support deferral
        Assume.assumeTrue(mImpl == Impl.MODERN);

        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);
        final ProcessRecord receiverGreenApp = makeActiveProcessRecord(PACKAGE_GREEN);
        final ProcessRecord receiverBlueApp = makeActiveProcessRecord(PACKAGE_BLUE);
        final ProcessRecord receiverYellowApp = makeActiveProcessRecord(PACKAGE_YELLOW);

        mUidObserver.onUidCachedChanged(getUidForPackage(PACKAGE_GREEN), true);
        mUidObserver.onUidCachedChanged(getUidForPackage(PACKAGE_BLUE), true);
        mUidObserver.onUidCachedChanged(getUidForPackage(PACKAGE_YELLOW), false);

        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        final BroadcastOptions opts = BroadcastOptions.makeBasic()
                .setDeferralPolicy(BroadcastOptions.DEFERRAL_POLICY_UNTIL_ACTIVE);
        enqueueBroadcast(makeBroadcastRecord(airplane, callerApp, opts,
                List.of(makeRegisteredReceiver(receiverGreenApp),
                        makeRegisteredReceiver(receiverBlueApp),
                        makeManifestReceiver(PACKAGE_BLUE, CLASS_BLUE),
                        makeRegisteredReceiver(receiverYellowApp))));
        waitForIdle();

        // Green ignored since it's in cached state
        verifyScheduleRegisteredReceiver(never(), receiverGreenApp, airplane);

        // Blue delivered both since it has a manifest receiver
        verifyScheduleReceiver(times(1), receiverBlueApp, airplane);
        verifyScheduleRegisteredReceiver(times(1), receiverBlueApp, airplane);

        // Yellow delivered since it's not cached
        verifyScheduleRegisteredReceiver(times(1), receiverYellowApp, airplane);

        // Shift green to be active and confirm that deferred broadcast is delivered
        mUidObserver.onUidCachedChanged(getUidForPackage(PACKAGE_GREEN), false);
        waitForIdle();
        verifyScheduleRegisteredReceiver(times(1), receiverGreenApp, airplane);
    }

    @Test
    public void testBroadcastDelivery_uidForeground() throws Exception {
        // Legacy stack doesn't support prioritization to foreground app.
        Assume.assumeTrue(mImpl == Impl.MODERN);

        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);
        final ProcessRecord receiverBlueApp = makeActiveProcessRecord(PACKAGE_BLUE);
        final ProcessRecord receiverGreenApp = makeActiveProcessRecord(PACKAGE_GREEN);

        mUidObserver.onUidStateChanged(receiverGreenApp.info.uid,
                ActivityManager.PROCESS_STATE_TOP, 0, ActivityManager.PROCESS_CAPABILITY_NONE);

        final Intent airplane = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        final Intent timeTick = new Intent(Intent.ACTION_TIME_TICK);

        final BroadcastFilter receiverBlue = makeRegisteredReceiver(receiverBlueApp);
        final BroadcastFilter receiverGreen = makeRegisteredReceiver(receiverGreenApp);
        final BroadcastRecord airplaneRecord = makeBroadcastRecord(airplane, callerApp,
                List.of(receiverBlue));
        final BroadcastRecord timeTickRecord = makeBroadcastRecord(timeTick, callerApp,
                List.of(receiverBlue, receiverGreen));

        enqueueBroadcast(airplaneRecord);
        enqueueBroadcast(timeTickRecord);

        waitForIdle();
        // Verify that broadcasts to receiverGreenApp gets scheduled first.
        assertThat(getReceiverScheduledTime(timeTickRecord, receiverGreen))
                .isLessThan(getReceiverScheduledTime(airplaneRecord, receiverBlue));
        assertThat(getReceiverScheduledTime(timeTickRecord, receiverGreen))
                .isLessThan(getReceiverScheduledTime(timeTickRecord, receiverBlue));
    }

    @Test
    public void testPrioritizedBroadcastDelivery_uidForeground() throws Exception {
        // Legacy stack doesn't support prioritization to foreground app.
        Assume.assumeTrue(mImpl == Impl.MODERN);

        final ProcessRecord callerApp = makeActiveProcessRecord(PACKAGE_RED);
        final ProcessRecord receiverBlueApp = makeActiveProcessRecord(PACKAGE_BLUE);
        final ProcessRecord receiverGreenApp = makeActiveProcessRecord(PACKAGE_GREEN);

        mUidObserver.onUidStateChanged(receiverGreenApp.info.uid,
                ActivityManager.PROCESS_STATE_TOP, 0, ActivityManager.PROCESS_CAPABILITY_NONE);

        final Intent timeTick = new Intent(Intent.ACTION_TIME_TICK);

        final BroadcastFilter receiverBlue = makeRegisteredReceiver(receiverBlueApp, 10);
        final BroadcastFilter receiverGreen = makeRegisteredReceiver(receiverGreenApp, 5);
        final BroadcastRecord prioritizedRecord = makeBroadcastRecord(timeTick, callerApp,
                List.of(receiverBlue, receiverGreen));

        enqueueBroadcast(prioritizedRecord);

        waitForIdle();
        // Verify that uid foreground-ness does not impact that delivery of prioritized broadcast.
        // That is, broadcast to receiverBlueApp gets scheduled before the one to receiverGreenApp.
        assertThat(getReceiverScheduledTime(prioritizedRecord, receiverGreen))
                .isGreaterThan(getReceiverScheduledTime(prioritizedRecord, receiverBlue));
    }

    private long getReceiverScheduledTime(@NonNull BroadcastRecord r, @NonNull Object receiver) {
        for (int i = 0; i < r.receivers.size(); ++i) {
            if (isReceiverEquals(receiver, r.receivers.get(i))) {
                return r.scheduledTime[i];
            }
        }
        fail(receiver + "not found in " + r);
        return -1;
    }
}
