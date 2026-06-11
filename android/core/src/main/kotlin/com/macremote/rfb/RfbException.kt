package com.macremote.rfb

/** RFB 协议错误（握手失败、认证失败、数据流损坏等）。 */
class RfbException(message: String, cause: Throwable? = null) : Exception(message, cause)
