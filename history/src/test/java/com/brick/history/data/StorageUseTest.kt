package com.brick.history.data

import com.brick.history.ui.StorageUse
import org.junit.Assert.assertEquals
import org.junit.Test

class StorageUseTest {
    @Test fun readableSizes() {
        assertEquals("512 B", StorageUse.bytes(512))
        assertEquals("2.4 MB", StorageUse.bytes(2_400_000))
        assertEquals("3 MB", StorageUse.bytes(3_000_000))
        assertEquals("128 GB", StorageUse.bytes(128_000_000_000))
        assertEquals("64.5 KB", StorageUse.bytes(64_500))
    }
}
