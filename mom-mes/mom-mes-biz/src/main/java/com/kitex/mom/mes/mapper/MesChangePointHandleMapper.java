package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.mom.mes.entity.MesChangePointHandleEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 变化点处理
 *
 * @author kitex
 * @since 2025-05-26
 */
@Mapper
public interface MesChangePointHandleMapper extends BaseMapper<MesChangePointHandleEntity> {

    IPage<MesChangePointHandleEntity> queryPage(Page<MesChangePointHandleEntity> pageParam, @Param("query") MesChangePointHandleEntity query);

}
