package com.kitex.mom.mdm.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.vo.ErrorMessage;
import com.kitex.mom.common.barcode.api.feign.RemotePrintService;
import com.kitex.mom.common.barcode.dto.req.PreviewObj;
import com.kitex.mom.common.barcode.dto.req.PrintReq;
import com.kitex.mom.mdm.constant.AvailableStatusEnum;
import com.kitex.mom.mdm.dto.MdmFactoryModelDTO;
import com.kitex.mom.mdm.entity.MdmFactoryModelEntity;
import com.kitex.mom.mdm.enums.FactoryModelTypeEnum;
import com.kitex.mom.mdm.mapper.MdmFactoryModelMapper;
import com.kitex.mom.mdm.service.MdmFactoryModelService;
import com.kitex.mom.mdm.vo.MdmFactoryModelAncestorVO;
import com.kitex.mom.mdm.vo.MdmFactoryModelTreeVO;
import com.kitex.mom.mdm.vo.excel.MdmFactoryModelExcelVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 工厂建模服务实现类
 *
 * @author claude
 * @date 2025-07-30
 */
@Service
@RequiredArgsConstructor
public class MdmFactoryModelServiceImpl extends ServiceImpl<MdmFactoryModelMapper, MdmFactoryModelEntity>
        implements MdmFactoryModelService {

    private final RemotePrintService remotePrintService;

    @Override
    @Cacheable(value = "factoryModelTree", key = "'all'")
    public List<MdmFactoryModelTreeVO> getFactoryModelTree() {
        List<MdmFactoryModelEntity> allNodes = baseMapper.selectTreeList();
        return buildTree(allNodes);
    }

    @Override
    @Cacheable(value = "factoryModelTree", key = "'children:' + #parentId")
    public List<MdmFactoryModelTreeVO> getChildrenByParentId(Long parentId) {
        List<MdmFactoryModelEntity> children = baseMapper.selectByParentId(parentId);
        return children.stream().map(this::convertToTreeVO).collect(Collectors.toList());
    }

    @Override
    @Cacheable(value = "factoryModelNodes", key = "'level:' + #level")
    public List<MdmFactoryModelTreeVO> getNodesByLevel(Integer level) {
        List<MdmFactoryModelEntity> nodes = baseMapper.selectByLevel(level);
        return nodes.stream().map(this::convertToTreeVO).collect(Collectors.toList());
    }

    @Override
    public List<MdmFactoryModelTreeVO> getNodesByType(String type) {
        List<MdmFactoryModelEntity> nodes = baseMapper.selectByType(type);
        return nodes.stream().map(this::convertToTreeVO).collect(Collectors.toList());
    }

    @Override
    @Cacheable(value = "factoryModelNodes", key = "'type:' + #type")
    public List<MdmFactoryModelTreeVO> getNodesByTypeWithCache(String type) {
        return getNodesByType(type);
    }

    @Override
    public List<MdmFactoryModelTreeVO> getNodesByTypeAndParent(String type, Long parentId) {
        LambdaQueryWrapper<MdmFactoryModelEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MdmFactoryModelEntity::getType, type)
                .eq(MdmFactoryModelEntity::getParentId, parentId)
                .orderByAsc(MdmFactoryModelEntity::getSort);

        List<MdmFactoryModelEntity> nodes = list(wrapper);
        return nodes.stream().map(this::convertToTreeVO).collect(Collectors.toList());
    }

    @Override
    @Cacheable(value = "factoryModelCount", key = "'type:' + #type")
    public Integer countNodesByType(String type) {
        LambdaQueryWrapper<MdmFactoryModelEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MdmFactoryModelEntity::getType, type);
        return Math.toIntExact(count(wrapper));
    }

    @Override
    @Cacheable(value = "factoryModelTypes", key = "'level:' + #level")
    public List<String> getTypesByLevel(Integer level) {
        FactoryModelTypeEnum[] types = FactoryModelTypeEnum.getByLevel(level);
        return Arrays.stream(types)
                .map(FactoryModelTypeEnum::getCode)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(value = {"factoryModelNodes", "factoryModelCount", "factoryModelTypes", "factoryModelTree"}, allEntries = true)
    public Boolean createFactoryModelNode(MdmFactoryModelDTO dto) {
        // 验证节点编码是否重复
        if (!validateNodeCode(dto.getCode(), null)) {
            throw new RuntimeException("节点编码已存在");
        }

        // 验证节点层级关系
//        if (!validateNodeHierarchy(dto.getParentId(), dto.getLevel(), dto.getType())) {
//            throw new RuntimeException("节点层级关系不正确");
//        }

        MdmFactoryModelEntity entity = new MdmFactoryModelEntity();
        BeanUtils.copyProperties(dto, entity);

        // 设置默认值
        if (entity.getSort() == null) {
            entity.setSort(0);
        }
        if (entity.getStatus() == null) {
            entity.setStatus(true);
        }

        return save(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(value = {"factoryModelNodes", "factoryModelCount", "factoryModelTypes", "factoryModelTree"}, allEntries = true)
    public Boolean updateFactoryModelNode(MdmFactoryModelDTO dto) {
        // 验证节点编码是否重复
        if (!validateNodeCode(dto.getCode(), dto.getId())) {
            throw new RuntimeException("节点编码已存在");
        }

        // 验证节点层级关系
        if (!validateNodeHierarchy(dto.getParentId(), dto.getLevel(), dto.getType())) {
            throw new RuntimeException("节点层级关系不正确");
        }

        MdmFactoryModelEntity entity = new MdmFactoryModelEntity();
        BeanUtils.copyProperties(dto, entity);

        return updateById(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(value = {"factoryModelNodes", "factoryModelCount", "factoryModelTypes", "factoryModelTree"}, allEntries = true)
    public Boolean deleteFactoryModelNode(Long id) {
        // 查询所有子孙节点
        List<MdmFactoryModelEntity> descendants = baseMapper.selectDescendants(id);

        // 删除所有子孙节点
        if (!CollectionUtils.isEmpty(descendants)) {
            List<Long> descendantIds = descendants.stream().map(MdmFactoryModelEntity::getId).collect(Collectors.toList());
            removeByIds(descendantIds);
        }

        // 删除当前节点
        return removeById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(value = {"factoryModelNodes", "factoryModelCount", "factoryModelTypes", "factoryModelTree"}, allEntries = true)
    public Boolean moveNode(Long nodeId, Long newParentId, Integer newSort) {
        MdmFactoryModelEntity node = getById(nodeId);
        if (node == null) {
            throw new RuntimeException("节点不存在");
        }

        // 验证新的层级关系
        Integer newLevel = newParentId == null ? 1 : getById(newParentId).getLevel() + 1;
        if (!validateNodeHierarchy(newParentId, newLevel, node.getType())) {
            throw new RuntimeException("移动后的层级关系不正确");
        }

        node.setParentId(newParentId);
        node.setLevel(newLevel);
        if (newSort != null) {
            node.setSort(newSort);
        }

        return updateById(node);
    }

    @Override
    public Boolean validateNodeCode(String code, Long excludeId) {
        int count = baseMapper.countByCode(code, excludeId);
        return count == 0;
    }

    @Override
    public Boolean validateNodeHierarchy(Long parentId, Integer level, String type) {
        // 根节点验证
        if (parentId == -1) {
            return level == 1 && FactoryModelTypeEnum.INDUSTRY.getCode().equals(type);
        }

        // 查询父节点
        MdmFactoryModelEntity parent = getById(parentId);
        if (parent == null) {
            return false;
        }

        // 验证级别递增
        if (level != parent.getLevel() + 1) {
            return false;
        }

        // 验证类型与级别匹配
        FactoryModelTypeEnum typeEnum = FactoryModelTypeEnum.getByCode(type);
        if (typeEnum == null || !typeEnum.getLevel().equals(level)) {
            return false;
        }

        return true;
    }

    @Override
    public String getNodePath(Long nodeId) {
        List<MdmFactoryModelEntity> ancestors = baseMapper.selectAncestors(nodeId);
        MdmFactoryModelEntity currentNode = getById(nodeId);

        List<String> pathParts = new ArrayList<>();
        ancestors.forEach(ancestor -> pathParts.add(ancestor.getName()));
        pathParts.add(currentNode.getName());

        return String.join(" > ", pathParts);
    }

    @Override
    public MdmFactoryModelEntity getNodeByPath(String path) {
        if (!StringUtils.hasText(path)) {
            return null;
        }

        String[] pathParts = path.split(" > ");
        if (pathParts.length == 0) {
            return null;
        }

        // 从根节点开始查找
        MdmFactoryModelEntity currentNode = null;
        Long currentParentId = null;

        for (String nodeName : pathParts) {
            LambdaQueryWrapper<MdmFactoryModelEntity> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(MdmFactoryModelEntity::getName, nodeName.trim());

            if (currentParentId == null) {
                wrapper.isNull(MdmFactoryModelEntity::getParentId);
            } else {
                wrapper.eq(MdmFactoryModelEntity::getParentId, currentParentId);
            }

            currentNode = getOne(wrapper);
            if (currentNode == null) {
                return null;
            }
            currentParentId = currentNode.getId();
        }

        return currentNode;
    }

    /**
     * 构建树形结构
     */
    private List<MdmFactoryModelTreeVO> buildTree(List<MdmFactoryModelEntity> allNodes) {
        if (CollectionUtils.isEmpty(allNodes)) {
            return new ArrayList<>();
        }

        List<MdmFactoryModelTreeVO> treeVOs = allNodes.stream()
                .map(this::convertToTreeVO)
                .collect(Collectors.toList());

        Map<Long, MdmFactoryModelTreeVO> nodeMap = treeVOs.stream()
                .collect(Collectors.toMap(MdmFactoryModelTreeVO::getId, node -> node));

        List<MdmFactoryModelTreeVO> rootNodes = new ArrayList<>();

        for (MdmFactoryModelTreeVO node : treeVOs) {
            if (node.getParentId() == -1) {
                rootNodes.add(node);
            } else {
                MdmFactoryModelTreeVO parent = nodeMap.get(node.getParentId());
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
     * 对树形结构排序
     */
    private void sortTree(List<MdmFactoryModelTreeVO> nodes) {
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

        for (MdmFactoryModelTreeVO node : nodes) {
            if (!CollectionUtils.isEmpty(node.getChildren())) {
                sortTree(node.getChildren());
            }
        }
    }

    /**
     * 实体转换为树形VO
     */
    private MdmFactoryModelTreeVO convertToTreeVO(MdmFactoryModelEntity entity) {
        MdmFactoryModelTreeVO vo = new MdmFactoryModelTreeVO();
        BeanUtils.copyProperties(entity, vo);

        // 设置类型名称
        FactoryModelTypeEnum typeEnum = FactoryModelTypeEnum.getByCode(entity.getType());
        if (typeEnum != null) {
            vo.setTypeName(typeEnum.getName());
        }

        vo.setHasChildren(false);
        return vo;
    }

    /**
     * 导出excel 表格
     *
     * @return excel 文件流
     */
    @Override
    public List<MdmFactoryModelExcelVO> exportFactoryModel() {
        // 获取已经排好序的树形结构数据
        List<MdmFactoryModelTreeVO> treeList = getFactoryModelTree();

        // 将树形结构转换为平铺的列表
        List<MdmFactoryModelTreeVO> sortedList = flattenTreeToList(treeList);

        // 构建ID到编码的映射，用于设置父节点编码
        Map<Long, String> factoryIdMap = sortedList.stream()
                .collect(Collectors.toMap(MdmFactoryModelTreeVO::getId, MdmFactoryModelTreeVO::getCode));

        return sortedList
                .stream()
                .map(node -> {
                    MdmFactoryModelExcelVO excelVO = new MdmFactoryModelExcelVO();
                    BeanUtils.copyProperties(node, excelVO);
                    String parentCode = factoryIdMap.get(node.getParentId());
                    excelVO.setParentCode(parentCode);
                    excelVO.setType(FactoryModelTypeEnum.getByCode(node.getType()).getName());
                    excelVO.setStatus(AvailableStatusEnum.labelOf(node.getStatus()));
                    return excelVO;
                })
                .collect(Collectors.toList());
    }

    /**
     * 导入工厂建模数据
     *
     * @param excelVOList
     * @param bindingResult
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(value = {"factoryModelNodes", "factoryModelCount", "factoryModelTypes", "factoryModelTree"}, allEntries = true)
    public R importFactoryModel(List<MdmFactoryModelExcelVO> excelVOList, BindingResult bindingResult) {
        // 通用校验获取失败的数据
        List<ErrorMessage> errorMessageList = (List<ErrorMessage>) bindingResult.getTarget();

        // 工厂建模map
        Map<String, MdmFactoryModelEntity> factoryModelMap = this.list().stream()
                .collect(Collectors.toMap(MdmFactoryModelEntity::getCode, Function.identity()));
        List<MdmFactoryModelEntity> insertList = new ArrayList<>();
        Snowflake snowflake = IdUtil.getSnowflake();
        for (MdmFactoryModelExcelVO excelVO : excelVOList) {
            Set<String> errorMsg = new HashSet<>();
            MdmFactoryModelEntity factoryModelEntity = factoryModelMap.get(excelVO.getCode());
            if (factoryModelEntity != null) {
                errorMsg.add(excelVO.getCode() + ":编码已存在");
            }
            MdmFactoryModelEntity savedEntity = new MdmFactoryModelEntity();
            savedEntity.setId(snowflake.nextId());

            Long parentId = null;
            String typeCode = null;
            Integer level = null;

            if ("-1".equals(excelVO.getParentCode())) {
                String excelTypeCode = FactoryModelTypeEnum.getCodeByName(excelVO.getType());
                if (!FactoryModelTypeEnum.INDUSTRY.getCode().equals(excelTypeCode)) {
                    errorMsg.add(excelVO.getType() + ":节点类型不匹配");
                } else {
                    parentId = -1L;
                    typeCode = FactoryModelTypeEnum.INDUSTRY.getCode();
                    level = FactoryModelTypeEnum.INDUSTRY.getLevel();
                }
            } else {
                MdmFactoryModelEntity parentEntity = factoryModelMap.get(excelVO.getParentCode());
                if (parentEntity == null) {
                    errorMsg.add(excelVO.getParentCode() + ":父节点编码不存在");
                } else {
                    typeCode = FactoryModelTypeEnum.getCodeByName(excelVO.getType());
                    level = FactoryModelTypeEnum.getByCode(typeCode).getLevel();
                    if ((parentEntity.getLevel() + 1) != level) {
                        errorMsg.add(excelVO.getType() + ":节点类型不匹配");
                    } else {
                        parentId = parentEntity.getId();
                    }
                }
            }

            if (CollUtil.isEmpty(errorMsg)) {
                savedEntity.setParentId(parentId);
                savedEntity.setCode(excelVO.getCode());
                savedEntity.setName(excelVO.getName());
                savedEntity.setSort(excelVO.getSort());
                savedEntity.setDescription(excelVO.getDescription());
                savedEntity.setType(typeCode);
                savedEntity.setLevel(level);
                savedEntity.setStatus(Boolean.TRUE);
                insertList.add(savedEntity);
                factoryModelMap.put(savedEntity.getCode(), savedEntity);
            } else {
                // 数据不合法情况
                errorMessageList.add(new ErrorMessage(excelVO.getLineNum() + 1, errorMsg));
            }
        }
        this.saveBatch(insertList);
        if (CollUtil.isNotEmpty(errorMessageList)) {
            return R.failed(errorMessageList);
        }
        return R.ok(null, "工厂建模导入成功");
    }

    @Override
    public List<MdmFactoryModelEntity> getByTypeAndCode(String station, String workCenterCode) {
        return List.of();
    }

    /**
     * 根据父节点ID获取子节点列表及其子节点所有上级(工序页面绑定工位，根据线体查询工位时使用)
     *
     * @param parentId 父节点ID
     * @return 子节点列表及其子节点所有上级
     */
    @Override
    public List<MdmFactoryModelAncestorVO> childrenByParent(Long parentId) {
        // 根据parentId获取子级数据
        List<MdmFactoryModelEntity> children = baseMapper.selectByParentId(parentId);
        if (CollectionUtils.isEmpty(children)) {
            return new ArrayList<>();
        }
        return getAncestorNodes(children);
    }

    /**
     * 根据工位列表获取祖先节点列表
     *
     * @param stationList 工位列表
     * @return 祖先节点列表
     */
    private List<MdmFactoryModelAncestorVO> getAncestorNodes(List<MdmFactoryModelEntity> stationList) {
        List<MdmFactoryModelAncestorVO> result = new ArrayList<>();
        if (CollectionUtils.isEmpty(stationList)) {
            return result;
        }

        // 一次性查出所有节点并构建 id -> entity 的映射，避免频繁访问数据库
        Map<Long, MdmFactoryModelEntity> idToEntityMap = list().stream()
                .collect(Collectors.toMap(MdmFactoryModelEntity::getId, Function.identity()));

        // 为每个子级数据获取其所有上级数据（在内存中沿着 parentId 向上查找）
        for (MdmFactoryModelEntity station : stationList) {
            MdmFactoryModelAncestorVO ancestorVO = new MdmFactoryModelAncestorVO();
            // 设置工位信息（当前子级数据，level为5）
            ancestorVO.setStationId(station.getId());
            ancestorVO.setStationCode(station.getCode());
            ancestorVO.setStationName(station.getName());

            // 在内存中向上遍历父链，收集祖先
            List<MdmFactoryModelEntity> ancestors = new ArrayList<>();
            MdmFactoryModelEntity current = station;
            while (current != null && current.getParentId() != null) {
                MdmFactoryModelEntity parent = idToEntityMap.get(current.getParentId());
                if (parent == null) {
                    break;
                }
                ancestors.add(parent);
                current = parent;
            }

            // 按 level 从低到高排序
            ancestors.sort(Comparator.comparing(MdmFactoryModelEntity::getLevel));

            // 遍历祖先节点，填充对应的字段
            for (MdmFactoryModelEntity ancestor : ancestors) {
                switch (ancestor.getLevel()) {
                    case 2: // 工厂级别
                        ancestorVO.setFactoryId(ancestor.getId());
                        ancestorVO.setFactoryCode(ancestor.getCode());
                        ancestorVO.setFactoryName(ancestor.getName());
                        break;
                    case 3: // 车间/仓库级别
                        ancestorVO.setWorkshopId(ancestor.getId());
                        ancestorVO.setWorkshopCode(ancestor.getCode());
                        ancestorVO.setWorkshopName(ancestor.getName());
                        break;
                    case 4: // 线体级别
                        ancestorVO.setLineId(ancestor.getId());
                        ancestorVO.setLineCode(ancestor.getCode());
                        ancestorVO.setLineName(ancestor.getName());
                        break;
                    // level 1 是产业级别，不需要填充到VO中
                    default:
                        break;
                }
            }
            result.add(ancestorVO);
        }
        return result;
    }

    /**
     * 工位打印
     *
     * @param printReq 打印Req
     * @return R 对象
     */
    @Override
    public String print(PrintReq printReq) {
        List<MdmFactoryModelEntity> factoryModelEntityList = this.listByIds(Arrays.asList(printReq.getIds()));
        PreviewObj obj = new PreviewObj();
        obj.setTemplateCode(printReq.getTemplateCode());
        String jsonData = JSONUtil.toJsonStr(factoryModelEntityList);
        obj.setJsonData(jsonData);
        return remotePrintService.preview(obj).getData();
    }

    /**
     * 根据工位ids以列表的形式展示工位及其所有上级数据
     *
     * @param stationIds 工位ids
     * @return 工位及其所有上级数据
     */
    @Override
    public List<MdmFactoryModelAncestorVO> getStationByAllParent(Long[] stationIds) {
        List<MdmFactoryModelEntity> stationList = this.list(new LambdaQueryWrapper<MdmFactoryModelEntity>()
                .in(ArrayUtil.isNotEmpty(stationIds), MdmFactoryModelEntity::getId, stationIds));
        return getAncestorNodes(stationList);
    }

    /**
     * 递归获取节点的所有祖先节点
     *
     * @param nodeId 节点ID
     * @return 祖先节点列表，按level从低到高排序
     */
    private List<MdmFactoryModelEntity> getAncestorsRecursively(Long nodeId) {
        List<MdmFactoryModelEntity> ancestors = new ArrayList<>();
        MdmFactoryModelEntity currentNode = getById(nodeId);

        if (currentNode == null || currentNode.getParentId() == null) {
            return ancestors;
        }

        // 递归获取父节点
        MdmFactoryModelEntity parent = getById(currentNode.getParentId());
        while (parent != null) {
            ancestors.add(parent);
            if (parent.getParentId() == null) {
                break;
            }
            parent = getById(parent.getParentId());
        }

        // 按level排序，确保从低到高
        ancestors.sort(Comparator.comparing(MdmFactoryModelEntity::getLevel));

        return ancestors;
    }

    @Override
    public Set<Long> getChildrenIds(Long parentId) {
        if (parentId == null) {
            return new HashSet<>();
        }

        Set<Long> childrenIds = new HashSet<>();
        getChildrenIdsRecursive(parentId, childrenIds);
        return childrenIds;
    }

    /**
     * 根据父节点ID获取所有子节点-分页
     *
     * @param page                  分页对象，包含分页参数
     * @param mdmFactoryModelEntity 查询条件
     * @return 所有子节点
     */
    @Override
    public Page<MdmFactoryModelEntity> getAllChildrenPage(Page page, MdmFactoryModelEntity mdmFactoryModelEntity) {

        // 构建查询条件
        LambdaQueryWrapper<MdmFactoryModelEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(mdmFactoryModelEntity.getId() != null, MdmFactoryModelEntity::getId, mdmFactoryModelEntity.getId())
                .eq(mdmFactoryModelEntity.getLevel() != null, MdmFactoryModelEntity::getLevel, mdmFactoryModelEntity.getLevel())
                .eq(mdmFactoryModelEntity.getStatus() != null, MdmFactoryModelEntity::getStatus, mdmFactoryModelEntity.getStatus())
                .eq(StrUtil.isNotBlank(mdmFactoryModelEntity.getCode()), MdmFactoryModelEntity::getCode, mdmFactoryModelEntity.getCode())
                .eq(StrUtil.isNotBlank(mdmFactoryModelEntity.getName()), MdmFactoryModelEntity::getName, mdmFactoryModelEntity.getName())
                .orderByAsc(MdmFactoryModelEntity::getSort);

        // 如果指定了父节点ID，需要先获取所有子节点ID
        if (mdmFactoryModelEntity.getParentId() != null) {
            Set<Long> childrenIds = getChildrenIds(mdmFactoryModelEntity.getParentId());
            if (childrenIds.isEmpty()) {
                return new Page<>();
            }
            wrapper.in(MdmFactoryModelEntity::getId, childrenIds);
        }

        return this.page(page, wrapper);
    }

    /**
     * 根据父节点ID获取所有子节点
     *
     * @param parentId 父节点ID
     * @return 所有子节点
     */
    @Override
    public List<MdmFactoryModelEntity> getAllChildren(Long parentId) {
        Set<Long> childrenIds = getChildrenIds(parentId);
        if (childrenIds.isEmpty()) {
            return new ArrayList<>();
        }

        LambdaQueryWrapper<MdmFactoryModelEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(MdmFactoryModelEntity::getId, childrenIds)
                .orderByAsc(MdmFactoryModelEntity::getLevel)
                .orderByAsc(MdmFactoryModelEntity::getSort);

        return list(wrapper);
    }

    /**
     * 递归获取所有子节点ID
     *
     * @param parentId    父节点ID
     * @param childrenIds 子节点ID集合，用于收集结果
     */
    private void getChildrenIdsRecursive(Long parentId, Set<Long> childrenIds) {
        // 参数校验
        if (parentId == null) {
            return;
        }

        // 构建查询条件，只查询ID和父节点ID，减少数据传输量
        LambdaQueryWrapper<MdmFactoryModelEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(MdmFactoryModelEntity::getId, MdmFactoryModelEntity::getParentId)
                .eq(MdmFactoryModelEntity::getParentId, parentId)
                .eq(MdmFactoryModelEntity::getStatus, Boolean.TRUE);

        List<MdmFactoryModelEntity> children = list(wrapper);

        // 递归处理每个子节点
        for (MdmFactoryModelEntity child : children) {
            childrenIds.add(child.getId());
            // 递归获取子节点的子节点
            getChildrenIdsRecursive(child.getId(), childrenIds);
        }
    }


    /**
     * 将树形结构转换为平铺的列表
     * 按照深度优先遍历的顺序展开树形结构
     *
     * @param treeList 树形结构列表
     * @return 平铺的实体列表
     */
    private List<MdmFactoryModelTreeVO> flattenTreeToList(List<MdmFactoryModelTreeVO> treeList) {
        List<MdmFactoryModelTreeVO> result = new ArrayList<>();

        for (MdmFactoryModelTreeVO treeNode : treeList) {
            addTreeNodeAndChildren(treeNode, result);
        }

        return result;
    }

    /**
     * 递归添加树节点及其所有子节点到结果列表
     * 按照深度优先遍历的顺序
     *
     * @param treeNode 当前树节点
     * @param result   结果列表
     */
    private void addTreeNodeAndChildren(MdmFactoryModelTreeVO treeNode, List<MdmFactoryModelTreeVO> result) {
        // 直接添加当前树节点
        result.add(treeNode);

        // 递归处理子节点
        if (!CollectionUtils.isEmpty(treeNode.getChildren())) {
            for (MdmFactoryModelTreeVO child : treeNode.getChildren()) {
                addTreeNodeAndChildren(child, result);
            }
        }
    }
}