package com.kitex.mom.mdm.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.vo.ErrorMessage;
import com.kitex.mom.mdm.dto.MdmBomChildDTO;
import com.kitex.mom.mdm.dto.MdmBomDTO;
import com.kitex.mom.mdm.entity.MdmBomChildEntity;
import com.kitex.mom.mdm.entity.MdmBomEntity;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.mapper.MdmBomChildMapper;
import com.kitex.mom.mdm.mapper.MdmBomMapper;
import com.kitex.mom.mdm.service.MdmBomService;
import com.kitex.mom.mdm.service.MdmMaterialService;
import com.kitex.mom.mdm.utils.DynamicRefreshUtils;
import com.kitex.mom.mdm.vo.MdmBomTreeVO;
import com.kitex.mom.mdm.vo.excel.MdmBomExcelVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.BindingResult;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * BOM
 *
 * @author kitex
 * @date 2024-10-31 09:14:59
 */
@Service
@RequiredArgsConstructor
public class MdmBomServiceImpl extends ServiceImpl<MdmBomMapper, MdmBomEntity> implements MdmBomService {

    private final MdmBomChildMapper mdmBomChildMapper;

    private final MdmMaterialService mdmMaterialService;

    /**
     * 新增bom信息
     *
     * @param mdmBomDTO
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveDeep(MdmBomDTO mdmBomDTO) {
        MdmBomEntity mdmBom = new MdmBomEntity();
        BeanUtil.copyProperties(mdmBomDTO, mdmBom);
        // 校验是否存在相同的版本号
        LambdaQueryWrapper<MdmBomEntity> wrapper = Wrappers.<MdmBomEntity>lambdaQuery()
                .eq(MdmBomEntity::getProductCode, mdmBomDTO.getProductCode())
                .eq(MdmBomEntity::getVersion, mdmBomDTO.getVersion());
        if (!Objects.isNull(this.getOne(wrapper, false))) {
            throw new RuntimeException("当前产品的BOM版本号已存在");
        }
        baseMapper.insert(mdmBom);
        DynamicRefreshUtils.refreshMdm(mdmBom);
        for (MdmBomChildEntity mdmBomChild : mdmBomDTO.getMdmBomChildList()) {
            mdmBomChild.setBomId(mdmBom.getId());
            // TODO 查询当前这个物料是否有bom信息
            mdmBomChildMapper.insert(mdmBomChild);
        }
        return Boolean.TRUE;
    }

    /**
     * 修改bom信息
     *
     * @param mdmBomDTO
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateDeep(MdmBomDTO mdmBomDTO) {
        MdmBomEntity mdmBom = new MdmBomEntity();
        BeanUtil.copyProperties(mdmBomDTO, mdmBom);
        baseMapper.updateById(mdmBom);
        DynamicRefreshUtils.refreshMdm(mdmBom);
        // 删除旧数据
        mdmBomChildMapper
                .delete(Wrappers.<MdmBomChildEntity>lambdaQuery().eq(MdmBomChildEntity::getBomId, mdmBom.getId()));
        for (MdmBomChildEntity mdmBomChild : mdmBomDTO.getMdmBomChildList()) {
            mdmBomChild.setId(null);
            mdmBomChild.setBomId(mdmBom.getId());
            mdmBomChildMapper.insert(mdmBomChild);
        }
        return Boolean.TRUE;
    }

    /**
     * 删除bom信息
     *
     * @param ids
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeDeep(Long[] ids) {
        baseMapper.deleteByIds(CollUtil.toList(ids));
        mdmBomChildMapper.delete(Wrappers.<MdmBomChildEntity>lambdaQuery().in(MdmBomChildEntity::getBomId, ids));
        return Boolean.TRUE;
    }

    /**
     * 删除bom的物料
     *
     * @param ids
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeChild(Long[] ids) {
        mdmBomChildMapper.deleteByIds(CollUtil.toList(ids));
        return Boolean.TRUE;
    }

    /**
     * 根据条件查询bom信息
     *
     * @param mdmBom 查询条件
     * @return 完整的bom信息 或者 null
     */
    @Override
    public MdmBomDTO getBomDeep(MdmBomEntity mdmBom) {
        MdmBomDTO mdmBomDTO = new MdmBomDTO();
        MdmBomEntity mdmBom1 = this.getOne(Wrappers.query(mdmBom), false);
        if (Objects.isNull(mdmBom1)) {
            return null;
        }
        BeanUtil.copyProperties(mdmBom1, mdmBomDTO);
        mdmBomDTO.setMdmBomChildList(mdmBomChildMapper
                .selectList(Wrappers.<MdmBomChildEntity>lambdaQuery().eq(MdmBomChildEntity::getBomId, mdmBom1.getId())));
        return mdmBomDTO;
    }

