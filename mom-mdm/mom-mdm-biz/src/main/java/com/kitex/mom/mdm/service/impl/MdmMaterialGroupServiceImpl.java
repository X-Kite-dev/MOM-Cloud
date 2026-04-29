package com.kitex.mom.mdm.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.tree.Tree;
import cn.hutool.core.lang.tree.TreeNode;
import cn.hutool.core.lang.tree.TreeUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.constant.CommonConstants;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.vo.ErrorMessage;
import com.kitex.mom.mdm.entity.MdmMaterialGroupEntity;
import com.kitex.mom.mdm.mapper.MdmMaterialGroupMapper;
import com.kitex.mom.mdm.service.MdmMaterialGroupService;
import com.kitex.mom.mdm.vo.excel.MdmMaterialGroupExcelVO;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.BindingResult;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 物料分组表
 *
 * @author kitex
 * @date 2024-09-18 10:00:05
 */
@Service
@AllArgsConstructor
public class MdmMaterialGroupServiceImpl extends ServiceImpl<MdmMaterialGroupMapper, MdmMaterialGroupEntity>
        implements MdmMaterialGroupService {

    /**
     * 树形数据
     *
     * @param parentId  父节点ID
     * @param groupCode 分组编码
     * @param groupName 分组名称
     * @return 树形物料分组
     */
    @Override
    public List<Tree<Long>> getMdmMaterialGroupTree(Long parentId, String groupCode, String groupName, Boolean enabled) {
        Long parent = parentId == null ? CommonConstants.MENU_TREE_ROOT_ID : parentId;
        List<TreeNode<Long>> collect = this
                .list(Wrappers.<MdmMaterialGroupEntity>lambdaQuery()
                        .eq(Objects.nonNull(parentId), MdmMaterialGroupEntity::getParentId, parentId)
                        .like(StrUtil.isNotBlank(groupCode), MdmMaterialGroupEntity::getGroupCode, groupCode)
                        .like(StrUtil.isNotBlank(groupName), MdmMaterialGroupEntity::getGroupName, groupName)
                        .eq(enabled != null, MdmMaterialGroupEntity::getEnabled, enabled)
                        .orderByAsc(MdmMaterialGroupEntity::getSortOrder)
                        .orderByDesc(MdmMaterialGroupEntity::getCreateTime))
                .stream()
                .map(getNodeFunction())
                .collect(Collectors.toList());
        // 模糊查询 不组装树结构 直接返回 表格方便编辑
        if (StrUtil.isNotBlank(groupCode) || StrUtil.isNotBlank(groupName)) {
            return collect.stream().map(node -> {
                Tree<Long> tree = new Tree<>();
                tree.putAll(node.getExtra());
                BeanUtils.copyProperties(node, tree);
                return tree;
            }).collect(Collectors.toList());
        }
        return TreeUtil.build(collect, parent);
    }

    /**
     * 导入excel 表格
     *
     * @param excelVOList   物料分组列表
     * @param bindingResult 错误信息列表
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public R importMaterialGroup(List<MdmMaterialGroupExcelVO> excelVOList, BindingResult bindingResult) {
        // 通用校验获取失败的数据
        List<ErrorMessage> errorMessageList = (List<ErrorMessage>) bindingResult.getTarget();
        Map<String, Long> materialGroupMap = this.list()
                .stream()
                .collect(
                        Collectors.toMap(MdmMaterialGroupEntity::getGroupCode, MdmMaterialGroupEntity::getId, (a, b) -> a));
        for (MdmMaterialGroupExcelVO excelVO : excelVOList) {
            Set<String> errorMsg = new HashSet<>();
            // 校验物料分组编码是否存在
            boolean exsitMaterialGroupCode = materialGroupMap.containsKey(excelVO.getGroupCode());
            if (exsitMaterialGroupCode) {
                errorMsg.add(excelVO.getGroupCode() + "物料分组编码已存在");
            }
            if (!"-1".equals(excelVO.getParentCode())) {
                Long exsitParentMaterialGroupCode = materialGroupMap.get(excelVO.getParentCode());
                if (exsitParentMaterialGroupCode == null) {
                    errorMsg.add(excelVO.getParentCode() + "上级编码不存在");
                }
                excelVO.setParentCode(String.valueOf(exsitParentMaterialGroupCode));
            }
            // 数据合法情况
            if (CollUtil.isEmpty(errorMsg)) {
                MdmMaterialGroupEntity mdmMaterialGroupEntity = insertExcel(excelVO);
                this.save(mdmMaterialGroupEntity);
                materialGroupMap.put(excelVO.getGroupCode(), mdmMaterialGroupEntity.getId());
            } else {
                // 数据不合法情况
                errorMessageList.add(new ErrorMessage(excelVO.getLineNum() + 1, errorMsg));
            }
        }
        if (CollUtil.isNotEmpty(errorMessageList)) {
            return R.failed(errorMessageList);
        }
        return R.ok(null, "物料分组导入成功");
    }

    private MdmMaterialGroupEntity insertExcel(MdmMaterialGroupExcelVO excelVO) {
        MdmMaterialGroupEntity materialGroup = new MdmMaterialGroupEntity();
        BeanUtils.copyProperties(excelVO, materialGroup);
        materialGroup.setEnabled(Boolean.TRUE);
        materialGroup.setParentId(Long.valueOf(excelVO.getParentCode()));
        return materialGroup;
    }

    /**
     * 导出excel 表格
     *
     * @param mdmMaterialGroup 查询条件
     * @return excel 文件流
     */
    @Override
    public List<MdmMaterialGroupExcelVO> export(MdmMaterialGroupEntity mdmMaterialGroup) {
        LambdaQueryWrapper<MdmMaterialGroupEntity> queryWrapper = Wrappers.lambdaQuery();
        queryWrapper.like(StrUtil.isNotBlank(mdmMaterialGroup.getGroupName()), MdmMaterialGroupEntity::getGroupName,
                mdmMaterialGroup.getGroupName());
        queryWrapper.like(StrUtil.isNotBlank(mdmMaterialGroup.getGroupCode()), MdmMaterialGroupEntity::getGroupCode,
                mdmMaterialGroup.getGroupCode());
        queryWrapper.eq(Objects.nonNull(mdmMaterialGroup.getEnabled()), MdmMaterialGroupEntity::getEnabled,
                mdmMaterialGroup.getEnabled());
        List<MdmMaterialGroupEntity> list = this.list(queryWrapper);
        return list.stream().map(group -> {
            MdmMaterialGroupExcelVO mdmMaterialGroupExcelVO = new MdmMaterialGroupExcelVO();
            BeanUtils.copyProperties(group, mdmMaterialGroupExcelVO);
            MdmMaterialGroupEntity parentGroup = this.getById(group.getParentId());
            mdmMaterialGroupExcelVO.setParentCode(parentGroup != null ? parentGroup.getGroupCode() : "-1");
            return mdmMaterialGroupExcelVO;
        }).collect(Collectors.toList());
    }

    /**
     * 通过id删除物料分组表
     *
     * @param ids id列表
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeMaterialGroup(ArrayList<Long> ids) {

        // 递归获取所有子节点ID
        List<Long> allIds = new ArrayList<>(ids);
        for (Long id : ids) {
            List<Long> childIds = getChildIds(id);
            allIds.addAll(childIds);
        }

        // 删除所有节点
        return this.removeByIds(allIds);
    }

    /**
     * 递归获取子节点ID
     *
     * @param parentId 父节点ID
     * @return 子节点ID列表
     */
    private List<Long> getChildIds(Long parentId) {
        List<Long> childIds = new ArrayList<>();
        List<MdmMaterialGroupEntity> children = this.list(Wrappers.<MdmMaterialGroupEntity>lambdaQuery()
                .select(MdmMaterialGroupEntity::getId)
                .eq(MdmMaterialGroupEntity::getParentId, parentId));

        for (MdmMaterialGroupEntity child : children) {
            childIds.add(child.getId());
            childIds.addAll(getChildIds(child.getId()));
        }

        return childIds;
    }

    @NotNull
    private Function<MdmMaterialGroupEntity, TreeNode<Long>> getNodeFunction() {
        return materialGroup -> {
            TreeNode<Long> node = new TreeNode<>();
            node.setId(materialGroup.getId());
            node.setName(materialGroup.getGroupName());
            node.setParentId(materialGroup.getParentId());
            node.setWeight(materialGroup.getSortOrder());
            // 扩展属性
            Map<String, Object> extra = new HashMap<>();
            extra.put("enabled", materialGroup.getEnabled());
            extra.put("groupCode", materialGroup.getGroupCode());
            extra.put("description", materialGroup.getDescription());
            node.setExtra(extra);
            return node;
        };
    }

}