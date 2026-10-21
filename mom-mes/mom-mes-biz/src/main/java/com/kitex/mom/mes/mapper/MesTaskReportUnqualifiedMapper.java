package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.mom.mes.entity.MesTaskReportUnqualifiedEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 生产报工-不合格品上报
 *
 * @author kitex
 * @since 2025-05-06
 */
@Mapper
public interface MesTaskReportUnqualifiedMapper extends BaseMapper<MesTaskReportUnqualifiedEntity> {

    /** 新原计量写前检查实际数据库保护，不以本地配置或缓存代表就绪。 */
    @InterceptorIgnore(tenantLine = "true", dataPermission = "true")
    @Select("SELECT \"mom_mes\".mom_production_ng_source_ready() AND \"mom_mes\".mom_production_ng_identification_ready_v1()")
    Boolean sourceReady();
}
