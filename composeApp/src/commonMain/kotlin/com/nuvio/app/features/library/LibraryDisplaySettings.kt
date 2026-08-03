package com.nuvio.app.features.library

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class LibraryLayoutMode {
    HORIZONTAL,
    VERTICAL,
}

enum class LibrarySortOption {
    DEFAULT,
    ADDED_DESC,
    ADDED_ASC,
    TITLE_ASC,
    TITLE_DESC,
    RATING_DESC,
    RATING_ASC,
    RELEASE_DATE_DESC,
    RELEASE_DATE_ASC,
    LAST_WATCHED_DESC,
    SHELF_GROUP,
}

enum class LibraryViewFilterMode {
    EXCLUSIVE,
    INCLUSIVE,
}

data class LibraryDisplaySettingsUiState(
    val layoutMode: LibraryLayoutMode = LibraryLayoutMode.HORIZONTAL,
    val savedSortOption: LibrarySortOption = LibrarySortOption.ADDED_DESC,
    val shelvesSortOption: LibrarySortOption = LibrarySortOption.DEFAULT,
    val selectedShelfKey: String? = null,
    val viewFilterMode: LibraryViewFilterMode = LibraryViewFilterMode.INCLUSIVE,
    val showPrivateVault: Boolean = false,
    val showNSFW: Boolean = false,
) {
    val sortOption: LibrarySortOption
        get() = savedSortOption
}

object LibraryDisplaySettingsRepository {
    private val _uiState = MutableStateFlow(LibraryDisplaySettingsUiState())
    val uiState: StateFlow<LibraryDisplaySettingsUiState> = _uiState.asStateFlow()

    private var hasLoaded = false

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        loadFromDisk()
    }

    fun clearLocalState() {
        hasLoaded = false
        _uiState.value = LibraryDisplaySettingsUiState()
    }

    fun setLayoutMode(layoutMode: LibraryLayoutMode) {
        ensureLoaded()
        if (_uiState.value.layoutMode == layoutMode) return
        _uiState.value = _uiState.value.copy(layoutMode = layoutMode)
        persist()
    }

    fun setSelectedShelfKey(shelfKey: String?) {
        ensureLoaded()
        if (_uiState.value.selectedShelfKey == shelfKey) return
        _uiState.value = _uiState.value.copy(selectedShelfKey = shelfKey)
        persist()
    }

    fun setSortOption(sortOption: LibrarySortOption, isSavedMode: Boolean = true) {
        ensureLoaded()
        val current = _uiState.value
        val updated = current.copy(savedSortOption = sortOption, shelvesSortOption = sortOption)
        if (current == updated) return
        _uiState.value = updated
        persist()
    }

    fun setViewFilterMode(viewFilterMode: LibraryViewFilterMode) {
        ensureLoaded()
        if (_uiState.value.viewFilterMode == viewFilterMode) return
        _uiState.value = _uiState.value.copy(viewFilterMode = viewFilterMode)
        persist()
    }

    fun setShowPrivateVault(show: Boolean) {
        ensureLoaded()
        if (_uiState.value.showPrivateVault == show) return
        _uiState.value = _uiState.value.copy(showPrivateVault = show)
        persist()
    }

    fun setShowNSFW(show: Boolean) {
        ensureLoaded()
        if (_uiState.value.showNSFW == show) return
        _uiState.value = _uiState.value.copy(showNSFW = show)
        persist()
    }

    fun togglePrivateVault() {
        ensureLoaded()
        _uiState.value = _uiState.value.copy(showPrivateVault = !_uiState.value.showPrivateVault)
        persist()
    }

    private fun loadFromDisk() {
        hasLoaded = true
        _uiState.value = decodeLibraryDisplaySettings(LibraryDisplaySettingsStorage.loadPayload())
    }

    private fun persist() {
        LibraryDisplaySettingsStorage.savePayload(encodeLibraryDisplaySettings(_uiState.value))
    }
}

internal data class LibraryVerticalEntry(
    val item: LibraryItem,
    val section: LibrarySection,
)

