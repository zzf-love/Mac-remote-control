package com.macremote.rfb

/**
 * RFB (Remote Framebuffer / VNC) 协议常量。
 * 参考 RFC 6143；macOS 自带“屏幕共享”即为一个 RFB 服务器
 * （它上报的版本号是非标准的 003.889，按 3.8 处理即可）。
 */
object RfbProto {
    // 安全类型
    const val SEC_INVALID = 0
    const val SEC_NONE = 1
    const val SEC_VNC_AUTH = 2
    const val SEC_APPLE_DH = 30 // Apple 私有认证（用 macOS 账户登录），暂未实现

    // 客户端 → 服务器消息
    const val MSG_SET_PIXEL_FORMAT = 0
    const val MSG_SET_ENCODINGS = 2
    const val MSG_FRAMEBUFFER_UPDATE_REQUEST = 3
    const val MSG_KEY_EVENT = 4
    const val MSG_POINTER_EVENT = 5
    const val MSG_CLIENT_CUT_TEXT = 6

    // 服务器 → 客户端消息
    const val SMSG_FRAMEBUFFER_UPDATE = 0
    const val SMSG_SET_COLOUR_MAP = 1
    const val SMSG_BELL = 2
    const val SMSG_SERVER_CUT_TEXT = 3

    // 编码
    const val ENC_RAW = 0
    const val ENC_COPY_RECT = 1
    const val ENC_ZRLE = 16
    const val ENC_DESKTOP_SIZE = -223 // 伪编码：桌面尺寸变化

    // 鼠标按键掩码（PointerEvent button-mask）
    const val BTN_LEFT = 1
    const val BTN_MIDDLE = 2
    const val BTN_RIGHT = 4
    const val BTN_WHEEL_UP = 8
    const val BTN_WHEEL_DOWN = 16
}
