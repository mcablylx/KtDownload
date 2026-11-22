package top.mcly99.dl.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.mcly99.dl.model.AppSettings
import top.mcly99.dl.model.ProxyMode
import java.util.Properties

/**
 * P1：代理三态与 HTTP/2 开关的配置迁移 / 往返一致性。
 *
 * 只测 [SettingsRepository.fromProperties] / [toProperties] 两个纯函数，
 * 不碰真实的 `config.properties`，避免测试改写用户设置。
 */
class SettingsRepositoryTest {

    @Test
    fun empty_config_uses_safe_defaults() {
        val settings = SettingsRepository.fromProperties(Properties())
        assertEquals(ProxyMode.DIRECT, settings.proxyMode)
        assertFalse(settings.http2Enabled)
        assertEquals("", settings.proxyHost)
        assertEquals(1080, settings.proxyPort)
    }

    @Test
    fun legacy_enabled_flag_migrates_to_manual() {
        val props = Properties().apply {
            setProperty("proxyEnabled", "true")
            setProperty("proxyHost", "127.0.0.1")
            setProperty("proxyPort", "7890")
        }
        val settings = SettingsRepository.fromProperties(props)
        assertEquals(ProxyMode.MANUAL, settings.proxyMode)
        assertEquals("127.0.0.1", settings.proxyHost)
        assertEquals(7890, settings.proxyPort)
    }

    @Test
    fun legacy_disabled_flag_migrates_to_direct_even_with_host_configured() {
        val props = Properties().apply {
            setProperty("proxyEnabled", "false")
            setProperty("proxyHost", "127.0.0.1")
        }
        assertEquals(ProxyMode.DIRECT, SettingsRepository.fromProperties(props).proxyMode)
    }

    @Test
    fun new_mode_takes_priority_over_legacy_flag() {
        val props = Properties().apply {
            setProperty("proxyEnabled", "true")
            setProperty("proxyMode", "SYSTEM")
        }
        assertEquals(ProxyMode.SYSTEM, SettingsRepository.fromProperties(props).proxyMode)
    }

    @Test
    fun unknown_mode_falls_back_to_defaults_instead_of_crashing() {
        val props = Properties().apply { setProperty("proxyMode", "AUTO_MAGIC") }
        assertEquals(ProxyMode.DIRECT, SettingsRepository.fromProperties(props).proxyMode)
        assertEquals(
            ProxyMode.SYSTEM,
            SettingsRepository.fromProperties(props, AppSettings(proxyMode = ProxyMode.SYSTEM)).proxyMode,
        )
    }

    @Test
    fun mode_names_are_case_insensitive_and_trimmed() {
        val props = Properties().apply { setProperty("proxyMode", "  system  ") }
        assertEquals(ProxyMode.SYSTEM, SettingsRepository.fromProperties(props).proxyMode)
    }

    @Test
    fun round_trip_preserves_mode_host_port_and_http2() {
        val original = AppSettings(
            proxyMode = ProxyMode.SYSTEM,
            proxyHost = "proxy.local",
            proxyPort = 3128,
            http2Enabled = true,
            deepCatchEnabled = true,
        )
        val restored = SettingsRepository.fromProperties(SettingsRepository.toProperties(original))
        assertEquals(original.proxyMode, restored.proxyMode)
        assertEquals(original.proxyHost, restored.proxyHost)
        assertEquals(original.proxyPort, restored.proxyPort)
        assertTrue(restored.http2Enabled)
        assertTrue(restored.deepCatchEnabled)
    }

    @Test
    fun saving_writes_legacy_flag_so_old_builds_still_get_manual_proxy() {
        val manual = SettingsRepository.toProperties(AppSettings(proxyMode = ProxyMode.MANUAL))
        assertEquals("true", manual.getProperty("proxyEnabled"))
        val system = SettingsRepository.toProperties(AppSettings(proxyMode = ProxyMode.SYSTEM))
        assertEquals("false", system.getProperty("proxyEnabled"))
        assertEquals("SYSTEM", system.getProperty("proxyMode"))
    }

