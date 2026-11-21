package com.offline.translate.feature.history

import com.offline.translate.core.database.TranslationHistoryDao
import com.offline.translate.core.database.TranslationHistoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryExportTest {

    private class FakeHistoryDao : TranslationHistoryDao {
        private val items = mutableListOf<TranslationHistoryEntity>()
        private val state = MutableStateFlow<List<TranslationHistoryEntity>>(emptyList())
        private var nextId = 1L

        override fun observeAll(): Flow<List<TranslationHistoryEntity>> = state
        override fun observeFavorites(): Flow<List<TranslationHistoryEntity>> =
            state.map { list -> list.filter { it.isFavorite } }

        override fun searchHistory(query: String, onlyFavorites: Boolean): Flow<List<TranslationHistoryEntity>> =
            state.map { list ->
                list.filter {
                    (query.isBlank() || it.sourceText.contains(query, true) || it.translatedText.contains(query, true)) &&
                        (!onlyFavorites || it.isFavorite)
                }
            }

        override suspend fun insert(entity: TranslationHistoryEntity): Long {
            val row = entity.copy(id = nextId++)
            items.add(row)
            state.value = items.toList()
            return row.id
        }

        override suspend fun setFavorite(id: Long, favorite: Boolean) {
            val idx = items.indexOfFirst { it.id == id }
            if (idx >= 0) {
                items[idx] = items[idx].copy(isFavorite = favorite)
                state.value = items.toList()
            }
        }

        override suspend fun deleteById(id: Long) {
            items.removeAll { it.id == id }
            state.value = items.toList()
        }

        override suspend fun clearNonFavorites() {
            items.removeAll { !it.isFavorite }
            state.value = items.toList()
        }

        override suspend fun getAllSnapshot(): List<TranslationHistoryEntity> = items.toList()
    }

    @Test
    fun `HistoryViewModel exports CSV and toggles favorites`() = runTest {
        val dao = FakeHistoryDao()
        val id = dao.insert(
            TranslationHistoryEntity(
                sourceLang = "ru",
                targetLang = "en",
                domainId = "mechanical",
                sourceText = "рабочее давление 5 MPa",
                translatedText = "operating pressure 5 MPa",
                latencyMs = 310L
            )
        )
        val vm = HistoryViewModel(dao)
        val csv = vm.exportHistoryCsv()
        assertTrue(csv.contains("operating pressure 5 MPa"))
        assertTrue(csv.contains("mechanical"))

        dao.setFavorite(id, true)
        assertEquals(true, dao.getAllSnapshot().first().isFavorite)

        val protocolMd = vm.exportMeetingProtocolMarkdown()
        assertTrue(protocolMd.contains("# Технический протокол перевода"))
        assertTrue(protocolMd.contains("operating pressure 5 MPa"))
    }
}
