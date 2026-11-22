package top.mcly99.dl.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VersionTest {

    @Test
    fun compares_numeric_segments_not_strings() {
        // 字符串序会把 1.0.10 当成比 1.0.9 旧，数值比较不会
        assertTrue(Version.isNewer("v1.0.10", "1.0.9"))
        assertTrue(Version.isNewer("1.10.0", "1.9.9"))
        assertTrue(Version.isNewer("2.0.0", "1.99.99"))
    }

    @Test
    fun handles_v_prefix_and_equal_versions() {
        assertTrue(Version.isNewer("v1.0.1", "1.0.0"))
        assertFalse(Version.isNewer("v1.0.0", "1.0.0"))
        assertTrue(Version.isNewer("V1.0.1", "v1.0.0"))
        // 段数不同按补 0 对齐
        assertFalse(Version.isNewer("1.0.0", "1.0"))
        assertFalse(Version.isNewer("1.0", "1.0.0"))
    }

    @Test
    fun ignores_suffixes_and_metadata() {
        assertTrue(Version.isNewer("v1.1.0-beta", "1.0.0"))
        assertFalse(Version.isNewer("v1.0.0+build5", "1.0.0"))
    }

    @Test
    fun invalid_versions_never_report_update() {
        assertFalse(Version.isNewer("", "1.0.0"))
        assertFalse(Version.isNewer("garbage", "1.0.0"))
        assertFalse(Version.isNewer(null, "1.0.0"))
        assertFalse(Version.isNewer("v1.0.1", "garbage"))
        assertFalse(Version.isNewer("1.0.1", null))
    }

    @Test
    fun compare_sign_is_correct() {
        assertEquals(1, Version.compare("1.0.1", "1.0.0"))
        assertEquals(-1, Version.compare("1.0.0", "1.0.1"))
        assertEquals(0, Version.compare("1.0.0", "1.0.0"))
    }
}
