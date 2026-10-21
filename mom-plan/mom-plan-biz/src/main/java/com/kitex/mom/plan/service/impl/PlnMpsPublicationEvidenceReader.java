package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;
import com.kitex.mom.mes.dto.MesApsLocalScheduleDTO;
import com.kitex.mom.mes.dto.MesApsPublicationProbeDTO;
import com.kitex.mom.plan.entity.PlnResourceReplanCaseEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/**
 * MPS永久发布链的只读取证器，不负责审批或修改正式计划。
 *
 * <p>先找原业务号，再逐段核对应用及补偿完整载荷。原回执成功与当前计划匹配必须同时成立；
 * 已补偿的旧SUCCESS不能当成新应用，新段只使用原号衍生的固定号，最多10段。</p>
 */
@Component
@RequiredArgsConstructor
public class PlnMpsPublicationEvidenceReader {
    private static final int MAX_SEGMENTS = 10;
    private final RemoteMesTaskService mesService;

    /** 一个物理应用段；applied为空表示未观察到此段提交，须按正常A3重新复核。 */
    public record Segment(String businessRequestId, String applyRequestId, String compensateRequestId,
                          String beforeFingerprint, int index, MesApsPublicationProbeDTO.Reply applied,
                          MesApsPublicationProbeDTO.Reply lastCompensation, String originalPublisher) {
    }

    /** 只读完整原号目录；不取最近一条猜测，也不把回退链当发布链。 */
    public List<MesApsPublicationProbeDTO.ReceiptRef> catalog(PlnResourceReplanCaseEntity source) {
        R<List<MesApsPublicationProbeDTO.ReceiptRef>> response = mesService.apsPublicationCatalog(
                new MesApsPublicationProbeDTO.Scope().setCaseId(source.getId())
                        .setPlanNo(source.getIssueNo()).setFactoryCode(source.getFactoryCode()));
        require(response != null && response.isOk() && response.getData() != null,
                "MES永久发布目录不可用，禁止推断未应用或换新号");
        return response.getData();
    }

    /**
     * 读取原号分段链。补偿存在时不要求之前的应用仍匹配当前计划；只要求最后补偿精确恢复，
     * 且恢复指纹仍等于原审批MES指纹，才能允许下一段重新走完整A3。
     */
    public Segment read(PlnResourceReplanCaseEntity source, String requestId,
                        List<MesApsLocalScheduleDTO.OperationUpdate> proposed,
                        List<MesApsLocalScheduleDTO.OperationUpdate> originals, List<String> allCodes) {
        return read(source, requestId, proposed, originals, allCodes, null);
    }

    /** 节点全集与主工序清单同序冻结；旧重放仍走原五参合同，不补节点或改变原命令。 */
    public Segment read(PlnResourceReplanCaseEntity source, String requestId,
                        List<MesApsLocalScheduleDTO.OperationUpdate> proposed,
                        List<MesApsLocalScheduleDTO.OperationUpdate> originals, List<String> allCodes,
                        List<String> allNodeCodes) {
        require(StrUtil.isNotBlank(requestId) && requestId.length() <= 60,
                "MPS业务发布永久号须为1至60位，预留MES固定重投/补偿后缀");
        String before = source.getMesExecutionFingerprint();
        String publisher = null;
        MesApsPublicationProbeDTO.Reply lastCompensation = null;
        for (int index = 0; index < MAX_SEGMENTS; index++) {
            String applyId = index == 0 ? requestId + ":MES" : requestId + ":MES-RETRY:" + index;
            String compensateId = index == 0 ? requestId + ":MES-COMPENSATE" : applyId + ":COMPENSATE";
            var applied = probe(command(source, applyId, before, proposed), allCodes, allNodeCodes);
            if ("NOT_OBSERVED".equals(applied.getState())) {
                return new Segment(requestId, applyId, compensateId, before, index, null, lastCompensation, publisher);
            }
            requireApplied(source, applied, applyId, before, proposed.size());
            if (publisher == null) publisher = applied.getAppliedBy();
            require(Objects.equals(publisher, applied.getAppliedBy()), "原发布链存在不同执行人，禁止自动续接");
            var compensated = probe(command(source, compensateId,
                    applied.getReceipt().getAfterExecutionFingerprint(), originals), allCodes, allNodeCodes);
            if ("NOT_OBSERVED".equals(compensated.getState())) {
                require("APPLIED_MATCHED".equals(applied.getState()),
                        "MES原应用仍在，但当前计划已漂移；必须跨账对账，禁止换号或覆盖现场");
                return new Segment(requestId, applyId, compensateId, before, index, applied, lastCompensation, publisher);
            }
            requireApplied(source, compensated, compensateId,
                    applied.getReceipt().getAfterExecutionFingerprint(), originals.size());
            require(Objects.equals(publisher, compensated.getAppliedBy()), "补偿人员与原发布链不一致，须人工对账");
            // 旧补偿可能已被下一段再次应用；只有下一段不存在时，才检查当前是否仍恢复。
            lastCompensation = compensated;
            before = compensated.getReceipt().getAfterExecutionFingerprint();
            require(Objects.equals(before, source.getMesExecutionFingerprint()),
                    "补偿后现场执行指纹已不同于原审批，不能重新套用原审批下发");
        }
        throw new CheckedException("原发布链达到10段恢复上限，须核对失败原因，不得无限生成重投号");
    }