    @Test
    fun http2_is_off_by_default_and_only_on_when_explicitly_true() {
        assertFalse(SettingsRepository.fromProperties(Properties()).http2Enabled)
        assertTrue(
            SettingsRepository.fromProperties(Properties().apply { setProperty("http2Enabled", "true") })
                .http2Enabled,
        )
        assertFalse(
            SettingsRepository.fromProperties(Properties().apply { setProperty("http2Enabled", "yes") })
                .http2Enabled,
        )
    }

    // ---------------------------------------------------------------- P3 · C1

    @Test
    fun multi_account_rotation_is_off_by_default_and_round_trips() {
        assertFalse(SettingsRepository.fromProperties(Properties()).multiAccountDownload)
        val on = AppSettings(multiAccountDownload = true)
        val restored = SettingsRepository.fromProperties(SettingsRepository.toProperties(on))
        assertTrue(restored.multiAccountDownload)
        assertEquals("true", SettingsRepository.toProperties(on).getProperty("multiAccountDownload"))
        assertEquals("false", SettingsRepository.toProperties(AppSettings()).getProperty("multiAccountDownload"))
    }

    @Test
    fun malformed_multi_account_flag_falls_back_to_safe_default() {
        assertFalse(
            SettingsRepository.fromProperties(
                Properties().apply { setProperty("multiAccountDownload", "yes") },
            ).multiAccountDownload,
        )
    }

    // ---------------------------------------------------------------- C2：单文件多源分片

    @Test
    fun multi_source_is_off_by_default_and_round_trips_only_together_with_c1() {
        assertFalse(SettingsRepository.fromProperties(Properties()).multiSourceDownload)
        val both = AppSettings(multiAccountDownload = true, multiSourceDownload = true)
        val restored = SettingsRepository.fromProperties(SettingsRepository.toProperties(both))
        assertTrue(restored.multiAccountDownload)
        assertTrue(restored.multiSourceDownload)
    }

    @Test
    fun c2_is_forced_off_when_c1_is_off() {
        // 手工改过配置 / 老版本残留：C2 单独为 true 不允许生效
        val c2Only = Properties().apply { setProperty("multiSourceDownload", "true") }
        assertFalse(SettingsRepository.fromProperties(c2Only).multiSourceDownload)
        val saved = SettingsRepository.toProperties(
            AppSettings(multiAccountDownload = false, multiSourceDownload = true),
        )
        assertEquals("false", saved.getProperty("multiSourceDownload"))
    }

    // ------------------------------------------------ 下载完成后关机（上游 1.3.4）

    @Test
    fun shutdown_after_complete_is_off_by_default_and_round_trips() {
        assertFalse(SettingsRepository.fromProperties(Properties()).shutdownAfterComplete)
        val saved = SettingsRepository.toProperties(AppSettings(shutdownAfterComplete = true))
        assertEquals("true", saved.getProperty("shutdownAfterComplete"))
        assertTrue(SettingsRepository.fromProperties(saved).shutdownAfterComplete)
    }

    @Test
    fun malformed_shutdown_flag_does_not_enable_a_dangerous_action() {
        val props = Properties().apply { setProperty("shutdownAfterComplete", "YES") }
        assertFalse(SettingsRepository.fromProperties(props).shutdownAfterComplete)
    }

    // ------------------------------------------------ 自动检查更新

    @Test
    fun auto_check_update_is_on_by_default_and_round_trips() {
        assertTrue(SettingsRepository.fromProperties(Properties()).autoCheckUpdate)
        val saved = SettingsRepository.toProperties(AppSettings(autoCheckUpdate = false))
        assertEquals("false", saved.getProperty("autoCheckUpdate"))
        assertFalse(SettingsRepository.fromProperties(saved).autoCheckUpdate)
    }

    @Test
    fun malformed_auto_check_flag_falls_back_to_default() {
        val props = Properties().apply { setProperty("autoCheckUpdate", "YES") }
        assertTrue(SettingsRepository.fromProperties(props).autoCheckUpdate)
    }
}
