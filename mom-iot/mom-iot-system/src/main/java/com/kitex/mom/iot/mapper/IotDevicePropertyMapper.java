package com.kitex.mom.iot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.mom.iot.entity.IotDevicePropertyEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * IoT设备属性数据Mapper
 *
 * @author system
 * @date 2025-03-12
 */
@Mapper
public interface IotDevicePropertyMapper extends BaseMapper<IotDevicePropertyEntity> {

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
            @Param("endTime") String endTime
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