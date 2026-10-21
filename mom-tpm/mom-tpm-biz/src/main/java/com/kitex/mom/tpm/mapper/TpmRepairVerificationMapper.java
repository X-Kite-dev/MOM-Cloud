package com.kitex.mom.tpm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.tpm.entity.TpmRepairVerification;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TpmRepairVerificationMapper extends BaseMapper<TpmRepairVerification> {
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(#{key},0))")
    void lockKey(@Param("key") String key);

    @Select("SELECT h.id,h.task_id AS \"taskId\",h.device_id AS \"deviceId\",h.device_code AS \"deviceCode\",h.mode,"
            + "h.before_json AS \"beforeJson\",h.after_json AS \"afterJson\",h.create_time AS \"createTime\" "
            + "FROM tpm_repair_device_hold h WHERE h.tenant_id=#{tenantId} AND h.factory_code=#{factoryCode} AND h.task_id=#{taskId} LIMIT 1")
    java.util.Map<String, Object> deviceHold(@Param("tenantId") Long tenantId, @Param("factoryCode") String factoryCode, @Param("taskId") Long taskId);

    @Select("SELECT s.id,s.arrange_id AS \"arrangeId\",s.arrange_code AS \"arrangeCode\","
            + "s.segment_id AS \"segmentId\",s.stop_time AS \"stopTime\",s.segment_mode AS \"segmentMode\" "
            + "FROM mes_repair_arrange_stop s WHERE s.tenant_id=#{tenantId} AND s.factory_code=#{factoryCode} "
            + "AND s.repair_task_id=#{taskId} ORDER BY s.id")
    java.util.List<java.util.Map<String, Object>> arrangeStops(@Param("tenantId") Long tenantId,
                                                               @Param("factoryCode") String factoryCode, @Param("taskId") Long taskId);
}
