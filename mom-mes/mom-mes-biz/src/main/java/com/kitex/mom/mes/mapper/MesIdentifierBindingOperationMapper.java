package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mes.entity.MesIdentifierBindingOperationEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 不可变节点账，只由统一报工协调器在本地事务追加，数据库再次核对正式报工。
 */
@Mapper
public interface MesIdentifierBindingOperationMapper extends BaseMapper<MesIdentifierBindingOperationEntity> {
}
