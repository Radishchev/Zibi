package com.zibi.app.bubble

enum class BubblePanelTab {
    Chat,
    Quick,
}

data class BubblePanelState(
    val expanded: Boolean = false,
    val tab: BubblePanelTab = BubblePanelTab.Chat,
) {
    companion object {
        fun open(tab: BubblePanelTab = BubblePanelTab.Chat): BubblePanelState =
            BubblePanelState(expanded = true, tab = tab)
    }

    fun close(): BubblePanelState =
        copy(expanded = false)

    fun select(tab: BubblePanelTab): BubblePanelState =
        copy(tab = tab)
}