package com.android.systemui.biometrics.data.repository

import android.hardware.biometrics.PromptInfo
import com.android.systemui.biometrics.data.model.PromptKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Fake implementation of [PromptRepository] for tests. */
class FakePromptRepository : PromptRepository {

    private val _isShowing = MutableStateFlow(false)
    override val isShowing = _isShowing.asStateFlow()

    private val _promptInfo = MutableStateFlow<PromptInfo?>(null)
    override val promptInfo = _promptInfo.asStateFlow()

    private val _userId = MutableStateFlow<Int?>(null)
    override val userId = _userId.asStateFlow()

    private var _challenge = MutableStateFlow<Long?>(null)
    override val challenge = _challenge.asStateFlow()

    private val _kind = MutableStateFlow(PromptKind.ANY_BIOMETRIC)
    override val kind = _kind.asStateFlow()

    override fun setPrompt(
        promptInfo: PromptInfo,
        userId: Int,
        gatekeeperChallenge: Long?,
        kind: PromptKind
    ) {
        _promptInfo.value = promptInfo
        _userId.value = userId
        _challenge.value = gatekeeperChallenge
        _kind.value = kind
    }

    override fun unsetPrompt() {
        _promptInfo.value = null
        _userId.value = null
        _challenge.value = null
        _kind.value = PromptKind.ANY_BIOMETRIC
    }

    fun setIsShowing(showing: Boolean) {
        _isShowing.value = showing
    }
}
