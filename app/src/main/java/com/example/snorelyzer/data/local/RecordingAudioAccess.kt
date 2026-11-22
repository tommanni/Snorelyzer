package com.example.snorelyzer.data.local

import java.time.LocalDate

class RecordingAudioAccess {
    private val readers = mutableMapOf<LocalDate, Int>()
    private val expiringNights = mutableSetOf<LocalDate>()

    @Synchronized
    internal fun tryUse(night: LocalDate): AutoCloseable? {
        if (night in expiringNights) return null
        readers[night] = readers.getOrDefault(night, 0) + 1
        return lease {
            val remaining = readers.getValue(night) - 1
            if (remaining == 0) readers.remove(night) else readers[night] = remaining
        }
    }

    @Synchronized
    internal fun tryExpire(night: LocalDate): AutoCloseable? {
        if (night in readers || !expiringNights.add(night)) return null
        return lease { expiringNights.remove(night) }
    }

    private fun lease(release: () -> Unit): AutoCloseable {
        var closed = false
        return AutoCloseable {
            synchronized(this) {
                if (!closed) {
                    closed = true
                    release()
                }
            }
        }
    }
}
