package com.offline.translate.core.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "translation_history",
    indices = [
        Index(value = ["timestamp_ms"]),
        Index(value = ["is_favorite"]),
        Index(value = ["domain_id"])
    ]
)
data class TranslationHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "source_lang") val sourceLang: String,
    @ColumnInfo(name = "target_lang") val targetLang: String,
    @ColumnInfo(name = "domain_id") val domainId: String,
    @ColumnInfo(name = "source_text") val sourceText: String,
    @ColumnInfo(name = "translated_text") val translatedText: String,
    @ColumnInfo(name = "routing_mode") val routingMode: String = "DIRECT",
    @ColumnInfo(name = "applied_terms_summary") val appliedTermsSummary: String = "",
    @ColumnInfo(name = "latency_ms") val latencyMs: Long = 0L,
    @ColumnInfo(name = "is_favorite") val isFavorite: Boolean = false,
    @ColumnInfo(name = "timestamp_ms") val timestampMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "glossary_terms",
    indices = [
        Index(value = ["source_lang", "target_lang", "domain_id"]),
        Index(value = ["source_term", "source_lang", "target_lang", "domain_id", "is_user_defined"], unique = true)
    ]
)
data class GlossaryTermEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "source_lang") val sourceLang: String,
    @ColumnInfo(name = "target_lang") val targetLang: String,
    @ColumnInfo(name = "domain_id") val domainId: String,
    @ColumnInfo(name = "source_term") val sourceTerm: String,
    @ColumnInfo(name = "target_term") val targetTerm: String,
    @ColumnInfo(name = "is_user_defined") val isUserDefined: Boolean = true,
    @ColumnInfo(name = "case_sensitive") val caseSensitive: Boolean = false,
    @ColumnInfo(name = "priority") val priority: Int = 100,
    @ColumnInfo(name = "note") val note: String = ""
)

@Entity(tableName = "installed_language_packs")
data class InstalledPackEntity(
    @PrimaryKey
    @ColumnInfo(name = "pack_id") val packId: String,
    @ColumnInfo(name = "version") val version: String,
    @ColumnInfo(name = "manifest_json") val manifestJson: String,
    @ColumnInfo(name = "install_dir_path") val installDirPath: String,
    @ColumnInfo(name = "disk_size_bytes") val diskSizeBytes: Long,
    @ColumnInfo(name = "installed_at_ms") val installedAtMs: Long = System.currentTimeMillis()
)

@Dao
interface TranslationHistoryDao {
    @Query("SELECT * FROM translation_history ORDER BY timestamp_ms DESC")
    fun observeAll(): Flow<List<TranslationHistoryEntity>>

    @Query("SELECT * FROM translation_history WHERE is_favorite = 1 ORDER BY timestamp_ms DESC")
    fun observeFavorites(): Flow<List<TranslationHistoryEntity>>

    @Query(
        """
        SELECT * FROM translation_history 
        WHERE (source_text LIKE '%' || :query || '%' OR translated_text LIKE '%' || :query || '%')
        AND (:onlyFavorites = 0 OR is_favorite = 1)
        ORDER BY timestamp_ms DESC
        """
    )
    fun searchHistory(query: String, onlyFavorites: Boolean): Flow<List<TranslationHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: TranslationHistoryEntity): Long

    @Query("UPDATE translation_history SET is_favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query("DELETE FROM translation_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM translation_history WHERE is_favorite = 0")
    suspend fun clearNonFavorites()

    @Query("SELECT * FROM translation_history ORDER BY timestamp_ms DESC")
    suspend fun getAllSnapshot(): List<TranslationHistoryEntity>
}

@Dao
interface GlossaryDao {
    @Query(
        """
        SELECT * FROM glossary_terms 
        WHERE source_lang = :sourceLang 
        AND target_lang = :targetLang 
        AND (domain_id = :domainId OR domain_id = 'general')
        ORDER BY priority DESC, LENGTH(source_term) DESC
        """
    )
    suspend fun getActiveTermsForPairAndDomain(
        sourceLang: String,
        targetLang: String,
        domainId: String
    ): List<GlossaryTermEntity>

    @Query("SELECT * FROM glossary_terms ORDER BY is_user_defined DESC, domain_id ASC, source_term ASC")
    fun observeAllTerms(): Flow<List<GlossaryTermEntity>>

    @Query("SELECT * FROM glossary_terms WHERE (:domainId IS NULL OR domain_id = :domainId) AND (:userOnly = 0 OR is_user_defined = 1)")
    suspend fun getTermsFiltered(domainId: String?, userOnly: Boolean): List<GlossaryTermEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(terms: List<GlossaryTermEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(term: GlossaryTermEntity): Long

    @Query("DELETE FROM glossary_terms WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM glossary_terms")
    suspend fun count(): Int
}

@Dao
interface InstalledPackDao {
    @Query("SELECT * FROM installed_language_packs ORDER BY installed_at_ms DESC")
    fun observeInstalledPacks(): Flow<List<InstalledPackEntity>>

    @Query("SELECT * FROM installed_language_packs")
    suspend fun getInstalledPacksSnapshot(): List<InstalledPackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(pack: InstalledPackEntity)

    @Query("DELETE FROM installed_language_packs WHERE pack_id = :packId")
    suspend fun deleteByPackId(packId: String)
}

@Database(
    entities = [
        TranslationHistoryEntity::class,
        GlossaryTermEntity::class,
        InstalledPackEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class TranslatorDatabase : RoomDatabase() {
    abstract fun historyDao(): TranslationHistoryDao
    abstract fun glossaryDao(): GlossaryDao
    abstract fun installedPackDao(): InstalledPackDao
}
