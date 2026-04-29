package com.kitex.mom.mdm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mdm.entity.MdmClassesEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalTime;

@Mapper
public interface MdmClassesMapper extends BaseMapper<MdmClassesEntity> {

    MdmClassesEntity getInfoByNow(@Param("now") LocalTime now);
}
