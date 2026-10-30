package com.kitex.mom.qms.service.impl;

import static com.kitex.mom.qms.dto.inspection.QmsInspectionRevisionOutcomeContract.*;
import static com.kitex.mom.qms.service.impl.QmsInspectionHistorySupport.require;

import com.fasterxml.jackson.databind.*;
import com.kitex.common.core.jackson.MomJavaTimeModule;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.qms.entity.*;
import com.kitex.mom.qms.mapper.*;
import com.kitex.mom.qms.service.*;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

/** 本地父任务→投递→run锁内真正改判；不调用MES/MDM/WMS，不覆盖原历史。 */
@Service
@RequiredArgsConstructor
public class QmsInspectionRevisionCommitService {
    private final QmsInspectionRevisionRunMapper runs;
    private final QmsInspectionRevisionCoordinationMapper coordination;
    private final QmsInspectionEventMapper tasks;
    private final QmsInspectionProcedureTaskMapper taskWriter;
    private final QmsInspectionEventService journal;
    private final InspectionProcedureHistoryService histories;
    private final QmsPlanEventOutboxService plan;
    private static final ObjectMapper JSON =
            new ObjectMapper().registerModule(new MomJavaTimeModule());

    @Transactional(
            rollbackFor = Exception.class,
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED)
    public void commit(Long id) {
        QmsInspectionEventWorker.scope();
        require(Integer.valueOf(1).equals(runs.ready()), "正式修订结构未就绪");
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        var e = QmsInspectionRevisionRunCodec.execution(coordination, id, tenant, factory);
        var observed = tasks.procedureSnapshot(tenant, factory, e.getTaskId(), true);
        require(observed != null, "原检验任务已不存在");
        var d = coordination.delivery(id, tenant, factory, true);
        var run = runs.run(id, tenant, factory, true);
        require(run != null, "缺少正式执行请求");
        var o = runs.outcome(id, tenant, factory);
        if (o != null) {
            require(
                    "COMMITTED".equals(QmsInspectionRevisionRunCodec.outcome(e, o).decision()),
                    "正式执行已中止，不能再次提交");
            return;
        }
        require(
                "COMMIT".equals(run.getPhase())
                        && "READY".equals(run.getStatus())
                        && Objects.equals(run.getCreatedBy(), e.getExecutorId())
                        && "RESERVED".equals(d.getStatus())
                        && coordination.cancel(id, tenant, factory) == null,
                "正式提交阶段或准备事实不一致");
        var seal = QmsInspectionRevisionRunCodec.seal(runs, e);
        require(seal != null, "缺少MES封存回执");
        var original =
                com.kitex.mom.qms.dto.inspection.QmsInspectionRevisionExecutionContract.parse(
                                e.getIntent())
                        .request();
        JsonNode current = JSON.valueToTree(observed);
        var fields = original.get("source").fieldNames();
        while (fields.hasNext()) {
            String n = fields.next();
            JsonNode v = current.has(n) ? current.get(n) : JSON.nullNode();
            if ("standardId".equals(n) && !v.isNull())
                v = JSON.getNodeFactory().textNode(v.asText());
            require(v.equals(original.get("source").get(n)), "当前任务依据已变化，不能正式改写旧判定");
        }
        var chain = journal.originals("PROCEDURE", e.getTaskId());
        require(
                chain.size() == 2
                        && chain.get(1)
                                .getId()
                                .toString()
                                .equals(original.path("baseEventId").textValue())
                        && chain.get(1)
                                .getFingerprint()
                                .equals(original.path("baseFingerprint").textValue()),
                "提交前原事件头已变化");
        QmsInspectionRevisionRunService.ready(run, "COMMITTING");
        require(runs.progress(run) == 1, "本地提交阶段未保存");
        run.setVersion(run.getVersion() + 1);
        Date now = new Date();
        var task = taskWriter.selectById(e.getTaskId());
        require(
                task != null
                        && task.getTenantId().equals(tenant)
                        && task.getFactoryCode().equals(factory),
                "改判对象已越域");
        task.setResult("UN_QUALIFIED")
                .setStatus("UNHANDLED")
                .setUsableNum(0)
                .setHandleNum(0)
                .setLatestInspectionTime(now);
        require(taskWriter.updateById(task) == 1, "正式改判未保存");
        var event = histories.checkRevision(task, id, e.getExecutorId(), now);
        require(event != null && event.getSequenceNo() == 3, "修订结果未形成连续第三原件");
        var planFact = plan.enqueueIsolation("PROCEDURE", task.getId());
        require(planFact != null && planFact.getEventId() != null, "质量变更计划待发未保存");
        String raw = committed(e.getIntent(), seal.getSealFingerprint(), event.getPayload(), now);
        require(
                runs.recordOutcome(
                                new QmsInspectionRevisionOutcomeEntity()
                                        .setId(id)
                                        .setTenantId(tenant)
                                        .setFactoryCode(factory)
                                        .setTaskId(e.getTaskId())
                                        .setDecision("COMMITTED")
                                        .setPayload(raw)
                                        .setFingerprint(hash(raw))
                                        .setCreatedAt(now))
                        == 1,
                "正式提交原件未保存");
        QmsInspectionRevisionRunService.ready(run, "APPLY");
        require(runs.progress(run) == 1, "已提交结果的现场同步阶段未保存");
    }
}
