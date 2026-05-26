package dev.anilbeesetti.nextplayer.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.anilbeesetti.nextplayer.BuildConfig
import dev.anilbeesetti.nextplayer.core.common.updater.GithubUpdateConfig
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideGithubUpdateConfig(): GithubUpdateConfig = GithubUpdateConfig(
        owner = BuildConfig.UPDATE_GITHUB_OWNER,
        repo = BuildConfig.UPDATE_GITHUB_REPO,
    )
}
