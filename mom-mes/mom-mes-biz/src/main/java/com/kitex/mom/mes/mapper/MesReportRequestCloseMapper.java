package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.entity.MesReportRequestCloseEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 封存账查重包含全部历史行，不能逻辑删除后释放原号。
 */
@Mapper
public interface MesReportRequestCloseMapper extends BaseMapper<MesReportRequestCloseEntity> {

    /**
     * 仅按租户原号查永久账，调用方再核实际工厂及实名。
     */
    @Select("SELECT * FROM mes_report_request_close WHERE tenant_id=#{tenantId} AND request_id=#{requestId}")
    MesReportRequestCloseEntity findPermanent(@Param("tenantId") Long tenantId, @Param("requestId") String requestId);
}
