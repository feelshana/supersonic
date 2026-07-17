package com.tencent.supersonic.chat.api.pojo.enums;

public enum ChatIntentType {
    /**
     * 直接回复，前端/Dify 直接输出 directReply 内容
     */
    DIRECT_REPLY,

    /**
     * 数据解读，需继续调用 EasyBI 取数截断接口
     */
    DATA_INTERPRETATION,

    /**
     * 正常问数，需继续调用 parseAndExecute 等问数链路
     */
    DATA_QUERY
}
