package com.kitex.mom.mdm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mdm.entity.MdmPhysicalTransformStandardLogEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 完整版本操作履历；没有修改、删除或裁剪审计入口。
 */
@Mapper
public interface MdmPhysicalTransformStandardLogMapper extends BaseMapper<MdmPhysicalTransformStandardLogEntity> {
}
