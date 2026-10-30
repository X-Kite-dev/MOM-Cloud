package com.kitex.mom.iot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.mom.iot.entity.IotDevicePropertyEntity;
import com.kitex.mom.iot.agent.dto.IotTelemetryAggregateRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.time.LocalDateTime;

/**
 * IoT设备属性数据Mapper
 *
 * @author system
 * @date 2025-03-12
 */
@Mapper
public interface IotDevicePropertyMapper extends BaseMapper<IotDevicePropertyEntity> {
    @org.apache.ibatis.annotations.Select("SELECT DISTINCT ON (identifier) * FROM iot_device_property WHERE del_flag='0' AND scope_verified=true AND device_id=#{deviceId} AND tenant_id=#{tenantId} AND factory_code=#{factory} ORDER BY identifier,collect_time DESC NULLS LAST,id DESC")
    List<IotDevicePropertyEntity> latestScoped(@Param("deviceId") Long deviceId,@Param("tenantId") Long tenantId,@Param("factory") String factory);

    /**
     * 聚合精确设备在一个实际生产区间内、归属已核验的属性采样。
     * 数值统计只采信质量为 GOOD 且能够安全解析为十进制数的值。
     */
    List<IotTelemetryAggregateRow> aggregateProcessTelemetry(
            @Param("tenantId") Long tenantId,
            @Param("factoryCode") String factoryCode,
            @Param("deviceId") Long deviceId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    /** 精确设备、参数和生产时间窗的已核验采样；额外一条用于识别截断。 */
    List<IotDevicePropertyEntity> selectProcessDeviationSamples(
            @Param("tenantId") Long tenantId,
            @Param("factoryCode") String factoryCode,
            @Param("deviceId") Long deviceId,
            @Param("identifier") String identifier,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            @Param("limit") int limit);

    /**
     * 查询设备元数据
     *
     * @param page       分页对象
     * @param deviceIds  设备ID列表
     * @param identifier 物模型标识符
     * @param startTime  开始时间
     * @param endTime    结束时间
     * @return 设备属性数据分页结果
     */
    IPage<IotDevicePropertyEntity> selectMetadataPage(
            Page<IotDevicePropertyEntity> page,
            @Param("deviceIds") List<Long> deviceIds,
            @Param("identifier") String identifier,
            @Param("startTime") String startTime,
            @Param("endTime") String endTime,
            @Param("productId") Long productId,@Param("tenantId") Long tenantId,@Param("factory") String factory
    );

    /**
     * 根据产品ID查询设备属性数据
     *
     * @param page       分页对象
     * @param productId  产品ID
     * @param identifier 物模型标识符
     * @param startTime  开始时间
     * @param endTime    结束时间
     * @return 设备属性数据分页结果
     */
    IPage<IotDevicePropertyEntity> selectByProductId(
            Page<IotDevicePropertyEntity> page,
            @Param("productId") Long productId,
            @Param("identifier") String identifier,
            @Param("startTime") String startTime,
            @Param("endTime") String endTime
    );
}
