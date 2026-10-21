package com.kitex.mom.qms.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.qms.entity.QmsFinishedGoodsDecisionReversalEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface QmsFinishedGoodsDecisionReversalMapper
        extends MomBaseMapper<QmsFinishedGoodsDecisionReversalEntity> {

    /**
     * 跨租户取得已到期且尚未消费的授权候选；这里只读取身份和业务范围。
     * 真正变更必须重新进入候选所属租户、工厂并加行锁复验。
     */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT id,tenant_id,factory_code,authorization_expires_at "
            + "FROM qms_finished_goods_decision_reversal "
            + "WHERE del_flag='0' AND tenant_id IS NOT NULL AND status='APPROVED' "
            + "AND authorization_expires_at IS NOT NULL AND authorization_expires_at<=#{cutoff} "
            + "ORDER BY authorization_expires_at,id LIMIT #{limit}")
    List<QmsFinishedGoodsDecisionReversalEntity> selectExpiredCandidates(
            @Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
