package com.tencent.supersonic.chat.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tencent.supersonic.chat.api.pojo.response.QueryResp;
import com.tencent.supersonic.chat.server.persistence.dataobject.ChatQueryDO;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

@Mapper
public interface ChatQueryDOMapper extends BaseMapper<ChatQueryDO> {
    @Select(" SELECT  " + "a.question_id as questionId," + "a.agent_id as agentId,"
            + "a.user_name as userName," + "a.query_state as queryState,"
            + "a.query_text as queryText," + "a.query_result  as queryResult"
            + " FROM s2_chat_query a"
            + " INNER JOIN (SELECT  max(question_id) as question_id,query_text  FROM s2_chat_query      "
            + "WHERE  (chat_id =#{chatId}  AND user_name = #{userName} "
            + "AND query_state = 1 AND query_result IS NOT NULL AND query_result <> '' "
            + " AND JSON_EXTRACT(query_result, '$.chatContext.sqlInfo.resultType')!='text')   GROUP BY query_text ) b"
            + " on a.question_id=b.question_id " + " ORDER BY a.question_id DESC limit 10")

    List<Map<String, Object>> selectByUserName(@Param("chatId") Integer chatId,
            @Param("userName") String userName);
}
