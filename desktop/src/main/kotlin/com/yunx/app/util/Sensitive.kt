package com.yunx.app.util

/**
 * 展示层脱敏：网盘账号的「昵称 / 登录账号」里可能直接就是手机号或邮箱
 * （例如蓝奏云的昵称返回的就是登录手机号），界面上必须打码，避免敏感信息明文暴露。
 *
 * - 手机号（纯数字且 ≥ 7 位）：保留前 3 后 4，如 `138****0000`
 * - 邮箱：保留首字符与域名，如 `a***@qq.com`
 * - 其它：**原样返回** —— 普通昵称不能被破坏（否则「我的昵称」会变成「我***称」）
 */
fun maskAccountText(value: String): String {
    val v = value.trim()
    if (v.isEmpty()) return v
    if (v.contains('@')) {
        val name = v.substringBefore('@')
        val domain = v.substringAfter('@')
        return (if (name.isEmpty()) "" else name.take(1)) + "***@" + domain
    }
    val digits = v.filter { it.isDigit() }
    if (digits.length >= 7 && digits.length == v.length) {
        return v.take(3) + "****" + v.takeLast(4)
    }
    return v
}
