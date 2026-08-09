package com.ynk.virtualdisplay.video

import org.junit.Assert.*
import org.junit.Test

class PerformanceTrackerTest {

    @Test
    fun testPerformanceTrackerStatsString() {
        val tracker = PerformanceTracker().apply {
            actualWidth = 1280
            actualHeight = 720
        }

        tracker.recordReceived(1000)
        val stats = tracker.getStatsString()

        assertTrue(stats.contains("分辨率: 1280x720"))
        assertTrue(stats.contains("码率:"))
        assertTrue(stats.contains("解码延迟:"))
        assertTrue(stats.contains("渲染延迟:"))
    }
}
