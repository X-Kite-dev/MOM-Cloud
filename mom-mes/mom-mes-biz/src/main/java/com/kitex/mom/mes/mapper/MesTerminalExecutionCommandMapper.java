package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.entity.MesTerminalExecutionCommandEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 永久命令查重包含所有历史行；逻辑删除不能释放原号或未决占用。 */
@Mapper
public interface MesTerminalExecutionCommandMapper extends BaseMapper<MesTerminalExecutionCommandEntity> {
    /** 原号事务锁必须先于工单业务锁和行锁。 */
    @Select("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(#{key}, 0))")
    Integer lockRequest(@Param("key") String key);

    /** 原号只按实际租户查询，工厂和实名由服务再次严格核对。 */
    @Select("SELECT * FROM mes_terminal_execution_command WHERE tenant_id=#{tenantId} AND request_id=#{requestId}")
    MesTerminalExecutionCommandEntity permanent(@Param("tenantId") Long tenantId,
                                               @Param("requestId") String requestId);

    /** 调用方已持有稳定工单锁与派工行锁，再锁永久命令行。 */
    @Select("SELECT * FROM mes_terminal_execution_command WHERE tenant_id=#{tenantId} AND request_id=#{requestId} FOR UPDATE")
    MesTerminalExecutionCommandEntity locked(@Param("tenantId") Long tenantId,
                                            @Param("requestId") String requestId);

    /** 不追加行锁，避免在报工/计划等原锁序中引入反向锁。 */
    @Select("SELECT * FROM mes_terminal_execution_command WHERE tenant_id=#{tenantId} AND factory_code=#{factoryCode} "
            + "AND arrange_id=#{arrangeId} AND status IN ('PREPARED','EXECUTING','UNKNOWN') LIMIT 2")
    java.util.List<MesTerminalExecutionCommandEntity> pending(@Param("tenantId") Long tenantId,
                                                             @Param("factoryCode") String factoryCode,
                                                             @Param("arrangeId") Long arrangeId);
}
