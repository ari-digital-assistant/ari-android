package dev.heyari.ari.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.heyari.ari.data.card.CardStateRepository
import dev.heyari.ari.data.card.CardStateSource
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CardModule {

    @Binds
    @Singleton
    abstract fun bindCardStateSource(repository: CardStateRepository): CardStateSource
}