internal data class LibraryVerticalProjection(
    val availableSections: List<LibrarySection>,
    val selectedSectionKey: String?,
    val availableTypes: List<String>,
    val selectedType: String?,
    val entries: List<LibraryVerticalEntry>,
)

internal fun availableLibrarySortOptions(sourceMode: LibrarySourceMode): List<LibrarySortOption> =
    if (sourceMode.isRemoteTrackingSource) {
        LibrarySortOption.entries.filterNot { it == LibrarySortOption.SHELF_GROUP }
    } else {
        LibrarySortOption.entries.filterNot { it == LibrarySortOption.DEFAULT || it == LibrarySortOption.SHELF_GROUP }
    }

internal fun effectiveLibrarySortOption(
    selected: LibrarySortOption,
    sourceMode: LibrarySourceMode,
): LibrarySortOption =
    if (selected == LibrarySortOption.DEFAULT && sourceMode == LibrarySourceMode.LOCAL) {
        LibrarySortOption.ADDED_DESC
    } else {
        selected
    }

internal fun sortLibraryItems(
    items: List<LibraryItem>,
    selected: LibrarySortOption,
    sourceMode: LibrarySourceMode,
): List<LibraryItem> =
    when (effectiveLibrarySortOption(selected, sourceMode)) {
        LibrarySortOption.DEFAULT -> items.sortedWith(
            compareBy<LibraryItem> { it.traktRank ?: Int.MAX_VALUE }
                .thenByDescending { it.savedAtEpochMs }
                .thenBy { libraryTitleTieBreakKey(it) }
                .thenBy { it.id },
        )
        LibrarySortOption.ADDED_DESC -> items.sortedWith(
            compareByDescending<LibraryItem> { it.savedAtEpochMs }
                .thenBy { libraryTitleTieBreakKey(it) }
                .thenBy { it.id },
        )
        LibrarySortOption.ADDED_ASC -> items.sortedWith(
            compareBy<LibraryItem> { it.savedAtEpochMs }
                .thenBy { libraryTitleTieBreakKey(it) }
                .thenBy { it.id },
        )
        LibrarySortOption.TITLE_ASC -> items.sortedWith(
            compareBy<LibraryItem> { libraryTitleSortKey(it) }
                .thenBy { it.id },
        )
        LibrarySortOption.TITLE_DESC -> items.sortedWith(
            compareByDescending<LibraryItem> { libraryTitleSortKey(it) }
                .thenBy { it.id },
        )
        LibrarySortOption.RATING_DESC -> items.sortedWith(
            compareByDescending<LibraryItem> { it.imdbRating?.toFloatOrNull() ?: 0f }
                .thenBy { libraryTitleTieBreakKey(it) }
                .thenBy { it.id },
        )
        LibrarySortOption.RATING_ASC -> items.sortedWith(
            compareBy<LibraryItem> { it.imdbRating?.toFloatOrNull() ?: Float.MAX_VALUE }
                .thenBy { libraryTitleTieBreakKey(it) }
                .thenBy { it.id },
        )
        LibrarySortOption.RELEASE_DATE_DESC -> items.sortedWith(
            compareByDescending<LibraryItem> { extractNormalizedReleaseDateKey(it).isNotBlank() }
                .thenByDescending { extractNormalizedReleaseDateKey(it) }
                .thenBy { libraryTitleTieBreakKey(it) }
                .thenBy { it.id },
        )
        LibrarySortOption.RELEASE_DATE_ASC -> items.sortedWith(
            compareByDescending<LibraryItem> { extractNormalizedReleaseDateKey(it).isNotBlank() }
                .thenBy { extractNormalizedReleaseDateKey(it) }
                .thenBy { libraryTitleTieBreakKey(it) }
                .thenBy { it.id },
        )
        LibrarySortOption.LAST_WATCHED_DESC -> items.sortedWith(
            compareByDescending<LibraryItem> { it.lastWatchedAtEpochMs ?: 0L }
                .thenByDescending { it.savedAtEpochMs }
                .thenBy { libraryTitleTieBreakKey(it) }
                .thenBy { it.id },
        )
        LibrarySortOption.SHELF_GROUP -> items.sortedWith(
            compareBy<LibraryItem> { LibraryShelf.fromKey(it.shelf)?.ordinal ?: Int.MAX_VALUE }
                .thenByDescending { it.savedAtEpochMs }
                .thenBy { libraryTitleTieBreakKey(it) }
                .thenBy { it.id },
        )
    }

