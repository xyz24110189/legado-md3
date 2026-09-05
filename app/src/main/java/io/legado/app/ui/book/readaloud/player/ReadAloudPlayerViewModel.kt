package io.legado.app.ui.book.readaloud.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.constant.PreferKey
import io.legado.app.constant.ReadAloudBgMode
import io.legado.app.domain.model.readaloud.ReadAloudEngineSelection
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.domain.model.settings.ReadAloudSettings
import io.legado.app.help.config.AppConfigStore
import io.legado.app.help.readaloud.playback.PiperModelManager
import io.legado.app.help.config.compatDsInt
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ReadAloudPlayerViewModel(
    private val coordinator: ReadAloudPlayerCoordinator,
) : ViewModel() {

    val uiState = combine(
        coordinator.state,
        AppConfigStore.observeInt(PreferKey.readAloudPlayerBgMode),
        coordinator.readAloudSettings,
    ) { source, bgMode, aloudSettings ->
        toUiState(
            source = source,
            bgMode = bgMode ?: ReadAloudBgMode.Blur,
            piperModelKey = piperModelKeyOf(aloudSettings),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = toUiState(
            source = coordinator.snapshot(),
            bgMode = readBgMode(),
            piperModelKey = piperModelKeyOf(coordinator.currentReadAloudSettings),
        ),
    )

    private val _effects = MutableSharedFlow<ReadAloudPlayerEffect>(extraBufferCapacity = 8)
    val effects = _effects.asSharedFlow()

    fun onIntent(intent: ReadAloudPlayerIntent) {
        when (intent) {
            ReadAloudPlayerIntent.Refresh -> coordinator.refresh()
            ReadAloudPlayerIntent.TogglePause -> coordinator.togglePause()
            ReadAloudPlayerIntent.PreviousParagraph -> coordinator.previousParagraph()
            ReadAloudPlayerIntent.NextParagraph -> coordinator.nextParagraph()
            ReadAloudPlayerIntent.PreviousChapter -> coordinator.previousChapter()
            ReadAloudPlayerIntent.NextChapter -> coordinator.nextChapter()
            ReadAloudPlayerIntent.OpenSettings -> effect(ReadAloudPlayerEffect.ReturnToReaderSettings)
            ReadAloudPlayerIntent.SwitchToClassic -> effect(ReadAloudPlayerEffect.ReturnToClassic)
            ReadAloudPlayerIntent.CycleBgMode -> cycleBgMode()
            is ReadAloudPlayerIntent.SelectChapter -> coordinator.selectChapter(intent.index)
            is ReadAloudPlayerIntent.SetBgMode -> AppConfigStore.putInt(
                PreferKey.readAloudPlayerBgMode,
                intent.value,
            )
            is ReadAloudPlayerIntent.SetPiperModel -> viewModelScope.launch {
                coordinator.setPiperModel(intent.modelKey)
            }
            is ReadAloudPlayerIntent.SetSpeed -> viewModelScope.launch {
                coordinator.setSpeed(intent.value)
            }
            is ReadAloudPlayerIntent.SetTimer -> viewModelScope.launch {
                coordinator.setTimer(intent.minutes)
            }
            is ReadAloudPlayerIntent.SetFinishCurrentChapterAfterTimer ->
                viewModelScope.launch {
                    coordinator.setFinishCurrentChapterAfterTimer(intent.value)
                }
            is ReadAloudPlayerIntent.SeekTo -> coordinator.seekTo(
                chapterPosition = intent.chapterPosition,
                chapterLength = uiState.value.chapterLength,
            )
        }
    }

    private fun cycleBgMode() {
        val next = when (readBgMode()) {
            ReadAloudBgMode.Solid -> ReadAloudBgMode.Blur
            ReadAloudBgMode.Blur -> ReadAloudBgMode.FlowingLight
            ReadAloudBgMode.FlowingLight -> ReadAloudBgMode.Transparent
            else -> ReadAloudBgMode.Solid
        }
        AppConfigStore.putInt(PreferKey.readAloudPlayerBgMode, next)
    }

    private fun toUiState(
        source: ReadAloudPlayerSourceState,
        bgMode: Int,
        piperModelKey: String,
    ): ReadAloudPlayerUiState {
        val activeIndex = source.textLines.indexOfLast {
            it.chapterPosition <= source.chapterPosition
        }
        val chapters = source.chapters.map { chapter ->
            ReadAloudChapterUi(
                index = chapter.index,
                title = chapter.title,
                isVolume = chapter.isVolume,
                tocLevel = chapter.tocLevel,
            )
        }.toImmutableList()
        return ReadAloudPlayerUiState(
            bookUrl = source.bookUrl,
            bookName = source.bookName,
            author = source.author,
            coverPath = source.coverPath,
            sourceOrigin = source.sourceOrigin,
            chapterIndex = source.chapterIndex,
            chapterTitle = source.chapterTitle,
            chapters = chapters,
            chapterText = source.chapterText,
            textLines = source.textLines,
            activeTextLine = activeIndex,
            currentText = source.textLines.getOrNull(activeIndex)?.text ?: source.playbackText,
            nextText = source.textLines.getOrNull(activeIndex + 1)?.text.orEmpty(),
            chapterPosition = source.chapterPosition,
            chapterLength = source.chapterLength,
            engineName = source.engineName,
            speakerName = source.speakerName,
            piperModelKey = piperModelKey,
            isPaused = source.isPaused,
            speed = source.speed,
            timerMinutes = source.timerMinutes,
            finishCurrentChapterAfterTimer = source.finishCurrentChapterAfterTimer,
            bgMode = bgMode,
        )
    }

    private fun effect(value: ReadAloudPlayerEffect) {
        _effects.tryEmit(value)
    }

    private fun readBgMode(): Int {
        return AppConfigStore.preferences.compatDsInt(PreferKey.readAloudPlayerBgMode)
            ?: ReadAloudBgMode.Blur
    }

    /**
     * 从朗读设置里解析当前选中的 Piper 模型。
     * 选中态的唯一事实来源是 [io.legado.app.domain.model.settings.ReadAloudSettings.ttsEngine]
     * （与合成路由一致），非 Piper 引擎或未设置时回退默认模型。
     */
    private fun piperModelKeyOf(aloudSettings: ReadAloudSettings): String {
        return aloudSettings.ttsEngine
            ?.let { GSON.fromJsonObject<ReadAloudEngineSelection>(it).getOrNull() }
            ?.takeIf { it.engineType == ReadAloudVoice.ENGINE_PIPER }
            ?.engineId
            // 已下架/不存在的模型 key 回退默认，避免列表无高亮项
            ?.takeIf { PiperModelManager.isSupported(it) }
            ?: PiperModelManager.MODELS.first().key
    }

}
