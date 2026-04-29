package com.kitex.mom.mdm.service.impl;

import cn.hutool.core.lang.tree.Tree;
import cn.hutool.core.lang.tree.TreeNode;
import cn.hutool.core.lang.tree.TreeUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.constant.CommonConstants;
import com.kitex.common.core.util.R;
import com.kitex.mom.common.barcode.api.feign.RemotePrintService;
import com.kitex.mom.common.barcode.dto.req.PreviewObj;
import com.kitex.mom.common.barcode.dto.req.PrintReq;
import com.kitex.mom.mdm.constant.AvailableStatusEnum;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.entity.MdmWarehouseLocationEntity;
import com.kitex.mom.mdm.mapper.MdmWarehouseLocationMapper;
import com.kitex.mom.mdm.mapper.MdmWarehouseMapper;
import com.kitex.mom.mdm.service.MdmWarehouseLocationService;
import com.kitex.mom.mdm.vo.excel.MdmWarehouseLocationExcelVO;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.BindingResult;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 库位
 *
 * @author kitex
 * @date 2024-12-13 18:20:13
 */
@Service
@RequiredArgsConstructor
public class MdmWarehouseLocationServiceImpl extends ServiceImpl<MdmWarehouseLocationMapper, MdmWarehouseLocationEntity> implements MdmWarehouseLocationService {

    private final MdmWarehouseMapper mdmWarehouseMapper;

    private final RemotePrintService remotePrintService;

    @Override
    public List<Tree<Long>> getTreeList(MdmWarehouseLocationEntity params) {
        Long parentId = params.getParentId() == null ? CommonConstants.MENU_TREE_ROOT_ID : params.getParentId();
        QueryWrapper<MdmWarehouseLocationEntity> wrapper = Wrappers.query();
        wrapper.lambda().eq(StrUtil.isNotBlank(params.getWarehouseCode()), MdmWarehouseLocationEntity::getWarehouseCode, params.getWarehouseCode())
                .like(StrUtil.isNotBlank(params.getLocationCode()), MdmWarehouseLocationEntity::getLocationCode, params.getLocationCode())
                .like(StrUtil.isNotBlank(params.getLocationName()), MdmWarehouseLocationEntity::getLocationName, params.getLocationName())
                .orderByDesc(MdmWarehouseLocationEntity::getCreateTime);
        List<TreeNode<Long>> collect = list(wrapper).stream()
                .map(getNodeFunction())
                .collect(Collectors.toList());

        // 模糊查询 不组装树结构 直接返回 表格方便编辑
        if (StrUtil.isNotBlank(params.getLocationCode()) || StrUtil.isNotBlank(params.getLocationName())) {
            return collect.stream().map(node -> {
                Tree<Long> tree = new Tree<>();
                tree.putAll(node.getExtra());
                BeanUtils.copyProperties(node, tree);
                return tree;
            }).collect(Collectors.toList());
        }
        return TreeUtil.build(collect, parentId);
    }

    /**
     * 获取库位树结构-全量查询
     * 如果有仓库条件，则查询符合条件的库位
     * 如果没有仓库条件，则先查询所有仓库，再查询每个仓库下符合条件的库位
     * 仓库层级为0，库位是1 2 3
     *
     * @param params
     * @return
     */
    @Override
    public List<Tree<Long>> getTreeAllList(MdmWarehouseLocationEntity params) {
        if (StrUtil.isNotBlank(params.getWarehouseCode())) {
            return getTreeByWarehouse(params.getWarehouseCode(), params.getLocationCode(), params.getLocationName());
        }
        // 获取所有仓库作为树的第一层
        List<MdmWarehouseEntity> warehouseList = mdmWarehouseMapper.selectList(new LambdaQueryWrapper<>());
        List<Tree<Long>> result = new ArrayList<>();
        for (MdmWarehouseEntity warehouse : warehouseList) {
            Tree<Long> root = new Tree<>();
            root.setId(warehouse.getId());
            root.setName(warehouse.getWarehouseName());
            root.setParentId(-2L);
            // 扩展属性
            root.putExtra("locationLevel", 0);
            root.putExtra("code", warehouse.getWarehouseCode());
            List<Tree<Long>> treeByWarehouse = getTreeByWarehouse(warehouse.getWarehouseCode(), params.getLocationCode(), params.getLocationName());
            root.setChildren(treeByWarehouse);
            result.add(root);
        }
        return result;
    }

    public List<Tree<Long>> getTreeByWarehouse(@NotBlank String warehouseCode, String locationCode, String locationName) {
        List<TreeNode<Long>> collect = this.list(Wrappers.<MdmWarehouseLocationEntity>lambdaQuery()
                        .eq(MdmWarehouseLocationEntity::getWarehouseCode, warehouseCode)
                        .like(StrUtil.isNotBlank(locationCode), MdmWarehouseLocationEntity::getLocationCode, locationCode)
                        .like(StrUtil.isNotBlank(locationName), MdmWarehouseLocationEntity::getLocationName, locationName)
                ).stream()
                .map(getNodeFunction())
                .collect(Collectors.toList());
        Long rootId = CommonConstants.MENU_TREE_ROOT_ID;
        return TreeUtil.build(collect, rootId);
    }


    @NotNull
    private Function<MdmWarehouseLocationEntity, TreeNode<Long>> getNodeFunction() {
        return location -> {
            TreeNode<Long> node = new TreeNode<>();
            node.setId(location.getId());
            node.setName(location.getLocationName());
            node.setParentId(location.getParentId());
            // 扩展属性
            Map<String, Object> extra = new HashMap<>();
            extra.put("locationLevel", location.getLocationLevel());
            extra.put("code", location.getLocationCode());
            extra.put("warehouseCode", location.getWarehouseCode());
            extra.put("warehouseName", location.getWarehouseName());
            extra.put("shelvesNumber", location.getShelvesNumber());
            node.setExtra(extra);
            return node;
        };
    }