internal fun sortLibrarySections(
    sections: List<LibrarySection>,
    selected: LibrarySortOption,
    sourceMode: LibrarySourceMode,
): List<LibrarySection> =
    sections.map { section ->
        section.copy(items = sortLibraryItems(section.items, selected, sourceMode))
    }

internal fun buildLibraryVerticalProjection(
    sections: List<LibrarySection>,
    sourceMode: LibrarySourceMode,
    selectedSectionKey: String?,
    selectedType: String?,
    sortOption: LibrarySortOption,
): LibraryVerticalProjection {
    val availableSections = if (sourceMode.isRemoteTrackingSource) sections else emptyList()
    val selectedSection = if (sourceMode.isRemoteTrackingSource) {
        sections.firstOrNull { it.type == selectedSectionKey } ?: sections.firstOrNull()
    } else {
        null
    }
    val baseEntries = if (selectedSection != null) {
        selectedSection.items.map { item -> LibraryVerticalEntry(item, selectedSection) }
    } else {
        sections.flatMap { section ->
            section.items.map { item -> LibraryVerticalEntry(item, section) }
        }
    }
    val deduplicatedEntries = LinkedHashMap<String, LibraryVerticalEntry>()
    baseEntries.forEach { entry ->
        val key = libraryDisplayItemKey(entry.item)
        if (key !in deduplicatedEntries) {
            deduplicatedEntries[key] = entry
        }
    }
    val availableTypes = deduplicatedEntries.values
        .map { entry -> entry.item.type.normalizedLibraryType() }
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()
    val effectiveType = selectedType
        ?.normalizedLibraryType()
        ?.takeIf { it in availableTypes }
    val filteredEntries = deduplicatedEntries.values.filter { entry ->
        effectiveType == null || entry.item.type.normalizedLibraryType() == effectiveType
    }
    val entryByKey = filteredEntries.associateBy { entry -> libraryDisplayItemKey(entry.item) }
    val sortedEntries = sortLibraryItems(
        items = filteredEntries.map { entry -> entry.item },
        selected = sortOption,
        sourceMode = sourceMode,
    ).mapNotNull { item -> entryByKey[libraryDisplayItemKey(item)] }

    return LibraryVerticalProjection(
        availableSections = availableSections,
        selectedSectionKey = selectedSection?.type,
        availableTypes = availableTypes,
        selectedType = effectiveType,
        entries = sortedEntries,
    )
}

internal fun encodeLibraryDisplaySettings(state: LibraryDisplaySettingsUiState): String =
    LibraryDisplaySettingsJson.encodeToString(
        StoredLibraryDisplaySettings(
            layoutMode = state.layoutMode.name,
            sortOption = state.sortOption.name,
            savedSortOption = state.savedSortOption.name,
            shelvesSortOption = state.shelvesSortOption.name,
            selectedShelfKey = state.selectedShelfKey,
            viewFilterMode = state.viewFilterMode.name,
            showPrivateVault = state.showPrivateVault,
            showNSFW = state.showNSFW,
        ),
    )