    /** 最后一段尚未应用时，前一补偿必须仍对应当前完整MES计划。 */
    public void requireRestored(Segment segment) {
        if (segment.lastCompensation() == null || segment.applied() != null) return;
        require("APPLIED_MATCHED".equals(segment.lastCompensation().getState())
                        && segment.lastCompensation().getCurrent() != null
                        && segment.lastCompensation().getCurrent().getFacts() != null
                        && Objects.equals(segment.beforeFingerprint(),
                        segment.lastCompensation().getCurrent().getFacts().getExecutionFingerprint()),
                "最后补偿后的现场计划/执行事实已变化，禁止按旧审批重投");
    }

    private MesApsLocalScheduleDTO.ApplyCommand command(PlnResourceReplanCaseEntity source, String id,
                                                        String fingerprint,
                                                        List<MesApsLocalScheduleDTO.OperationUpdate> operations) {
        return new MesApsLocalScheduleDTO.ApplyCommand().setRequestId(id).setReplanCaseId(source.getId())
                .setFactoryCode(source.getFactoryCode()).setPlanNo(source.getIssueNo())
                .setExpectedExecutionFingerprint(fingerprint).setOperations(operations);
    }

    private MesApsPublicationProbeDTO.Reply probe(MesApsLocalScheduleDTO.ApplyCommand command,
                                                 List<String> allCodes, List<String> allNodeCodes) {
        var response = mesService.probeApsPublication(new MesApsPublicationProbeDTO.Query()
                .setExpectedCommand(command).setExpectedProcessCodes(allCodes).setExpectedSourceRouteNodeCodes(allNodeCodes));
        require(response != null && response.isOk() && response.getData() != null,
                "MES原应用/补偿取证未返回完整回执，请保留原号核对");
        var reply = response.getData();
        require(StrUtil.isNotBlank(reply.getState())
                        && List.of("NOT_OBSERVED", "APPLIED_MATCHED", "APPLIED_DRIFTED").contains(reply.getState()),
                "MES取证状态未知，不能视为应用或取消");
        return reply;
    }

    private void requireApplied(PlnResourceReplanCaseEntity source, MesApsPublicationProbeDTO.Reply reply,
                                String id, String before, int count) {
        var receipt = reply.getReceipt();
        require(Boolean.TRUE.equals(reply.getCommandMatched()) && receipt != null
                        && Objects.equals(receipt.getRequestId(), id) && Objects.equals(receipt.getPlanNo(), source.getIssueNo())
                        && Objects.equals(receipt.getReplanCaseId(), source.getId())
                        && Objects.equals(receipt.getBeforeExecutionFingerprint(), before)
                        && StrUtil.isNotBlank(receipt.getAfterExecutionFingerprint())
                        && Objects.equals(receipt.getUpdatedOperationCount(), count) && count > 0
                        && StrUtil.isNotBlank(reply.getAppliedBy()) && receipt.getAppliedTime() != null,
                "原永久回执缺实名、精确作用范围、完整载荷或真实应用证明，禁止补造历史");
        try {
            require(StrUtil.isNotBlank(reply.getAppliedTimeExact()), "原MES应用时刻缺精确凭证");
            // 仅在PLAN内部还原精确时刻；不改变原永久命令/回执指纹或公共日期规则。
            receipt.setAppliedTime(LocalDateTime.parse(reply.getAppliedTimeExact()));
        } catch (DateTimeParseException ex) {
            throw new CheckedException("原MES精确应用时刻不可解析");
        }
    }

    private void require(boolean valid, String message) {
        if (!valid) throw new CheckedException(message);
    }
}
