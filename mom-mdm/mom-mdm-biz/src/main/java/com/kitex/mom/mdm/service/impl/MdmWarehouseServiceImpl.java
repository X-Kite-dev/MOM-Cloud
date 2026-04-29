package com.kitex.mom.mdm.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.ObjUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.admin.api.entity.SysDictItemEntity;
import com.kitex.admin.api.feign.RemoteDictService;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.excel.vo.ErrorMessage;
import com.kitex.mom.mdm.constant.AvailableStatusEnum;
import com.kitex.mom.mdm.entity.MdmFactoryModelEntity;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.entity.MdmWarehouseLocationEntity;
import com.kitex.mom.mdm.enums.FactoryModelTypeEnum;
import com.kitex.mom.mdm.enums.WarehouseModelTypeEnum;
import com.kitex.mom.mdm.mapper.MdmWarehouseMapper;
import com.kitex.mom.mdm.service.MdmFactoryModelService;
import com.kitex.mom.mdm.service.MdmWarehouseLocationService;
import com.kitex.mom.mdm.service.MdmWarehouseService;
import com.kitex.mom.mdm.vo.MdmWarehouseModelTreeVO;
import com.kitex.mom.mdm.vo.excel.MdmWarehouseExcelVO;
import com.kitex.mom.mdm.vo.excel.MdmWarehouseModelExcelVO;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.validation.BindingResult;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 仓库
 *
 * @author kitex
 * @date 2024-10-28 13:48:36
 */