internal fun decodeLibraryDisplaySettings(payload: String?): LibraryDisplaySettingsUiState {
    val stored = payload
        ?.takeIf { it.isNotBlank() }
        ?.let { value ->
            runCatching {
                LibraryDisplaySettingsJson.decodeFromString<StoredLibraryDisplaySettings>(value)
            }.getOrNull()
        }

    val savedSort = stored?.savedSortOption
        ?.let { value -> LibrarySortOption.entries.firstOrNull { it.name == value } }
        ?: stored?.sortOption?.let { value -> LibrarySortOption.entries.firstOrNull { it.name == value } }
        ?: LibrarySortOption.ADDED_DESC

    val shelvesSort = stored?.shelvesSortOption
        ?.let { value -> LibrarySortOption.entries.firstOrNull { it.name == value } }
        ?: stored?.sortOption?.let { value -> LibrarySortOption.entries.firstOrNull { it.name == value } }
        ?: LibrarySortOption.DEFAULT

    return LibraryDisplaySettingsUiState(
        layoutMode = stored?.layoutMode
            ?.let { value -> LibraryLayoutMode.entries.firstOrNull { it.name == value } }
            ?: LibraryLayoutMode.HORIZONTAL,
        savedSortOption = savedSort,
        shelvesSortOption = shelvesSort,
        selectedShelfKey = stored?.selectedShelfKey,
        viewFilterMode = stored?.viewFilterMode
            ?.let { value -> LibraryViewFilterMode.entries.firstOrNull { it.name == value } }
            ?: LibraryViewFilterMode.INCLUSIVE,
        showPrivateVault = stored?.showPrivateVault ?: false,
        showNSFW = stored?.showNSFW ?: false,
    )
}

private val LibraryDisplaySettingsJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private fun libraryTitleSortKey(item: LibraryItem): String =
    libraryTitleTieBreakKey(item)

private fun libraryTitleTieBreakKey(item: LibraryItem): String =
    item.name
        .ifBlank { item.id }
        .lowercase()

private fun extractNormalizedReleaseDateKey(item: LibraryItem, isDescending: Boolean = false): String {
    val raw = item.rawReleaseDate?.trim().orEmpty().ifBlank { item.releaseInfo.orEmpty().trim() }
    if (raw.isBlank()) return ""

    val fullDateMatch = Regex("\\b(18|19|20)\\d{2}[-/]\\d{1,2}[-/]\\d{1,2}\\b").find(raw)?.value
    if (fullDateMatch != null) {
        val parts = fullDateMatch.split('-', '/')
        if (parts.size == 3) {
            val y = parts[0]
            val m = parts[1].padStart(2, '0')
            val d = parts[2].padStart(2, '0')
            return "$y-$m-$d"
        }
    }

    val yearMonthMatch = Regex("\\b(18|19|20)\\d{2}[-/]\\d{1,2}\\b").find(raw)?.value
    if (yearMonthMatch != null) {
        val parts = yearMonthMatch.split('-', '/')
        if (parts.size == 2) {
            val y = parts[0]
            val m = parts[1].padStart(2, '0')
            val dayPad = if (isDescending) "31" else "01"
            return "$y-$m-$dayPad"
        }
    }

    val yearMatch = Regex("\\b(18|19|20)\\d{2}\\b").find(raw)?.value
    if (yearMatch != null) {
        val monthDay = if (isDescending) "-12-31" else "-01-01"
        return "$yearMatch$monthDay"
    }

    return raw.lowercase()
}

private fun libraryDisplayItemKey(item: LibraryItem): String =
    "${item.type.normalizedLibraryType()}:${item.id.trim()}"

private fun String.normalizedLibraryType(): String = trim().lowercase()

internal val LibrarySourceMode.isRemoteTrackingSource: Boolean
    get() = this != LibrarySourceMode.LOCAL

@Serializable
private data class StoredLibraryDisplaySettings(
    @SerialName("layout_mode") val layoutMode: String = LibraryLayoutMode.HORIZONTAL.name,
    @SerialName("sort_option") val sortOption: String = LibrarySortOption.DEFAULT.name,
    @SerialName("saved_sort_option") val savedSortOption: String = LibrarySortOption.ADDED_DESC.name,
    @SerialName("shelves_sort_option") val shelvesSortOption: String = LibrarySortOption.DEFAULT.name,
    @SerialName("selected_shelf_key") val selectedShelfKey: String? = null,
    @SerialName("view_filter_mode") val viewFilterMode: String = LibraryViewFilterMode.EXCLUSIVE.name,
    @SerialName("show_private_vault") val showPrivateVault: Boolean = false,
    @SerialName("show_nsfw") val showNSFW: Boolean = false,
)
