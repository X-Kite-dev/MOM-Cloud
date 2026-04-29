package com.kitex.mom.mdm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kitex.mom.mdm.entity.MdmFactoryModelEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 工厂建模 Mapper 接口
 *
 * @author claude
 * @date 2025-07-30
 */
@Mapper
public interface MdmFactoryModelMapper extends BaseMapper<MdmFactoryModelEntity> {

    /**
     * 根据父节点ID查询子节点列表
     */
    List<MdmFactoryModelEntity> selectByParentId(@Param("parentId") Long parentId);

    /**
     * 查询指定级别的所有节点
     */
    List<MdmFactoryModelEntity> selectByLevel(@Param("level") Integer level);

    /**
     * 查询指定类型的所有节点
     */
    List<MdmFactoryModelEntity> selectByType(@Param("type") String type);

    /**
     * 根据节点ID查询所有子孙节点
     */
    List<MdmFactoryModelEntity> selectDescendants(@Param("nodeId") Long nodeId);

    /**
     * 根据节点ID查询所有祖先节点
     */
    List<MdmFactoryModelEntity> selectAncestors(@Param("nodeId") Long nodeId);

    /**
     * 检查节点编码是否重复
     */
    int countByCode(@Param("code") String code, @Param("excludeId") Long excludeId);

    /**
     * 查询树形结构数据
     */
    List<MdmFactoryModelEntity> selectTreeList();
}