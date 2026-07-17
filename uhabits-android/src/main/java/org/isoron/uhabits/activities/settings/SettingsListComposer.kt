package org.isoron.uhabits.activities.settings

data class SettingsGroup(
    val id: String,
    val title: String?,
    val isDestructive: Boolean,
    val items: List<SettingItem>
)

data class SettingsSectionModel(
    val id: SettingsSectionId,
    val iconRes: Int,
    val title: String,
    val summary: String,
    val groups: List<SettingsGroup>,
    val homeEndIconRes: Int? = null,
    val homeAction: (() -> Unit)? = null
) {
    companion object {
        fun fromItems(
        id: SettingsSectionId,
        iconRes: Int,
        title: String,
        summary: String,
        items: List<SettingItem>,
        homeEndIconRes: Int? = null,
        homeAction: (() -> Unit)? = null
        ) = SettingsSectionModel(
            id,
            iconRes,
            title,
            summary,
            splitIntoGroups(id, items),
            homeEndIconRes,
            homeAction
        )

        private fun splitIntoGroups(
            sectionId: SettingsSectionId,
            items: List<SettingItem>
        ): List<SettingsGroup> {
            val groups = mutableListOf<SettingsGroup>()
            var groupId = "${sectionId.name.lowercase()}:main"
            var title: String? = null
            var isDestructive = false
            var children = mutableListOf<SettingItem>()

            fun flush() {
                if (children.isEmpty()) return
                groups += SettingsGroup(groupId, title, isDestructive, children)
                children = mutableListOf()
            }

            items.forEach { item ->
                if (item is SettingItem.Subsection) {
                    flush()
                    groupId = item.key
                    title = item.title
                    isDestructive = item.isDestructive
                } else {
                    children += item
                }
            }
            flush()
            return groups
        }
    }
}

typealias SettingsSectionDefinition = SettingsSectionModel

object SettingsListComposer {
    fun composeHome(
        sections: List<SettingsSectionModel>,
        accessibilityDescription: (SettingsSectionModel) -> String,
        onSectionClick: (SettingsSectionId) -> Unit
    ): List<SettingItem> = sections.map { section ->
        SettingItem.Section(
            sectionId = section.id,
            iconRes = section.iconRes,
            endIconRes = section.homeEndIconRes,
            title = section.title,
            summary = section.summary,
            accessibilityDescription = accessibilityDescription(section),
            onClick = section.homeAction ?: { onSectionClick(section.id) }
        )
    }

    fun composeDetail(section: SettingsSectionModel): List<SettingItem> = buildList {
        section.groups.forEach { group ->
            group.title?.let {
                add(
                    SettingItem.Subsection(
                        key = group.id,
                        title = it,
                        isDestructive = group.isDestructive,
                        presentation = SettingPresentation(cardId = group.id, isFirst = true)
                    )
                )
            }
            group.items.forEachIndexed { index, item ->
                add(
                    item.withPresentation(
                        item.presentation.copy(
                            cardId = group.id,
                            showIcon = true,
                            isFirst = group.title == null && index == 0,
                            isLast = index == group.items.lastIndex
                        )
                    )
                )
            }
        }
    }

    private fun SettingItem.withPresentation(value: SettingPresentation): SettingItem = when (this) {
        is SettingItem.Navigation -> copy(presentation = value)
        is SettingItem.Switch -> copy(presentation = value)
        is SettingItem.Value -> copy(presentation = value)
        is SettingItem.SegmentedTheme -> copy(presentation = value)
        is SettingItem.ColorPicker -> copy(presentation = value)
        is SettingItem.Subsection -> copy(presentation = value)
        is SettingItem.Status -> copy(presentation = value)
        is SettingItem.Section -> error("Nested settings sections are not supported")
    }
}