@Service
@AllArgsConstructor
public class MdmWarehouseServiceImpl extends ServiceImpl<MdmWarehouseMapper, MdmWarehouseEntity>
        implements MdmWarehouseService {

    private final RemoteDictService remoteDictService;

    private final MdmFactoryModelService mdmFactoryModelService;

    private final MdmWarehouseLocationService mdmWarehouseLocationService;

    /**
     * 获取仓库模树形结构
     */
    @Override
    public List<MdmWarehouseModelTreeVO> getWarehouseModelTree() {
        // 获取产业级别数据
        List<MdmFactoryModelEntity> industryList = mdmFactoryModelService.list(new LambdaQueryWrapper<MdmFactoryModelEntity>()
                .eq(MdmFactoryModelEntity::getType, FactoryModelTypeEnum.INDUSTRY.getCode())
                .orderByAsc(MdmFactoryModelEntity::getSort));

        // 获取工厂级别数据
        List<MdmFactoryModelEntity> factoryList = mdmFactoryModelService.list(new LambdaQueryWrapper<MdmFactoryModelEntity>()
                .eq(MdmFactoryModelEntity::getType, FactoryModelTypeEnum.FACTORY.getCode())
                .orderByAsc(MdmFactoryModelEntity::getSort));
        // 跳过工厂验证
        FactoryContextHolder.setFactorySkip();
        // 获取所有仓库
        List<MdmWarehouseEntity> warehouseList = this.list(new LambdaQueryWrapper<MdmWarehouseEntity>()
                .orderByAsc(MdmWarehouseEntity::getSort));

        // 获取所有库位
        List<MdmWarehouseLocationEntity> locationList = mdmWarehouseLocationService.list();

        // 构建四层树形结构：产业->工厂->仓库->库位
        return buildFourLevelTree(industryList, factoryList, warehouseList, locationList);
    }

    /**
     * 构建四层树形结构：产业->工厂->仓库->库位
     */
    private List<MdmWarehouseModelTreeVO> buildFourLevelTree(List<MdmFactoryModelEntity> industryList,
                                                             List<MdmFactoryModelEntity> factoryList,
                                                             List<MdmWarehouseEntity> warehouseList,
                                                             List<MdmWarehouseLocationEntity> locationList) {
        List<MdmWarehouseModelTreeVO> result = new ArrayList<>();
        // 按工厂编码分组仓库数据
        Map<String, List<MdmWarehouseEntity>> warehouseMap = warehouseList.stream()
                .collect(Collectors.groupingBy(MdmWarehouseEntity::getFactoryCode));

        // 按仓库编码分组库位数据
        Map<String, List<MdmWarehouseLocationEntity>> locationMap = locationList.stream()
                .collect(Collectors.groupingBy(MdmWarehouseLocationEntity::getWarehouseCode));

        // 构建产业级别节点
        for (MdmFactoryModelEntity industry : industryList) {
            MdmWarehouseModelTreeVO industryNode = convertIndustryToTreeVO(industry);
            result.add(industryNode);
            // 构建该产业下的工厂节点
            List<MdmWarehouseModelTreeVO> factoryNodes = new ArrayList<>();
            for (MdmFactoryModelEntity factory : factoryList) {
                if (factory.getParentId().equals(industry.getId())) {
                    MdmWarehouseModelTreeVO factoryNode = convertFactoryToTreeVO(factory);
                    factoryNodes.add(factoryNode);
                    // 构建该工厂下的仓库节点
                    List<MdmWarehouseModelTreeVO> warehouseNodes = new ArrayList<>();
                    List<MdmWarehouseEntity> factoryWarehouses = warehouseMap.get(factory.getCode());
                    if (factoryWarehouses != null) {
                        for (MdmWarehouseEntity warehouse : factoryWarehouses) {
                            MdmWarehouseModelTreeVO warehouseNode = convertWarehouseToTreeVO(warehouse);
                            warehouseNodes.add(warehouseNode);
                            // 构建该仓库下的库位节点
                            List<MdmWarehouseModelTreeVO> locationNodes = new ArrayList<>();
                            List<MdmWarehouseLocationEntity> warehouseLocations = locationMap.get(warehouse.getWarehouseCode());
                            if (warehouseLocations != null) {
                                // 构建库位的树形结构
                                List<MdmWarehouseModelTreeVO> locationTree = buildLocationTree(warehouseLocations);
                                locationNodes.addAll(locationTree);
                            }

                            // 设置库位节点到仓库节点
                            if (!locationNodes.isEmpty()) {
                                warehouseNode.setChildren(locationNodes);
                                warehouseNode.setHasChildren(true);
                            }
                        }
                    }
                    // 设置仓库节点到工厂节点
                    if (!warehouseNodes.isEmpty()) {
                        factoryNode.setChildren(warehouseNodes);
                        factoryNode.setHasChildren(true);
                    }
                }
            }
            // 设置工厂节点到产业节点
            if (!factoryNodes.isEmpty()) {
                industryNode.setChildren(factoryNodes);
                industryNode.setHasChildren(true);
            }
        }

        return result;
    }

    /**
     * 构建库位的树形结构
     */
    private List<MdmWarehouseModelTreeVO> buildLocationTree(List<MdmWarehouseLocationEntity> locationList) {
        if (CollectionUtils.isEmpty(locationList)) {
            return new ArrayList<>();
        }

        List<MdmWarehouseModelTreeVO> treeVOs = locationList.stream()
                .map(this::convertLocationToTreeVO)
                .collect(Collectors.toList());

        Map<Long, MdmWarehouseModelTreeVO> nodeMap = treeVOs.stream()
                .collect(Collectors.toMap(MdmWarehouseModelTreeVO::getId, node -> node));

        List<MdmWarehouseModelTreeVO> rootNodes = new ArrayList<>();

        for (MdmWarehouseModelTreeVO node : treeVOs) {
            if (node.getParentId() == -1) {
                rootNodes.add(node);
            } else {
                MdmWarehouseModelTreeVO parent = nodeMap.get(node.getParentId());
                if (parent != null) {
                    if (parent.getChildren() == null) {
                        parent.setChildren(new ArrayList<>());
                    }
                    parent.getChildren().add(node);
                    parent.setHasChildren(true);
                }
            }
        }

        // 排序
        sortTree(rootNodes);
        return rootNodes;
    }

    /**
     * 产业实体转换为树形VO
     */
    private MdmWarehouseModelTreeVO convertIndustryToTreeVO(MdmFactoryModelEntity entity) {
        MdmWarehouseModelTreeVO vo = new MdmWarehouseModelTreeVO();
        BeanUtils.copyProperties(entity, vo);
        vo.setType(FactoryModelTypeEnum.INDUSTRY.getCode());
        vo.setTypeName(FactoryModelTypeEnum.INDUSTRY.getName());
        vo.setSort(entity.getSort());
        vo.setHasChildren(false);
        return vo;
    }

    /**
     * 工厂实体转换为树形VO
     */
    private MdmWarehouseModelTreeVO convertFactoryToTreeVO(MdmFactoryModelEntity entity) {
        MdmWarehouseModelTreeVO vo = new MdmWarehouseModelTreeVO();
        BeanUtils.copyProperties(entity, vo);
        vo.setType(FactoryModelTypeEnum.FACTORY.getCode());
        vo.setTypeName(FactoryModelTypeEnum.FACTORY.getName());
        vo.setSort(entity.getSort());
        vo.setHasChildren(false);
        return vo;
    }

    /**
     * 仓库实体转换为树形VO
     */
    private MdmWarehouseModelTreeVO convertWarehouseToTreeVO(MdmWarehouseEntity entity) {
        MdmWarehouseModelTreeVO vo = new MdmWarehouseModelTreeVO();
        BeanUtils.copyProperties(entity, vo);
        vo.setCode(entity.getWarehouseCode());
        vo.setName(entity.getWarehouseName());
        vo.setType(WarehouseModelTypeEnum.WAREHOUSE.getCode());
        vo.setTypeName(WarehouseModelTypeEnum.WAREHOUSE.getName());
        vo.setSort(entity.getSort());
        vo.setHasChildren(false);
        return vo;
    }

    /**
     * 库位实体转换为树形VO
     */
    private MdmWarehouseModelTreeVO convertLocationToTreeVO(MdmWarehouseLocationEntity entity) {
        MdmWarehouseModelTreeVO vo = new MdmWarehouseModelTreeVO();
        BeanUtils.copyProperties(entity, vo);
        vo.setCode(entity.getLocationCode());
        vo.setName(entity.getLocationName());
        vo.setType(String.valueOf(entity.getLocationLevel()));
        // 设置类型名称
        WarehouseModelTypeEnum typeEnum = WarehouseModelTypeEnum.getByCode(vo.getType());
        if (typeEnum != null) {
            vo.setTypeName(typeEnum.getName());
        }
        vo.setHasChildren(false);
        return vo;
    }

    /**
     * 对树形结构排序
     */
    private void sortTree(List<MdmWarehouseModelTreeVO> nodes) {
        if (CollectionUtils.isEmpty(nodes)) {
            return;
        }

        nodes.sort((a, b) -> {
            int sortCompare = Integer.compare(
                    a.getSort() != null ? a.getSort() : 0,
                    b.getSort() != null ? b.getSort() : 0
            );
            if (sortCompare != 0) {
                return sortCompare;
            }
            return a.getCreateTime().compareTo(b.getCreateTime());
        });

        for (MdmWarehouseModelTreeVO node : nodes) {
            if (!CollectionUtils.isEmpty(node.getChildren())) {
                sortTree(node.getChildren());
            }
        }
    }

    /**
     * 导入excel 表格
     *
     * @param excelVOList   仓库列表
     * @param bindingResult 错误信息列表
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public R importWarehouse(List<MdmWarehouseModelExcelVO> excelVOList, BindingResult bindingResult) {
        // 通用校验获取失败的数据
        List<ErrorMessage> errorMessageList = (List<ErrorMessage>) bindingResult.getTarget();
        // 获取所有的仓库编码
        Map<String, String> warehouseMap = this.list()
                .stream()
                .collect(Collectors.toMap(MdmWarehouseEntity::getWarehouseCode, MdmWarehouseEntity::getWarehouseName,
                        (a, b) -> a));
        // 获取所有的工厂编码
        Map<String, String> factoryMap = mdmFactoryModelService.list(new LambdaQueryWrapper<MdmFactoryModelEntity>()
                        .eq(MdmFactoryModelEntity::getType, FactoryModelTypeEnum.FACTORY.getCode()))
                .stream()
                .collect(Collectors.toMap(MdmFactoryModelEntity::getCode, MdmFactoryModelEntity::getName,
                        (a, b) -> a));
        // 获取所有库位信息
        Map<String, MdmWarehouseLocationEntity> locationMap = mdmWarehouseLocationService.list()
                .stream()
                .collect(Collectors.toMap(MdmWarehouseLocationEntity::getLocationCode, Function.identity(),
                        (a, b) -> a));
        // 仓库保存list
        List<MdmWarehouseEntity> warehouseList = new ArrayList<>();
        // 库位保存list
        List<MdmWarehouseLocationEntity> warehouseLocationList = new ArrayList<>();

        // 雪花算法ID
        Snowflake snowflake = IdUtil.getSnowflake();
        for (MdmWarehouseModelExcelVO excelVO : excelVOList) {
            Set<String> errorMsg = new HashSet<>();
            // 校验工厂编码
            String factoryName = factoryMap.get(excelVO.getFactoryCode());
            if (factoryName == null) {
                errorMsg.add(excelVO.getFactoryCode() + "工厂编码不存在");
            }
            // 当为-1时表示仓库，校验仓库逻辑
            if ("-1".equals(excelVO.getParentCode())) {
                boolean warehouseCode = warehouseMap.containsKey(excelVO.getCode());
                if (warehouseCode) {
                    errorMsg.add(excelVO.getCode() + "仓库编码已存在");
                }
            } else {
                // 校验库区、货架、库位
                String parentCode = excelVO.getParentCode();
                Integer expectedLevel = null;
                Long derivedParentId = null;
                if (warehouseMap.containsKey(parentCode)) {
                    // 父级为仓库编码 => 本条为库区，层级1
                    expectedLevel = 1;
                    derivedParentId = -1L;
                } else if (locationMap.containsKey(parentCode)) {
                    MdmWarehouseLocationEntity parentLocation = locationMap.get(parentCode);
                    Integer parentLevel = parentLocation.getLocationLevel();
                    if (parentLevel != null) {
                        derivedParentId = parentLocation.getId();
                        if (parentLevel == 1) {
                            // 父级为库区 => 本条为货架，层级2
                            expectedLevel = 2;
                        } else if (parentLevel == 2) {
                            // 父级为货架 => 本条为库位，层级3
                            expectedLevel = 3;
                        } else {
                            // 父级为库位或更深层级，不允许再新增
                            errorMsg.add(parentCode + "父级层级不允许新增下级");
                        }
                    } else {
                        // 父级层级未知，默认不通过
                        errorMsg.add(parentCode + "父级层级信息缺失");
                    }
                } else {
                    // 父级不存在
                    errorMsg.add(parentCode + "父级编码不存在");
                }
                // 将校验得到的父级与层级写回 excelVO
                excelVO.setLocationLevel(expectedLevel);
                excelVO.setParentId(derivedParentId);
                // 校验当前编码是否已存在
                if (locationMap.containsKey(excelVO.getCode())) {
                    errorMsg.add(excelVO.getCode() + "库位编码已存在");
                }
            }

            // 数据合法情况
            if (CollUtil.isEmpty(errorMsg)) {
                // 仓库处理逻辑
                MdmWarehouseEntity mdmWarehouseEntity = insertWarehouseExcel(excelVO, factoryName);
                warehouseList.add(mdmWarehouseEntity);
                warehouseMap.put(mdmWarehouseEntity.getWarehouseCode(), mdmWarehouseEntity.getWarehouseName());

                // 库位处理逻辑（直接使用校验阶段写入的 parentId 和 locationLevel）
                MdmWarehouseLocationEntity mdmWarehouseLocationEntity = insertWarehouseLocationExcel(excelVO, mdmWarehouseEntity, snowflake.nextId());
                warehouseLocationList.add(mdmWarehouseLocationEntity);
                locationMap.put(mdmWarehouseLocationEntity.getLocationCode(), mdmWarehouseLocationEntity);
            } else {
                // 数据不合法情况
                errorMessageList.add(new ErrorMessage(excelVO.getLineNum() + 1, errorMsg));
            }
        }
        // 保存仓库
        this.saveBatch(warehouseList);
        // 保存库位
        mdmWarehouseLocationService.saveBatch(warehouseLocationList);
        if (CollUtil.isNotEmpty(errorMessageList)) {
            return R.failed(errorMessageList);
        }
        return R.ok(null, "仓库导入成功");
    }

    /**
     * 插入仓库excel
     */
    private MdmWarehouseEntity insertWarehouseExcel(MdmWarehouseModelExcelVO excelVO, String factoryName) {
        MdmWarehouseEntity warehouse = new MdmWarehouseEntity();
        BeanUtil.copyProperties(excelVO, warehouse);
        warehouse.setWarehouseCode(excelVO.getCode());
        warehouse.setWarehouseCode(excelVO.getName());
        warehouse.setFactoryCode(factoryName);
        warehouse.setStatus(true);
        warehouse.setIsLocationManagement(1);
        warehouse.setIsMobile(1);
        warehouse.setIsVmi(0);
        return warehouse;
    }

    /**
     * 插入库位excel
     */
    private MdmWarehouseLocationEntity insertWarehouseLocationExcel(MdmWarehouseModelExcelVO excelVO,
                                                                    MdmWarehouseEntity warehouse,
                                                                    Long id) {
        MdmWarehouseLocationEntity mdmWarehouseLocation = new MdmWarehouseLocationEntity();
        BeanUtil.copyProperties(excelVO, warehouse);
        mdmWarehouseLocation.setId(id);
        mdmWarehouseLocation.setLocationCode(excelVO.getCode());
        mdmWarehouseLocation.setLocationName(excelVO.getName());
        return mdmWarehouseLocation;
    }

    /**
     * 导出
     *
     * @param mdmWarehouse
     * @return
     */
    @Override
    public List<MdmWarehouseExcelVO> export(MdmWarehouseEntity mdmWarehouse) {
        QueryWrapper<MdmWarehouseEntity> wrapper = new QueryWrapper<>();
        if (ObjUtil.isNotEmpty(mdmWarehouse.getFactoryCode())) {
            List<String> factoryCodes = Arrays.stream(mdmWarehouse.getFactoryCode().split(",")).toList();
            wrapper.lambda()
                    .in(MdmWarehouseEntity::getFactoryCode, factoryCodes);
        }
        // 获取仓库类型字典
        Map<String, String> warehouseTypeMap = remoteDictService.getDictByType("warehouse_type")
                .getData()
                .stream()
                .collect(Collectors.toMap(SysDictItemEntity::getItemValue, SysDictItemEntity::getLabel));
        return this
                .list(wrapper)
                .stream()
                .map(warehouse -> {
                    MdmWarehouseExcelVO mdmWarehouseExcelVO = new MdmWarehouseExcelVO();
                    BeanUtil.copyProperties(warehouse, mdmWarehouseExcelVO);
                    mdmWarehouseExcelVO.setWarehouseType(warehouseTypeMap.get(warehouse.getWarehouseType()));
                    mdmWarehouseExcelVO.setStatus(AvailableStatusEnum.labelOf(warehouse.getStatus()));
                    return mdmWarehouseExcelVO;
                })
                .collect(Collectors.toList());
    }

    /**
     * 导出excel 表格(仓储、库区)
     *
     * @return excel 文件流
     */
    @Override
    public List<MdmWarehouseModelExcelVO> exportAll() {
        List<MdmWarehouseModelExcelVO> exportExcelVOList = new ArrayList<>();
        // 仓库列表（按 sort）
        List<MdmWarehouseEntity> warehouseList = this.list();
        warehouseList.sort(Comparator.comparing(w -> w.getSort() == null ? 0 : w.getSort()));
        // 全量库位列表（构建映射）
        List<MdmWarehouseLocationEntity> allLocations = mdmWarehouseLocationService.list();
        Map<Long, MdmWarehouseLocationEntity> idToLocation = allLocations.stream()
                .collect(Collectors.toMap(MdmWarehouseLocationEntity::getId, Function.identity(), (a, b) -> a));
        // 每个仓库内：父ID -> 子库位列表
        Map<String, Map<Long, List<MdmWarehouseLocationEntity>>> warehouseToChildrenMap = new HashMap<>();
        for (MdmWarehouseLocationEntity loc : allLocations) {
            warehouseToChildrenMap
                    .computeIfAbsent(loc.getWarehouseCode(), k -> new HashMap<>())
                    .computeIfAbsent(loc.getParentId(), k -> new ArrayList<>())
                    .add(loc);
        }
        // 对每个仓库的每组子列表按 sort 排序
        for (Map<Long, List<MdmWarehouseLocationEntity>> childrenMap : warehouseToChildrenMap.values()) {
            for (List<MdmWarehouseLocationEntity> children : childrenMap.values()) {
                children.sort(Comparator.comparing(l -> l.getSort() == null ? 0 : l.getSort()));
            }
        }

        // 逐个仓库输出其自身与所有子级（深度优先）
        for (MdmWarehouseEntity warehouse : warehouseList) {
            // 仓库行
            MdmWarehouseModelExcelVO warehouseRow = new MdmWarehouseModelExcelVO();
            warehouseRow.setFactoryCode(warehouse.getFactoryCode());
            warehouseRow.setParentCode("-1");
            warehouseRow.setCode(warehouse.getWarehouseCode());
            warehouseRow.setName(warehouse.getWarehouseName());
            warehouseRow.setDescription(warehouse.getDescription());
            warehouseRow.setSort(warehouse.getSort());
            warehouseRow.setStatus(AvailableStatusEnum.labelOf(warehouse.getStatus()));
            exportExcelVOList.add(warehouseRow);

            // 该仓库的库位树根：parentId == -1
            Map<Long, List<MdmWarehouseLocationEntity>> childrenMap = warehouseToChildrenMap.getOrDefault(warehouse.getWarehouseCode(), new HashMap<>());
            List<MdmWarehouseLocationEntity> roots = childrenMap.getOrDefault(-1L, new ArrayList<>());
            for (MdmWarehouseLocationEntity root : roots) {
                addLocationAndChildrenRows(root, childrenMap, idToLocation, warehouse, exportExcelVOList);
            }
        }

        return exportExcelVOList;
    }

    /**
     * 按深度优先顺序添加库位及其子库位的导出行。
     */
    private void addLocationAndChildrenRows(MdmWarehouseLocationEntity node,
                                            Map<Long, List<MdmWarehouseLocationEntity>> childrenMap,
                                            Map<Long, MdmWarehouseLocationEntity> idToLocation,
                                            MdmWarehouseEntity warehouse,
                                            List<MdmWarehouseModelExcelVO> out) {
        // 当前库位行
        MdmWarehouseModelExcelVO row = new MdmWarehouseModelExcelVO();
        row.setFactoryCode(warehouse.getFactoryCode());
        // 父编码：根库位(parentId=-1)的父为仓库编码；否则为父库位编码
        if (node.getParentId() != null && node.getParentId() == -1L) {
            row.setParentCode(node.getWarehouseCode());
        } else {
            MdmWarehouseLocationEntity parent = idToLocation.get(node.getParentId());
            row.setParentCode(parent == null ? null : parent.getLocationCode());
        }
        row.setCode(node.getLocationCode());
        row.setName(node.getLocationName());
        row.setDescription(node.getDescription());
        row.setSort(node.getSort());
        try {
            row.setStatus(AvailableStatusEnum.labelOf(node.getStatus()));
        } catch (Exception ignore) {
        }
        out.add(row);

        // 子库位
        List<MdmWarehouseLocationEntity> children = childrenMap.getOrDefault(node.getId(), new ArrayList<>());
        if (!children.isEmpty()) {
            for (MdmWarehouseLocationEntity child : children) {
                addLocationAndChildrenRows(child, childrenMap, idToLocation, warehouse, out);
            }
        }
    }

}