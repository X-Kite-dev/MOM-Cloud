package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.mom.mes.entity.MesWipTimeoutHandleEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MesWipTimeoutHandleMapper extends BaseMapper<MesWipTimeoutHandleEntity> {
    IPage<MesWipTimeoutHandleEntity> queryPage(Page<MesWipTimeoutHandleEntity> page,
                                               @Param("query") MesWipTimeoutHandleEntity query);
}
