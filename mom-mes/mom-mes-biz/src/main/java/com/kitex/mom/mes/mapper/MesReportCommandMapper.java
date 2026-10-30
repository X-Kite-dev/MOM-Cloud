package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.entity.MesReportCommandEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 原始报工命令账持久层；查重包括所有历史行，不使用逻辑删除过滤。
 */
@Mapper
public interface MesReportCommandMapper extends BaseMapper<MesReportCommandEntity> {

    /**
     * 同租户原号串行化，先于工单和任何行锁。
     */
    @Select("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(#{key}, 0))")
    Integer lockRequest(@Param("key") String key);

    /**
     * 永久请求号查重。工厂及发起人由领域服务再次核对，禁止跨厂复用原号。
     */
    @Select("SELECT * FROM mes_report_command WHERE tenant_id=#{tenantId} AND request_id=#{requestId}")
    MesReportCommandEntity findPermanent(@Param("tenantId") Long tenantId, @Param("requestId") String requestId);
}
