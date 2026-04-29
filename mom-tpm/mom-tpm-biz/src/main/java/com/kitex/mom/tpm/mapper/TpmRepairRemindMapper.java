package com.kitex.mom.tpm.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.data.datascope.MomBaseMapper;
import com.kitex.mom.tpm.entity.TpmRepairRemindEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TpmRepairRemindMapper extends MomBaseMapper<TpmRepairRemindEntity> {


    Page<TpmRepairRemindEntity> pageList(Page toMpPage, @Param("query") TpmRepairRemindEntity tpmRepairRemind);

    /**
     * 根据参数获取维修提醒配置
     *
     * @param faultClassId 故障类型
     * @param deviceCode   设备编码
     * @param shiftCode    班次编码
     * @return 符合条件的配置列表
     */
    List<TpmRepairRemindEntity> getTarget(@Param("faultClassId") Long faultClassId,
                                          @Param("deviceCode") String deviceCode,
                                          @Param("shiftCode") String shiftCode);
}