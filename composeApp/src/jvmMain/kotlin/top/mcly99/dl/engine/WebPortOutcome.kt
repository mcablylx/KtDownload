package top.mcly99.dl.engine

/**
 * 「启动 / 换端口」一次尝试的结果判定。
 *
 * 关键点：`WebUploadServer.start` 是**先在新端口起服务、成功了才关旧的**，所以
 * 「`started == false`」不等于「服务已经停了」。换端口失败时旧服务还在原端口正常工作，
 * 界面必须如实显示旧地址 + 错误提示，而不是把运行状态和地址一起清空（P1-20）。
 *
 * 这三个派生属性把原先散在 ViewModel 里的三处分支收进一个类型：
 * - [failed]：要不要给用户报错
 * - [available]：界面该不该显示地址、能不能广播端口
 * - [keptOldPort]：失败的原因是「换了没成功」，还是「彻底起不来」
 */
data class WebPortOutcome(
    val requestedPort: Int,
    /** 实际在监听的端口；启动失败且服务已停时它可能仍是旧值，所以必须配合 [running] 看 */
    val activePort: Int,
    /** `start()` 是否在 [requestedPort] 上成功 */
    val started: Boolean,
    /** 调用结束后服务是否在运行（旧端口继续跑也算） */
    val running: Boolean,
) {
    /** 需要向用户报错：请求的端口没能用上 */
    val failed: Boolean get() = !started

    /** 服务可用：即使换端口失败，旧服务还在跑就仍然可用 */
    val available: Boolean get() = running

    /** 失败但旧服务仍在：日志要说明「继续在旧端口运行」，避免被当成彻底宕机 */
    val keptOldPort: Boolean get() = !started && running

    /** 日志 / 提示文案；三态互斥，别在 ViewModel 里再拼一遍 */
    fun describe(): String = when {
        started -> "Web 服务已启动，端口 $activePort"
        running -> "端口 $requestedPort 启动失败，服务继续在旧端口 $activePort 运行"
        else -> "Web 服务启动失败，端口 $requestedPort"
    }

    companion object {
        /**
         * @param started `WebUploadServer.start(port)` 的返回值
         * @param activePort 调用后 `WebUploadServer.port`（真正在监听的端口）
         * @param running 调用后 `WebUploadServer.isRunning`
         */
        fun resolve(requestedPort: Int, started: Boolean, activePort: Int, running: Boolean): WebPortOutcome =
            WebPortOutcome(
                requestedPort = requestedPort,
                activePort = activePort,
                started = started,
                running = running,
            )
    }
}
