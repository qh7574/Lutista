package cn.alittlecookie.lutista.tether.protocol

/**
 * 协议解析失败（数据自校验不通过）。
 *
 * 出现它就意味着「缓冲区/回包已经不可信」：调用方**不得**静默截断后继续解析，
 * 必须上抛或降级到「未知机型」形态（v8 §1.7 `Unrecognized`）。
 */
class PtpParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