    /**
     * 根据产品编码获取该产品所有子物料bom
     *
     * @param productCode 产品编码
     * @param bomId       bomId
     * @param version     版本
     * @return 子物料集合
     */
    @Override
    public List<MdmBomChildDTO> getChildMaterials(String productCode, Long bomId, String version) {
        List<MdmBomChildEntity> allBomChild = mdmBomChildMapper.selectList(Wrappers.lambdaQuery());
        List<MdmBomChildDTO> result = new ArrayList<>();
        MdmBomEntity bomEntity = this
                .getOne(Wrappers.<MdmBomEntity>lambdaQuery().eq(MdmBomEntity::getProductCode, productCode)
                        .eq(MdmBomEntity::getId, bomId)
                        .eq(StrUtil.isNotBlank(version), MdmBomEntity::getVersion, version)
                        .orderByDesc(MdmBomEntity::getVersion).last("limit 1"));
        if (Objects.isNull(bomEntity)) {
            return null;
        }
        getChildMaterials(allBomChild, result, bomEntity.getId());
        return result;
    }

    /**
     * 根据产品编码列表获取该产品所有子物料bom信息包括自己
     *
     * @param productCodes 产品编码列表
     * @return 子物料集合, 按层次区分
     */
    @Override
    public Map<String, MdmBomChildDTO> getAllChildMaterials(List<String> productCodes) {
        // 构造返回体
        Map<String, MdmBomChildDTO> result = new HashMap<>();
        BigDecimal parentQuantity = new BigDecimal(1);
        List<MdmBomChildEntity> allBomChild = mdmBomChildMapper.selectList(Wrappers.lambdaQuery());
        for (String productCode : productCodes) {
            List<MdmBomChildDTO> resultList = new ArrayList<>();
            // 查询产品的bom
            MdmBomEntity bomEntity = this.getOne(Wrappers.<MdmBomEntity>lambdaQuery().eq(MdmBomEntity::getProductCode, productCode)
                    .orderByDesc(MdmBomEntity::getVersion).last("limit 1"));
            // 添加当前产品信息并将子级bom添加
            MdmBomChildDTO bomChildDTO = new MdmBomChildDTO();
            bomChildDTO.setMaterialCode(productCode);
            if (Objects.isNull(bomEntity)) {
                bomChildDTO.setChildren(resultList);
                result.put(productCode, bomChildDTO);
                continue;
            }
            getChildMaterials(allBomChild, resultList, bomEntity.getId());
            bomChildDTO.setMaterialName(bomEntity.getProductName());
            bomChildDTO.setQuantity(parentQuantity);
            bomChildDTO.setBomId(bomEntity.getId());
            bomChildDTO.setSubitemBomId(0L);
            bomChildDTO.setChildren(resultList);
            result.put(productCode, bomChildDTO);
        }

        return result;
    }

