package com.kitex.mom.mdm.sop;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.mom.mdm.dto.MdmFormalSopDTO;
import com.kitex.mom.mdm.entity.MdmSopOperationEntity;
import com.kitex.mom.mdm.mapper.MdmSopOperationMapper;
import lombok.RequiredArgsConstructor;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.Objects;
import static com.kitex.mom.mdm.sop.MdmSopSupport.*;

/** 永久命令账；业务写入和回执写入必须处于同一个调用方事务。 */
@Component
@RequiredArgsConstructor
public class MdmSopJournal {
    private final MdmSopOperationMapper mapper;
    private final MdmSopSupport support;
    private final SqlSessionTemplate session;
    private final MdmSopRequestClaimService claims;

    public record Attempt(String requestId, String action, String commandHash, MdmFormalSopDTO.Receipt previous) { }

    public Attempt begin(String requestId, String action, Object command) {
        require(requestId != null && requestId.matches("[A-Za-z0-9_-]{16,64}"), "SOP操作需要16至64位永久请求号");
        String hash = hash("SOP_COMMAND_V1", action, command, support.actor());
        claims.claim(requestId, action, hash);
        session.clearCache();
        MdmSopOperationEntity old = mapper.selectOne(scope().eq(MdmSopOperationEntity::getRequestId, requestId).last("FOR UPDATE"));
        require(old != null && Objects.equals(hash, old.getCommandHash()) && Objects.equals(support.actor(), old.getActorId()),
                "该SOP永久请求号已用于不同载荷、动作或人员，不能换内容重试");
        var receipt = JSONUtil.toBean(old.getReceiptJson(), MdmFormalSopDTO.Receipt.class);
        require(!"CANCELLED".equals(receipt.getStatus()), "原SOP请求已明确取消，不允许再执行或复用该请求号");
        return new Attempt(requestId, action, hash, "PENDING".equals(receipt.getStatus()) ? null : receipt);
    }

    public void lock(String key) {
        mapper.lockKey("FORMAL_SOP:" + support.tenant() + ":" + support.access(true) + ":" + key);
        session.clearCache();
    }

    public MdmFormalSopDTO.Receipt finish(Attempt attempt, Long versionId, String targetId,
                                         String status, String resultHash, Object before) {
        var receipt = new MdmFormalSopDTO.Receipt().setRequestId(attempt.requestId()).setAction(attempt.action())
                .setCommandHash(attempt.commandHash()).setTargetId(targetId).setStatus(status)
                .setResultHash(resultHash).setOperatedAt(LocalDateTime.now());
        var operation = mapper.selectOne(scope().eq(MdmSopOperationEntity::getRequestId, attempt.requestId()).last("FOR UPDATE"));
        require(operation != null && "PENDING".equals(JSONUtil.parseObj(operation.getReceiptJson()).getStr("status")),
                "SOP请求已经终结，不能覆盖永久回执");
        operation.setVersionId(versionId);
        operation.setBeforeJson(before == null ? null : before instanceof String ? (String) before : JSONUtil.toJsonStr(before));
        operation.setReceiptJson(JSONUtil.toJsonStr(receipt));
        require(mapper.updateById(operation) == 1, "SOP永久回执保存失败，业务操作回滚");
        return receipt;
    }

    /** 行锁等待已在途的业务事务；只取消尚未执行的原请求，不撤销任何已提交业务。 */
    @Transactional(rollbackFor = Exception.class)
    public MdmFormalSopDTO.Receipt cancel(String requestId) {
        String factory = support.access(true);
        require(requestId != null && requestId.matches("[A-Za-z0-9_-]{16,64}"), "原请求号不正确");
        mapper.lockKey("FORMAL_SOP_CLAIM:" + support.tenant() + ":" + factory + ":" + requestId);
        session.clearCache();
        var operation = mapper.selectOne(scope().eq(MdmSopOperationEntity::getRequestId, requestId)
                .last("FOR UPDATE"));
        if (operation == null) {
            // 即使原HTTP还在上传/格式校验，也先占住取消墓碑，阻止稍后迟到的写入。
            String cancelledHash = hash("CANCEL_UNEXECUTED_SOP", support.actor(), requestId);
            var cancelled = new MdmFormalSopDTO.Receipt().setRequestId(requestId).setAction("CANCEL")
                    .setCommandHash(cancelledHash).setStatus("CANCELLED").setOperatedAt(LocalDateTime.now());
            var tombstone = new MdmSopOperationEntity();
            tombstone.setTenantId(support.tenant());
            tombstone.setFactoryCode(factory);
            tombstone.setRequestId(requestId);
            tombstone.setAction("CANCEL");
            tombstone.setCommandHash(cancelledHash);
            tombstone.setActorId(support.actor());
            tombstone.setActorName(support.actorName());
            tombstone.setReceiptJson(JSONUtil.toJsonStr(cancelled));
            require(mapper.insert(tombstone) == 1, "原请求取消墓碑保存失败");
            return cancelled;
        }
        require(Objects.equals(operation.getActorId(), support.actor()), "不能取消其他人员的SOP请求");
        var receipt = JSONUtil.toBean(operation.getReceiptJson(), MdmFormalSopDTO.Receipt.class);
        if (!"PENDING".equals(receipt.getStatus())) return receipt;
        receipt.setStatus("CANCELLED").setOperatedAt(LocalDateTime.now());
        operation.setReceiptJson(JSONUtil.toJsonStr(receipt));
        require(mapper.updateById(operation) == 1, "未执行SOP请求取消失败");
        return receipt;
    }

    /** 只能恢复当前实名人员的原请求，不泄露他人操作载荷。 */
    public MdmFormalSopDTO.Receipt receipt(String requestId) {
        support.access(false);
        var operation = mapper.selectOne(scope().eq(MdmSopOperationEntity::getRequestId, requestId)
                .eq(MdmSopOperationEntity::getActorId, support.actor()));
        return operation == null ? null : JSONUtil.toBean(operation.getReceiptJson(), MdmFormalSopDTO.Receipt.class);
    }

    public Page<MdmSopOperationEntity> page(Long versionId, long current, long size) {
        support.access(false);
        require(current > 0 && size > 0 && size <= 50, "审计分页每页1至50条");
        return mapper.selectPage(new Page<>(current, size), scope()
                .eq(MdmSopOperationEntity::getVersionId, versionId)
                .orderByDesc(MdmSopOperationEntity::getId));
    }

    private LambdaQueryWrapper<MdmSopOperationEntity> scope() {
        return new LambdaQueryWrapper<MdmSopOperationEntity>()
                .eq(MdmSopOperationEntity::getTenantId, support.tenant())
                .eq(MdmSopOperationEntity::getFactoryCode, support.access(false));
    }
}
