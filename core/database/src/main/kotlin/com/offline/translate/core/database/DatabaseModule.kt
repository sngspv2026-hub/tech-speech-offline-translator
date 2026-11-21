package com.offline.translate.core.database

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideTranslatorDatabase(
        @ApplicationContext context: Context
    ): TranslatorDatabase {
        return Room.databaseBuilder(
            context,
            TranslatorDatabase::class.java,
            "offline_tech_translator.db"
        ).fallbackToDestructiveMigration().build()
    }

    @Provides
    fun provideHistoryDao(db: TranslatorDatabase): TranslationHistoryDao = db.historyDao()

    @Provides
    fun provideGlossaryDao(db: TranslatorDatabase): GlossaryDao = db.glossaryDao()

    @Provides
    fun provideInstalledPackDao(db: TranslatorDatabase): InstalledPackDao = db.installedPackDao()
}
