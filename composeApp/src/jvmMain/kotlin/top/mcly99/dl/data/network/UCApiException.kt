package top.mcly99.dl.data.network

/**
 * UC 网盘业务异常。
 *
 * 此前 UC 全部业务失败都抛 [QuarkApiException]，码表也是夸克的语义（31001/23018）
 * 混着 UC 自己的 14001/41020，上层拿到的是「披着夸克异常外衣的 UC 错误」，
 * 日志/埋点无法按平台区分。UC 与夸克的分享解析契约相同（Result），异常类型拆分
 * 不影响 UI；这里独立成类，code 语义归 UC 自己（P1-E6）。
 */
class UCApiException(message: String, val code: Int = 0) : Exception(message)
