package com.tencent.supersonic.chat.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tencent.supersonic.chat.server.agent.Agent;
import com.tencent.supersonic.chat.server.persistence.dataobject.BiAgentTaskDO;
import com.tencent.supersonic.chat.server.persistence.mapper.BiAgentTaskMapper;
import com.tencent.supersonic.chat.server.service.BiAgentService;
import com.tencent.supersonic.chat.server.service.BiAgentTaskService;
import com.tencent.supersonic.common.bi.BiAgentConfig;
import com.tencent.supersonic.common.pojo.enums.TaskStatusEnum;
import com.tencent.supersonic.common.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

@Slf4j
@Service
public class BiAgentTaskServiceImpl extends ServiceImpl<BiAgentTaskMapper, BiAgentTaskDO>
        implements BiAgentTaskService {

    private static final int MAX_ERROR_MSG_LENGTH = 1900;

    @Autowired
    private BiAgentService biAgentService;

    @Autowired
    @Qualifier("biAgentExecutor")
    private ThreadPoolExecutor biAgentExecutor;

    @Value("${s2.bi.agent.task.dispatch-batch-size:20}")
    private Integer dispatchBatchSize;

    @Value("${s2.bi.agent.task.running-timeout-hours:6}")
    private Integer runningTimeoutHours;


    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addBiAgentTask(BiAgentConfig config) {
        if (Objects.isNull(config) || StringUtils.isBlank(config.getReportId())) {
            throw new IllegalArgumentException("reportId不能为空");
        }
        java.sql.Date taskDay = java.sql.Date.valueOf(LocalDate.now());
        Date now = new Date();
        BiAgentTaskDO taskDO = new BiAgentTaskDO();
        taskDO.setReportId(config.getReportId());
        taskDO.setTaskDay(taskDay);
        taskDO.setStatus(TaskStatusEnum.PENDING.getStatus());
        taskDO.setConfig(JsonUtil.toString(config));
        taskDO.setCreatedAt(now);
        taskDO.setUpdatedAt(now);
        baseMapper.insert(taskDO);
        log.info("新增BI训练任务成功, reportId: {}, taskId: {}", config.getReportId(), taskDO.getId());

    }

    @Override
    public void dispatchPendingTasks() {
        java.sql.Date taskDay = java.sql.Date.valueOf(LocalDate.now());
        List<BiAgentTaskDO> pendingTasks = baseMapper.selectList(new LambdaQueryWrapper<BiAgentTaskDO>()
                .eq(BiAgentTaskDO::getStatus, TaskStatusEnum.PENDING.getStatus())
                .eq(BiAgentTaskDO::getTaskDay, taskDay)
                .orderByAsc(BiAgentTaskDO::getId)
                .last("limit " + Math.max(1, dispatchBatchSize)));
        if (CollectionUtils.isEmpty(pendingTasks)) {
            return;
        }

        for (BiAgentTaskDO pendingTask : pendingTasks) {
            if (!tryMarkTaskRunning(pendingTask.getId(), pendingTask.getReportId())) {
                continue;
            }
            submitTask(pendingTask.getId());
        }

    }

    @Override
    public void markTimeoutRunningTasks() {
        long timeoutHours = Math.max(1, runningTimeoutHours);
        Date cutoffTime = new Date(System.currentTimeMillis() - timeoutHours * 60L * 60L * 1000L);
        Date now = new Date();
        int timeoutByStartedAt = baseMapper.update(null, new LambdaUpdateWrapper<BiAgentTaskDO>()
                .eq(BiAgentTaskDO::getStatus, TaskStatusEnum.RUNNING.getStatus())
                .lt(BiAgentTaskDO::getStartedAt, cutoffTime)
                .set(BiAgentTaskDO::getStatus, TaskStatusEnum.ERROR.getStatus())
                .set(BiAgentTaskDO::getErrorMsg, "task running timeout")
                .set(BiAgentTaskDO::getFinishedAt, now)
                .set(BiAgentTaskDO::getUpdatedAt, now));

        int timeoutByUpdatedAt = baseMapper.update(null, new LambdaUpdateWrapper<BiAgentTaskDO>()
                .eq(BiAgentTaskDO::getStatus, TaskStatusEnum.RUNNING.getStatus())
                .isNull(BiAgentTaskDO::getStartedAt)
                .lt(BiAgentTaskDO::getUpdatedAt, cutoffTime)
                .set(BiAgentTaskDO::getStatus, TaskStatusEnum.ERROR.getStatus())
                .set(BiAgentTaskDO::getErrorMsg, "task running timeout")
                .set(BiAgentTaskDO::getFinishedAt, now)
                .set(BiAgentTaskDO::getUpdatedAt, now));

        int totalTimeout = timeoutByStartedAt + timeoutByUpdatedAt;
        if (totalTimeout > 0) {
            log.warn("处理超时RUNNING的BI训练任务完成, timeoutCount: {}, cutoffTime: {}", totalTimeout, cutoffTime);
        }
    }

    @Override
    public void cleanHistoryNonFailedTasks() {
        java.sql.Date cutoffDate = java.sql.Date.valueOf(LocalDate.now().minusDays(7));
        int removed = baseMapper.delete(new LambdaQueryWrapper<BiAgentTaskDO>()
                .lt(BiAgentTaskDO::getTaskDay, cutoffDate)
                .ne(BiAgentTaskDO::getStatus, TaskStatusEnum.ERROR.getStatus()));
        if (removed > 0) {
            log.info("清理历史非失败BI训练任务完成, removed: {}, cutoffDate: {}", removed, cutoffDate);
        }
    }


    @Override
    public void cleanHistoryFailedTasks() {
        java.sql.Date cutoffDate = java.sql.Date.valueOf(LocalDate.now().minusDays(30));
        int removed = baseMapper.delete(new LambdaQueryWrapper<BiAgentTaskDO>()
                .lt(BiAgentTaskDO::getTaskDay, cutoffDate)
                .eq(BiAgentTaskDO::getStatus, TaskStatusEnum.ERROR.getStatus()));
        if (removed > 0) {
            log.info("清理历史失败BI训练任务完成, removed: {}, cutoffDate: {}", removed, cutoffDate);
        }
    }


    private void submitTask(Long taskId) {
        try {
            biAgentExecutor.execute(() -> executeTask(taskId));
        } catch (RejectedExecutionException e) {
            log.warn("BI训练线程池繁忙，任务执行被拒绝, taskId: {}", taskId, e);
            markTaskFailed(taskId, "biAgentExecutor rejected: " + safeErrorMessage(e));
        }
    }

    private boolean tryMarkTaskRunning(Long taskId, String reportId) {
        Date now = new Date();
        return baseMapper.tryMarkTaskRunning(taskId, reportId, TaskStatusEnum.PENDING.getStatus(),
                TaskStatusEnum.RUNNING.getStatus(), now) == 1;
    }


    private void executeTask(Long taskId) {
        BiAgentTaskDO taskDO = baseMapper.selectById(taskId);
        if (Objects.isNull(taskDO)
                || !StringUtils.equals(taskDO.getStatus(), TaskStatusEnum.RUNNING.getStatus())) {
            return;
        }

        try {
            BiAgentConfig config = JsonUtil.toObject(taskDO.getConfig(), BiAgentConfig.class);
            if (Objects.isNull(config) || StringUtils.isBlank(config.getReportId())) {
                throw new IllegalArgumentException("任务配置异常，reportId为空");
            }
            log.info("开始执行BI训练任务, taskId: {}, reportId: {}", taskDO.getId(), config.getReportId());
            Agent agent = biAgentService.createBiAgent(config);
            boolean callbackSuccess = biAgentService.biAgentCallback(agent, config);
            if (!callbackSuccess) {
                log.warn("BI训练任务回调失败，但训练已完成, taskId: {}, reportId: {}", taskDO.getId(), config.getReportId());
            }
            markTaskSuccess(taskId, agent);
            log.info("BI训练任务执行成功, taskId: {}, reportId: {}", taskDO.getId(), config.getReportId());

        } catch (Exception e) {
            log.error("BI训练任务执行失败, taskId: {}", taskId, e);
            markTaskFailed(taskId, safeErrorMessage(e));
        }
    }

    private void markTaskSuccess(Long taskId, Agent agent) {
        Date now = new Date();
        LambdaUpdateWrapper<BiAgentTaskDO> updateWrapper = new LambdaUpdateWrapper<BiAgentTaskDO>()
                .eq(BiAgentTaskDO::getId, taskId)
                .eq(BiAgentTaskDO::getStatus, TaskStatusEnum.RUNNING.getStatus())
                .set(BiAgentTaskDO::getStatus, TaskStatusEnum.SUCCESS.getStatus())
                .set(BiAgentTaskDO::getAgentId, agent == null ? null : agent.getId())
                .set(BiAgentTaskDO::getAgentName, agent == null ? null : agent.getName())
                .set(BiAgentTaskDO::getFinishedAt, now)
                .set(BiAgentTaskDO::getUpdatedAt, now)
                .set(BiAgentTaskDO::getErrorMsg, null);
        baseMapper.update(null, updateWrapper);
    }

    private void markTaskFailed(Long taskId, String errorMsg) {
        Date now = new Date();
        LambdaUpdateWrapper<BiAgentTaskDO> updateWrapper = new LambdaUpdateWrapper<BiAgentTaskDO>()
                .eq(BiAgentTaskDO::getId, taskId)
                .eq(BiAgentTaskDO::getStatus, TaskStatusEnum.RUNNING.getStatus())
                .set(BiAgentTaskDO::getStatus, TaskStatusEnum.ERROR.getStatus())
                .set(BiAgentTaskDO::getErrorMsg, StringUtils.left(errorMsg, MAX_ERROR_MSG_LENGTH))
                .set(BiAgentTaskDO::getFinishedAt, now)
                .set(BiAgentTaskDO::getUpdatedAt, now);
        baseMapper.update(null, updateWrapper);
    }

    private String safeErrorMessage(Exception e) {
        if (Objects.isNull(e)) {
            return "unknown error";
        }
        if (StringUtils.isNotBlank(e.getMessage())) {
            return e.getMessage();
        }
        return e.getClass().getSimpleName();
    }
}
