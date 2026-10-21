package com.kitex.mom.mdm.sop;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.mom.mdm.dto.MdmFormalSopDTO;
import com.kitex.mom.mdm.entity.MdmSopOperationEntity;
import com.kitex.mom.mdm.mapper.MdmSopOperationMapper;
import lombok.RequiredArgsConstructor;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.Objects;
import static com.kitex.mom.mdm.sop.MdmSopSupport.require;

/**
 * 先独立提交永久命令身份，再进入领域事务。领域拒绝/进程中断后原号仍被占用，
 * 只允许原载荷重试或在取得行锁后明确取消未执行请求，不能换载荷占用原号。
 */
@Service
@RequiredArgsConstructor
public class MdmSopRequestClaimService {
    private final MdmSopOperationMapper mapper;
    private final MdmSopSupport support;
    private final SqlSessionTemplate session;

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void claim(String requestId, String action, String commandHash) {
        String factory = support.access(true);
        mapper.lockKey("FORMAL_SOP_CLAIM:" + support.tenant() + ":" + factory + ":" + requestId);
        session.clearCache();
        var previous = mapper.selectOne(new LambdaQueryWrapper<MdmSopOperationEntity>()
                .eq(MdmSopOperationEntity::getTenantId, support.tenant())
                .eq(MdmSopOperationEntity::getFactoryCode, factory)
                .eq(MdmSopOperationEntity::getRequestId, requestId));
        if (previous != null) {
            require(Objects.equals(previous.getCommandHash(), commandHash)
                            && Objects.equals(previous.getActorId(), support.actor()),
                    "该SOP请求号已永久绑定另一载荷、动作或人员");
            return;
        }
        var row = new MdmSopOperationEntity();
        row.setTenantId(support.tenant());
        row.setFactoryCode(factory);
        row.setRequestId(requestId);
        row.setAction(action);
        row.setCommandHash(commandHash);
        row.setActorId(support.actor());
        row.setActorName(support.actorName());
        row.setReceiptJson(JSONUtil.toJsonStr(new MdmFormalSopDTO.Receipt().setRequestId(requestId)
                .setAction(action).setCommandHash(commandHash).setStatus("PENDING").setOperatedAt(LocalDateTime.now())));
        require(mapper.insert(row) == 1, "SOP永久请求身份登记失败");
    }
}