    /**
     * 删除
     * 也删除所有子节点
     *
     * @param ids
     * @return
     */
    @Override
    @Transactional
    public Boolean removeTreeBatchByIds(ArrayList<Long> ids) {
        for (Long id : ids) {
            delTreeNodeById(id);
        }
        return Boolean.TRUE;
    }

    /**
     * 库位打印
     *
     * @param printReq 打印Req
     * @return R 对象
     */
    @Override
    public String print(PrintReq printReq) {
        List<MdmWarehouseLocationEntity> locationList = this.listByIds(Arrays.asList(printReq.getIds()));
        PreviewObj obj = new PreviewObj();
        obj.setTemplateCode(printReq.getTemplateCode());
        String jsonData = JSONUtil.toJsonStr(locationList);
        obj.setJsonData(jsonData);
        return remotePrintService.preview(obj).getData();
    }

    /**
     * 导入excel 表
     *
     * @param excelVOList   库位列表
     * @param bindingResult 错误信息列表
     * @return ok fail
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public R importExcel(List<MdmWarehouseLocationExcelVO> excelVOList, BindingResult bindingResult) {
//        // 通用校验获取失败的数据
//        List<ErrorMessage> errorMessageList = (List<ErrorMessage>) bindingResult.getTarget();
//        // 仓库map
//        Map<String, MdmWarehouseEntity> warehouseMap = mdmWarehouseMapper.selectList(new LambdaQueryWrapper<>())
//                .stream()
//                .collect(Collectors.toMap(MdmWarehouseEntity::getWarehouseCode, Function.identity(), (a, b) -> a));
//
//        // 库位map
//        Map<String, MdmWarehouseLocationEntity> locationMap = this.list()
//                .stream()
//                .collect(Collectors.toMap(MdmWarehouseLocationEntity::getLocationCode, Function.identity(), (a, b) -> a));
//
//        for (MdmWarehouseLocationExcelVO excelVO : excelVOList) {
//            Set<String> errorMsg = new HashSet<>();
//
//            MdmWarehouseEntity mdmWarehouseEntity = warehouseMap.get(excelVO.getWarehouseCode());
//            if (mdmWarehouseEntity == null) {
//                errorMsg.add(excelVO.getWarehouseCode() + "仓库编码不存在");
//            }
//            MdmWarehouseLocationEntity mdmWarehouseLocationEntity = locationMap.get(excelVO.getLocationCode());
//            if (mdmWarehouseLocationEntity != null) {
//                errorMsg.add(excelVO.getLocationCode() + "库位编码已存在");
//            }
//
//            MdmWarehouseLocationEntity parentLocation = locationMap.get(excelVO.getParentLocationCode());
//            if (parentLocation == null || !Objects.equals(parentLocation.getLocationLevel(), excelVO.getLocationLevel() - 1)) {
//                if (!"无".equals(excelVO.getParentLocationCode())) {
//                    errorMsg.add(excelVO.getLocationCode() + "上级库位编码不存在");
//                }
//            }
//            // 数据合法情况
//            if (CollUtil.isEmpty(errorMsg)) {
//                MdmWarehouseLocationEntity saveLocation = new MdmWarehouseLocationEntity();
//                BeanUtils.copyProperties(excelVO, saveLocation);
//                saveLocation.setWarehouseName(mdmWarehouseEntity.getWarehouseName());
//                saveLocation.setParentId("无".equals(excelVO.getParentLocationCode()) ? -1 :parentLocation.getId());
//                this.save(saveLocation);
//                locationMap.put(saveLocation.getLocationCode(), saveLocation);
//            }
//            else {
//                // 数据不合法情况
//                errorMessageList.add(new ErrorMessage(excelVO.getLineNum() + 1, errorMsg));
//            }
//        }
//        if (CollUtil.isNotEmpty(errorMessageList)) {
//            return R.failed(errorMessageList);
//        }
        return R.ok(null, "库位导入成功");
    }

    /**
     * 导出excel 表格
     *
     * @param mdmWarehouseLocation 查询条件
     * @return excel 文件流
     */
    @Override
    public List<MdmWarehouseLocationExcelVO> exportExcel(MdmWarehouseLocationEntity mdmWarehouseLocation) {
        LambdaQueryWrapper<MdmWarehouseLocationEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(StrUtil.isNotBlank(mdmWarehouseLocation.getWarehouseCode()), MdmWarehouseLocationEntity::getWarehouseCode, mdmWarehouseLocation.getWarehouseCode())
                .eq(ObjUtil.isNotEmpty(mdmWarehouseLocation.getParentId()), MdmWarehouseLocationEntity::getParentId, mdmWarehouseLocation.getParentId());
        List<MdmWarehouseLocationEntity> list = this.list(queryWrapper);
        return list.stream().map(location -> {
            MdmWarehouseLocationExcelVO excelVO = new MdmWarehouseLocationExcelVO();
            BeanUtils.copyProperties(location, excelVO);
            excelVO.setStatus(AvailableStatusEnum.labelOf(location.getStatus()));
            return excelVO;
        }).collect(Collectors.toList());
    }

    public void delTreeNodeById(Long id) {
        List<MdmWarehouseLocationEntity> list = list(new LambdaQueryWrapper<MdmWarehouseLocationEntity>()
                .eq(MdmWarehouseLocationEntity::getParentId, id)
        );
        if (list.size() > 0) {
            for (MdmWarehouseLocationEntity entity : list) {
                delTreeNodeById(entity.getId());
            }
        }
        removeById(id);
    }

}