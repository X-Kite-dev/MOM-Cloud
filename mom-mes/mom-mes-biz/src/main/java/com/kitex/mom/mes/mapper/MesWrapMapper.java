package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.mom.mes.entity.MesWrapEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 箱码管理
 *
 * @author kitex
 * @since 2025-03-25
 */
@Mapper
public interface MesWrapMapper extends BaseMapper<MesWrapEntity> {

    IPage<MesWrapEntity> queryPage(Page<MesWrapEntity> pageParam, @Param("query") MesWrapEntity query);

} 