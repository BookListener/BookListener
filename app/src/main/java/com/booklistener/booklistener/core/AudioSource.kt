package com.booklistener.core

interface AudioSource {
    suspend fun listDirectory(path: String): Result<List<AudioItem>>
    suspend fun getPlayUri(item: AudioItem): Result<String>
    suspend fun refreshCredential(): Result<Unit>
    suspend fun getMeta(item: AudioItem): Result<AudioMeta>
}
