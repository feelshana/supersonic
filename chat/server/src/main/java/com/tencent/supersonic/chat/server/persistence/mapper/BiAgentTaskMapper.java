package com.tencent.supersonic.chat.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tencent.supersonic.chat.server.persistence.dataobject.BiAgentTaskDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.Date;

@Mapper
public interface BiAgentTaskMapper extends BaseMapper<BiAgentTaskDO> {

    @Update("UPDATE s2_bi_agent_task "
            + "SET status = #{runningStatus}, started_at = #{now}, updated_at = #{now}, error_msg = NULL "
            + "WHERE id = #{taskId} " + "AND status = #{pendingStatus} " + "AND NOT EXISTS ("
            + "    SELECT 1 FROM (" + "        SELECT id FROM s2_bi_agent_task t "
            + "        WHERE t.report_id = #{reportId} "
            + "          AND t.status = #{runningStatus} " + "          AND t.id <> #{taskId}"
            + "    ) running_task" + ")")
    int tryMarkTaskRunning(@Param("taskId") Long taskId, @Param("reportId") String reportId,
            @Param("pendingStatus") String pendingStatus,
            @Param("runningStatus") String runningStatus, @Param("now") Date now);
}