    /**
     * 导出excel 表格
     *
     * @param mdmBom 查询条件
     * @param ids    导出指定ID
     * @return excel 文件流
     */
    @Override
    public List<MdmBomExcelVO> exportExcel(MdmBomEntity mdmBom, Long[] ids) {
        List<MdmBomEntity> list = this.list(Wrappers.lambdaQuery(mdmBom).in(ArrayUtil.isNotEmpty(ids), MdmBomEntity::getId, ids));
        List<MdmBomChildEntity> allBomChild = mdmBomChildMapper.selectList(Wrappers.lambdaQuery());
        List<MdmBomExcelVO> result = new ArrayList<>();

        // 递归方法，组装层级
        class BomExportNode {
            void build(MdmBomEntity entity, String levelPrefix) {
                MdmBomExcelVO vo = new MdmBomExcelVO();
                // 复制主表属性到VO
                BeanUtils.copyProperties(entity, vo);
                vo.setLevel(levelPrefix);
                vo.setProductCode(entity.getProductCode());
                vo.setProductName(entity.getProductName());
                vo.setVersion(entity.getVersion());
                result.add(vo);

                // 递归处理子项
                List<MdmBomChildEntity> children = allBomChild.stream()
                        .filter(child -> child.getBomId().equals(entity.getId()))
                        .toList();
                int idx = 1;
                for (MdmBomChildEntity child : children) {
                    buildChild(child, levelPrefix + "." + idx, 1, entity.getVersion());
                    idx++;
                }
            }

            void buildChild(MdmBomChildEntity child, String level, int depth, String parentVersion) {
                MdmBomExcelVO vo = new MdmBomExcelVO();
                BeanUtils.copyProperties(child, vo);
                vo.setLevel(level);
                vo.setProductCode(child.getMaterialCode());
                vo.setProductName(child.getMaterialName());
                vo.setVersion(parentVersion); // 取父级version
                result.add(vo);
                // 递归处理子项
                List<MdmBomChildEntity> children = allBomChild.stream()
                        .filter(c -> c.getBomId().equals(child.getSubitemBomId()))
                        .toList();
                int idx = 1;
                for (MdmBomChildEntity sub : children) {
                    buildChild(sub, level + "." + idx, depth + 1, parentVersion);
                    idx++;
                }
            }
        }

        BomExportNode node = new BomExportNode();
        int rootIdx = 1;
        for (MdmBomEntity entity : list) {
            node.build(entity, String.valueOf(rootIdx));
            rootIdx++;
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean bomCopy(Long id) {
        MdmBomEntity bomEntity = this.getById(id);

        MdmBomEntity copyBomEntity = new MdmBomEntity();
        BeanUtils.copyProperties(bomEntity, copyBomEntity, "id");

        // 设置版本号，如果已存在则递增
        String baseVersion = bomEntity.getVersion() + "-复制-";
        String newVersion = baseVersion + "01";
        int count = 1;

        // 查询是否存在相同版本号
        while (this.count(Wrappers.<MdmBomEntity>lambdaQuery()
                .eq(MdmBomEntity::getProductCode, bomEntity.getProductCode())
                .eq(MdmBomEntity::getVersion, newVersion)) > 0) {
            count++;
            newVersion = baseVersion + String.format("%02d", count);
        }
        copyBomEntity.setVersion(newVersion);

        this.save(copyBomEntity);

        // 获取子集bom
        List<MdmBomChildEntity> bomChild = mdmBomChildMapper.selectList(new LambdaQueryWrapper<MdmBomChildEntity>()
                .eq(MdmBomChildEntity::getBomId, bomEntity.getId()));

        // 复制子集数据
        for (MdmBomChildEntity child : bomChild) {
            MdmBomChildEntity copyChild = new MdmBomChildEntity();
            BeanUtils.copyProperties(child, copyChild, "id");
            copyChild.setBomId(copyBomEntity.getId());
            mdmBomChildMapper.insert(copyChild);
        }

        return Boolean.TRUE;
    }

    /**
     * bom树查询
     *
     * @param queryWrapper 查询条件
     * @return
     */
    @Override
    public List<MdmBomTreeVO> tree(QueryWrapper<MdmBomEntity> queryWrapper) {
        // 构建返回体VO
        List<MdmBomTreeVO> treeList = new ArrayList<>();
        // 获取bom列表
        List<MdmBomEntity> list = this.list(queryWrapper);

        // 按照productCode分组
        Map<String, List<MdmBomEntity>> groupedByProduct = list.stream()
                .collect(Collectors.groupingBy(
                        MdmBomEntity::getProductCode,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
        // 处理每个产品分组
        groupedByProduct.forEach((productCode, boms) -> {
            if (!boms.isEmpty()) {
                MdmBomEntity firstRoute = boms.get(0);
                MdmBomTreeVO parentNode = new MdmBomTreeVO();
                parentNode.setId(firstRoute.getId());
                parentNode.setTitle(String.format("%s(%s)", firstRoute.getProductName(), productCode));
                parentNode.setProductCode(productCode);
                parentNode.setProductName(firstRoute.getProductName());
                parentNode.setProductSpec(firstRoute.getProductSpec());
                parentNode.setProductUnit(firstRoute.getUnit());

                // 创建子节点列表
                List<MdmBomEntity> children = java.util.stream.Stream.concat(
                        boms.stream().filter(route -> Boolean.TRUE.equals(route.getIsDefault())),
                        boms.stream().filter(route -> !Boolean.TRUE.equals(route.getIsDefault()))
                ).collect(Collectors.toList());

                parentNode.setChildren(children);
                treeList.add(parentNode);
            }
        });
        return treeList;
    }

    /**
     * 根据id设置默认bom
     *
     * @param id id
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean bomDefault(Long id) {
        MdmBomEntity bomEntity = this.getById(id);

        this.update(new LambdaUpdateWrapper<MdmBomEntity>()
                .eq(MdmBomEntity::getProductCode, bomEntity.getProductCode())
                .ne(MdmBomEntity::getId, id)
                .set(MdmBomEntity::getIsDefault, Boolean.FALSE));
        return this.update(new LambdaUpdateWrapper<MdmBomEntity>()
                .eq(MdmBomEntity::getId, id)
                .set(MdmBomEntity::getIsDefault, Boolean.TRUE));
    }

    /**
     * 导入excel 表
     *
     * @param excelVOList   对象实体列表
     * @param bindingResult 错误信息列表
     * @return ok fail
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public R importExcel(List<MdmBomExcelVO> excelVOList, BindingResult bindingResult) {
        // 通用校验获取失败的数据
        List<ErrorMessage> errorMessageList = (List<ErrorMessage>) bindingResult.getTarget();

        Map<String, MdmMaterialEntity> materialMap = mdmMaterialService.list()
                .stream()
                .collect(Collectors.toMap(MdmMaterialEntity::getMaterialCode, Function.identity()));
        // 查询bom数据
        Map<String, Long> bomMap = this.list().stream().collect(Collectors.toMap(
                bom -> bom.getProductCode() + "#" + bom.getVersion(),
                MdmBomEntity::getId,
                (id1, id2) -> id1 // 若有重复，取第一个
        ));
        // bom主表存在产品对应的版本准备删除的数据
        List<Long> delListIds = new ArrayList<>();
        // 准备插入到bom主表的数据
        List<MdmBomEntity> insertList = new ArrayList<>();
        // 准备插入到bom子表的数据
        List<MdmBomChildEntity> insertChildList = new ArrayList<>();
        Snowflake snowflake = IdUtil.getSnowflake();

        // 新增：层级关系缓存
        Map<String, Object> levelMap = new HashMap<>(); // 层级->实体
        Map<String, List<String>> childrenMap = new HashMap<>(); // 父层级->子层级
        Map<String, Long> levelIdMap = new HashMap<>(); // 层级->ID

        // 先统计所有层级的父子关系
        for (MdmBomExcelVO vo : excelVOList) {
            String level = vo.getLevel(); // 假设有getLevel方法
            String parentLevel = level.contains(".") ? level.substring(0, level.lastIndexOf(".")) : null;
            if (parentLevel != null) {
                childrenMap.computeIfAbsent(parentLevel, k -> new ArrayList<>()).add(level);
            }
        }

        // 校验和数据准备
        for (MdmBomExcelVO mdmBomExcelVO : excelVOList) {
            Set<String> errorMsg = new HashSet<>();
            // 查询数据是否存在
            String bomKey = mdmBomExcelVO.getProductCode() + "#" + mdmBomExcelVO.getVersion();
            Long id = bomMap.get(bomKey);
            if (id != null) {
                delListIds.add(id);
            }
            MdmMaterialEntity matEntity = materialMap.get(mdmBomExcelVO.getProductCode());
            if (matEntity == null) {
                errorMsg.add(mdmBomExcelVO.getProductCode() + "产品编码不存在");
            } else {
                mdmBomExcelVO.setProductName(matEntity.getMaterialName());
                mdmBomExcelVO.setUnit(matEntity.getBasicUnit());
                mdmBomExcelVO.setMaterialAttribute(matEntity.getMaterialAttribute());
            }
            if (mdmBomExcelVO.getStartDate() != null && mdmBomExcelVO.getEndDate() != null) {
                if (mdmBomExcelVO.getStartDate().isAfter(mdmBomExcelVO.getEndDate())) {
                    errorMsg.add("开始日期不能大于截止日期");
                }
            }
            if (CollUtil.isNotEmpty(errorMsg)) {
                errorMessageList.add(new ErrorMessage(mdmBomExcelVO.getLineNum() + 1, errorMsg));
            }
        }
        if (CollUtil.isNotEmpty(errorMessageList)) {
            return R.failed(errorMessageList);
        }

        // 构建层级结构并分配ID
        for (MdmBomExcelVO vo : excelVOList) {
            String level = vo.getLevel();
            Long id = snowflake.nextId();
            levelIdMap.put(level, id);
        }

        // 第一遍：插入数据，构建levelMap
        Map<String, MdmBomChildEntity> childLevelMap = new HashMap<>(); // 只存子层级
        for (MdmBomExcelVO vo : excelVOList) {
            String level = vo.getLevel();
            String parentLevel = level.contains(".") ? level.substring(0, level.lastIndexOf(".")) : null;
            Long id = levelIdMap.get(level);

            if (parentLevel == null) {
                // 根层级，只插入insertList
                MdmBomEntity entity = insertExcel(vo, snowflake);
                entity.setId(id);
                insertList.add(entity);
                levelMap.put(level, entity);
            } else {
                // 子层级，插入insertChildList
                MdmBomChildEntity child = new MdmBomChildEntity();
                BeanUtils.copyProperties(vo, child);
                child.setMaterialName(vo.getProductName());
                child.setMaterialCode(vo.getProductCode());
                child.setId(id);
                // 设置父级bomId
                Object parentObj = levelMap.get(parentLevel);
                Long parentId = null;
                if (parentObj instanceof MdmBomEntity) {
                    parentId = ((MdmBomEntity) parentObj).getId();
                } else if (parentObj instanceof MdmBomChildEntity) {
                    parentId = ((MdmBomChildEntity) parentObj).getId();
                }
                child.setBomId(parentId);
                // 不设置subitemBomId，后续统一处理
                insertChildList.add(child);
                levelMap.put(level, child);
                childLevelMap.put(level, child);

                // 如果有子集，还要插入insertList
                if (childrenMap.containsKey(level)) {
                    MdmBomEntity entity = insertExcel(vo, snowflake);
                    entity.setId(id);
                    insertList.add(entity);
                    levelMap.put(level, entity);
                }
            }
        }

        // 第二遍：为有子集的子层级设置subitemBomId
        for (Map.Entry<String, MdmBomChildEntity> entry : childLevelMap.entrySet()) {
            String level = entry.getKey();
            MdmBomChildEntity child = entry.getValue();
            if (childrenMap.containsKey(level)) {
                String firstSubLevel = childrenMap.get(level).get(0);
                MdmBomChildEntity subChild = childLevelMap.get(firstSubLevel);
                if (subChild != null) {
                    child.setSubitemBomId(subChild.getBomId());
                }
            }
        }
        // 先删除存在重复的数据
        if (delListIds.size() > 0) {
            this.removeBatchByIds(delListIds);
        }
        // 再插入bom数据
        if (insertList.size() > 0) {
            this.saveBatch(insertList);
        }
        for (MdmBomChildEntity mdmBomChild : insertChildList) {
            mdmBomChildMapper.insert(mdmBomChild);
        }
        return R.ok(null, "BOM导入成功");
    }

    /**
     * 根据产品编码获取该产品子物料bom信息(一层)
     *
     * @param productCode 产品编码
     */
    @Override
    public List<MdmBomChildEntity> getBomChild(String productCode, String bomVersion) {
        LambdaQueryWrapper<MdmBomEntity> wrapper = Wrappers.<MdmBomEntity>lambdaQuery()
                .eq(MdmBomEntity::getProductCode, productCode);
        if (StrUtil.isNotBlank(bomVersion)) {
            wrapper.eq(MdmBomEntity::getVersion, bomVersion);
        } else {
            wrapper.orderByDesc(MdmBomEntity::getVersion).last("limit 1");
        }
        MdmBomEntity mdmBomEntity = this.getOne(wrapper);
        if (Objects.isNull(mdmBomEntity)) {
            return null;
        }
        return mdmBomChildMapper.selectList(Wrappers.<MdmBomChildEntity>lambdaQuery().eq(MdmBomChildEntity::getBomId, mdmBomEntity.getId()));
    }

    private MdmBomEntity insertExcel(MdmBomExcelVO excelVO, Snowflake snowflake) {
        MdmBomEntity mdmBom = new MdmBomEntity();
        BeanUtils.copyProperties(excelVO, mdmBom);
        mdmBom.setId(snowflake.nextId());
        mdmBom.setEnabled(Boolean.TRUE);
        return mdmBom;
    }

    /**
     * 递归获取子物料
     */
    private void getChildMaterials(List<MdmBomChildEntity> allBomChild, List<MdmBomChildDTO> result, Long bomId) {
        List<MdmBomChildDTO> childEntities = allBomChild.stream()
                .filter(item -> item.getBomId().equals(bomId))
                .toList()
                .stream()
                .map(item -> {
                    MdmBomChildDTO mdmBomChildDTO = new MdmBomChildDTO();
                    BeanUtil.copyProperties(item, mdmBomChildDTO);
                    return mdmBomChildDTO;
                })
                .toList();
        result.addAll(childEntities);
        for (MdmBomChildDTO childEntity : childEntities) {
            if (ObjUtil.isNotEmpty(childEntity.getSubitemBomId())) {
                childEntity.setChildren(new ArrayList<>());
                getChildMaterials(allBomChild, childEntity.getChildren(), childEntity.getSubitemBomId());
            }
        }
    }

}
