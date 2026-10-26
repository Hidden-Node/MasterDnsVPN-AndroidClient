package com.masterdns.vpn.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TrafficFormattersTest {
    @Test
    fun formatSpeed_formatsCorrectUnits() {
        assertThat(TrafficFormatters.formatSpeed(0L)).isEqualTo("0 B/s")
        assertThat(TrafficFormatters.formatSpeed(500L)).isEqualTo("500 B/s")
        assertThat(TrafficFormatters.formatSpeed(1024L)).isEqualTo("1.0 KB/s")
        assertThat(TrafficFormatters.formatSpeed(1536L)).isEqualTo("1.5 KB/s")
        assertThat(TrafficFormatters.formatSpeed(1048576L)).isEqualTo("1.00 MB/s")
        assertThat(TrafficFormatters.formatSpeed(2621440L)).isEqualTo("2.50 MB/s")
        assertThat(TrafficFormatters.formatSpeed(-100L)).isEqualTo("0 B/s")
    }

    @Test
    fun formatBytes_formatsCorrectUnits() {
        assertThat(TrafficFormatters.formatBytes(0L)).isEqualTo("0 B")
        assertThat(TrafficFormatters.formatBytes(1024L)).isEqualTo("1.0 KB")
        assertThat(TrafficFormatters.formatBytes(1048576L)).isEqualTo("1.00 MB")
        assertThat(TrafficFormatters.formatBytes(1073741824L)).isEqualTo("1.00 GB")
        assertThat(TrafficFormatters.formatBytes(-50L)).isEqualTo("0 B")
    }

    @Test
    fun formatDuration_formatsMinutesAndHours() {
        assertThat(TrafficFormatters.formatDuration(0L)).isEqualTo("00:00")
        assertThat(TrafficFormatters.formatDuration(59L)).isEqualTo("00:59")
        assertThat(TrafficFormatters.formatDuration(65L)).isEqualTo("01:05")
        assertThat(TrafficFormatters.formatDuration(3600L)).isEqualTo("1:00:00")
        assertThat(TrafficFormatters.formatDuration(3665L)).isEqualTo("1:01:05")
        assertThat(TrafficFormatters.formatDuration(-10L)).isEqualTo("00:00")
    }
}
