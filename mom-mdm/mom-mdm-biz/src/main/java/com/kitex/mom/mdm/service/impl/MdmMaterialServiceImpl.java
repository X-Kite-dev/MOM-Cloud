package com.kitex.mom.mdm.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.admin.api.entity.SysDictItemEntity;
import com.kitex.admin.api.feign.RemoteDictService;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantBroker;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.excel.vo.ErrorMessage;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.common.barcode.api.feign.RemotePrintService;
import com.kitex.mom.common.barcode.dto.req.PreviewObj;
import com.kitex.mom.common.barcode.dto.req.PrintReq;
import com.kitex.mom.mdm.dto.MdmMaterialMeasureDTO;
import com.kitex.mom.mdm.dto.MdmMaterialWarningConfigDTO;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.entity.MdmMaterialGroupEntity;
import com.kitex.mom.mdm.mapper.MdmMaterialMapper;
import com.kitex.mom.mdm.service.MdmMaterialGroupService;
import com.kitex.mom.mdm.service.MdmMaterialMaintenanceValidator;
import com.kitex.mom.mdm.service.MdmMaterialService;
import com.kitex.mom.mdm.util.FileUrlUtil;
import com.kitex.mom.mdm.vo.excel.MdmMaterialExcelVO;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.BindingResult;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 物料表
 *
 * @author kitex
 * @date 2024-10-17 10:30:41
 */
@Service
@AllArgsConstructor
public class MdmMaterialServiceImpl extends ServiceImpl<MdmMaterialMapper, MdmMaterialEntity>
        implements MdmMaterialService {

    private final MdmMaterialGroupService mdmMaterialGroupService;

    private final RemoteDictService remoteDictService;

    private final RemotePrintService remotePrintService;

    private final FileUrlUtil fileUrlUtil;

    private final JdbcTemplate maintenanceJdbc;

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean saveMaintained(MdmMaterialEntity material) {
        lockMaintenanceScope();
        requireMaterial(material != null && material.getId() == null, "新增物料不能携带原主键");
        applyMaintenanceScope(material);
        requireMaterial(StrUtil.isNotBlank(material.getMaterialCode()) && StrUtil.isNotBlank(material.getMaterialName())
                && StrUtil.isNotBlank(material.getBasicUnit()), "物料编码、名称及基本单位不能为空");
        validateMaintenanceGroup(material);
        MdmMaterialMaintenanceValidator.validate(material);
        requireMaterial(count(new LambdaQueryWrapper<MdmMaterialEntity>()
                .eq(MdmMaterialEntity::getTenantId, material.getTenantId())
                .eq(MdmMaterialEntity::getFactoryCode, material.getFactoryCode())
                .eq(MdmMaterialEntity::getMaterialCode, material.getMaterialCode())) == 0, "当前工厂已存在该物料编码");
        requireMaterial(super.save(material), "物料未保存");
        return true;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateMaintained(MdmMaterialEntity material) {
        lockMaintenanceScope();
        requireMaterial(material != null && material.getId() != null, "请选择原物料");
        applyMaintenanceScope(material);
        MdmMaterialEntity original = getOne(new LambdaQueryWrapper<MdmMaterialEntity>()
                .eq(MdmMaterialEntity::getId, material.getId())
                .eq(MdmMaterialEntity::getTenantId, material.getTenantId())
                .eq(MdmMaterialEntity::getFactoryCode, material.getFactoryCode()).last("FOR UPDATE"));
        requireMaterial(original != null, "物料不存在或不属于当前实际组织");
        requireMaterial(material.getMaterialCode() == null || Objects.equals(original.getMaterialCode(), material.getMaterialCode()), "物料编码不能改绑");
        if (material.getGroupCode() != null && !Objects.equals(original.getGroupCode(), material.getGroupCode())) {
            validateMaintenanceGroup(material);
        }
        // MyBatis既有更新忽略null；先合并原值，避免部分更新绕过库存上下限等关联校验。
        var incoming = new BeanWrapperImpl(material);
        String[] nullFields = Arrays.stream(incoming.getPropertyDescriptors())
                .map(java.beans.PropertyDescriptor::getName).filter(name -> incoming.getPropertyValue(name) == null)
                .toArray(String[]::new);
        BeanUtils.copyProperties(material, original, nullFields);
        requireMaterial(StrUtil.isNotBlank(original.getMaterialName()) && StrUtil.isNotBlank(original.getBasicUnit()),
                "物料名称及基本单位不能为空");
        MdmMaterialMaintenanceValidator.validate(original);
        requireMaterial(super.updateById(material), "物料未更新，请刷新后核对");
        return true;
    }

    /** 新物料及变更分组须匹配当前工厂唯一启用分组，名称由真实来源还原。 */
    private void validateMaintenanceGroup(MdmMaterialEntity material) {
        requireMaterial(StrUtil.isNotBlank(material.getGroupCode()), "请选择物料分组");
        List<MdmMaterialGroupEntity> groups = mdmMaterialGroupService.list(new LambdaQueryWrapper<MdmMaterialGroupEntity>()
                .eq(MdmMaterialGroupEntity::getTenantId, material.getTenantId())
                .eq(MdmMaterialGroupEntity::getFactoryCode, material.getFactoryCode())
                .eq(MdmMaterialGroupEntity::getGroupCode, material.getGroupCode())
                .eq(MdmMaterialGroupEntity::getEnabled, true).last("LIMIT 2"));
        requireMaterial(groups.size() == 1, "物料分组未唯一归属当前工厂或未启用");
        material.setGroupName(groups.get(0).getGroupName());
    }

    /** 正式维护和导入共用组织锁，防止同一工厂的编码查重与插入相互穿越。 */
    private void lockMaintenanceScope() {
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        requireMaterial(tenant != null && tenant > 0 && StrUtil.isNotBlank(factory)
                && !Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
                && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()), "物料维护需要明确租户与实际工厂");
        maintenanceJdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "MDM_MATERIAL:" + tenant + ":" + factory);
    }

    /** 不接受客户端用请求正文覆盖当前组织。 */
    private void applyMaintenanceScope(MdmMaterialEntity material) {
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        requireMaterial((material.getTenantId() == null || tenant.equals(material.getTenantId()))
                && (material.getFactoryCode() == null || factory.equals(material.getFactoryCode())), "物料组织与当前上下文不一致");
        material.setTenantId(tenant);
        material.setFactoryCode(factory);
    }

    private static void requireMaterial(boolean valid, String message) {
        if (!valid) {
            throw new CheckedException(message);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<MdmMaterialWarningConfigDTO> warningConfigurations(Long tenantId, String factoryCode) {
        if (tenantId == null || tenantId <= 0 || StrUtil.isBlank(factoryCode)) {
            throw new CheckedException("物料预警参数需要明确租户和工厂");
        }
        String previousFactory = FactoryContextHolder.getFactoryCode();
        try {
            FactoryContextHolder.setFactoryCode(factoryCode);
            return TenantBroker.applyAs(tenantId, ignored -> list(new LambdaQueryWrapper<MdmMaterialEntity>()
                    .select(MdmMaterialEntity::getTenantId, MdmMaterialEntity::getFactoryCode,
                            MdmMaterialEntity::getMaterialCode, MdmMaterialEntity::getMaterialName,
                            MdmMaterialEntity::getExpiration, MdmMaterialEntity::getExpirationDay,
                            MdmMaterialEntity::getSlowMaterial, MdmMaterialEntity::getSlowMaterialDay)
                    .eq(MdmMaterialEntity::getTenantId, tenantId)
                    .eq(MdmMaterialEntity::getFactoryCode, factoryCode)).stream().map(row -> {
                if (!Objects.equals(row.getTenantId(), tenantId)
                        || !Objects.equals(row.getFactoryCode(), factoryCode)) {
                    throw new CheckedException("预警参数返回了其他租户或工厂物料");
                }
                var result = new MdmMaterialWarningConfigDTO();
                BeanUtils.copyProperties(row, result);
                return result;
            }).toList());
        } finally {
            FactoryContextHolder.setFactoryCode(previousFactory);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public MdmMaterialMeasureDTO productionMeasure(String materialCode) {
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        var actor = SecurityUtils.getUser();
        if (tenant == null || tenant <= 0 || TenantContextHolder.getTenantSkip()
                || Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) || StrUtil.isBlank(factory)
                || actor == null || actor.getId() == null || StrUtil.isBlank(materialCode)) {
            throw new CheckedException("物料计量观察需要已认证用户与实际租户工厂，禁止跳过隔离");
        }
        var rows = list(new LambdaQueryWrapper<MdmMaterialEntity>()
                .eq(MdmMaterialEntity::getTenantId, tenant).eq(MdmMaterialEntity::getFactoryCode, factory)
                .eq(MdmMaterialEntity::getMaterialCode, materialCode).last("LIMIT 2"));
        if (rows.size() != 1 || !Boolean.TRUE.equals(rows.get(0).getStatus())
                || StrUtil.isBlank(rows.get(0).getBasicUnit())) {
            throw new CheckedException("当前租户工厂未找到唯一启用物料及明确基本单位");
        }
        var row = rows.get(0);
        var result = new MdmMaterialMeasureDTO();
        result.setMaterialId(row.getId().toString());
        result.setTenantId(row.getTenantId().toString());
        result.setFactoryCode(row.getFactoryCode());
        result.setMaterialCode(row.getMaterialCode());
        result.setBasicUnit(row.getBasicUnit());
        result.setEnabled(row.getStatus());
        // 以原值.toString保留微秒；不截到秒，不把观察时间当发布版本。
        result.setUpdatedAt(row.getUpdateTime() == null ? null : row.getUpdateTime().toString());
        return result;
    }

    /**
     * 导入excel 表格
     *
     * @param excelVOList   物料列表
     * @param bindingResult 错误信息列表
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public R importMaterial(List<MdmMaterialExcelVO> excelVOList, BindingResult bindingResult) {
        // 通用校验获取失败的数据
        List<ErrorMessage> errorMessageList = (List<ErrorMessage>) bindingResult.getTarget();
        // 单位字典
        Map<String, String> materialUnitMap = remoteDictService.getDictByType("material_unit")
                .getData()
                .stream()
                .collect(Collectors.toMap(SysDictItemEntity::getLabel, SysDictItemEntity::getItemValue, (a, b) -> a));
        // 物料型态字典
        Map<String, String> materialTypeMap = remoteDictService.getDictByType("material_type")
                .getData()
                .stream()
                .collect(Collectors.toMap(SysDictItemEntity::getLabel, SysDictItemEntity::getItemValue, (a, b) -> a));
        // 物料属性字典
        Map<String, String> materialAttributeMap = remoteDictService.getDictByType("material_attribute")
                .getData()
                .stream()
                .collect(Collectors.toMap(SysDictItemEntity::getLabel, SysDictItemEntity::getItemValue, (a, b) -> a));
        lockMaintenanceScope();
        // 物料编码的唯一范围为当前实际租户及工厂。
        Map<String, String> materialMap = this.list(new LambdaQueryWrapper<MdmMaterialEntity>()
                .eq(MdmMaterialEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MdmMaterialEntity::getFactoryCode, FactoryContextHolder.getFactoryCode()))
                .stream()
                .collect(Collectors.toMap(MdmMaterialEntity::getMaterialCode, MdmMaterialEntity::getMaterialCode,
                        (a, b) -> a));
        // 物料分组
        Map<String, List<MdmMaterialGroupEntity>> materialGroupMap = mdmMaterialGroupService.list(new LambdaQueryWrapper<MdmMaterialGroupEntity>()
                .eq(MdmMaterialGroupEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MdmMaterialGroupEntity::getFactoryCode, FactoryContextHolder.getFactoryCode())
                .eq(MdmMaterialGroupEntity::getEnabled, true))
                .stream()
                .collect(Collectors.groupingBy(MdmMaterialGroupEntity::getGroupName));
        List<MdmMaterialEntity> insertList = new ArrayList<>();
        for (MdmMaterialExcelVO excelVO : excelVOList) {
            Set<String> errorMsg = new HashSet<>();
            // 校验物料编码是否存在
            boolean exsitMaterialCode = materialMap.containsKey(excelVO.getMaterialCode());
            if (exsitMaterialCode) {
                errorMsg.add(excelVO.getMaterialCode() + "物料编码已存在");
            }
            // 校验物料分组是否存在
            List<MdmMaterialGroupEntity> groupMatches = materialGroupMap.get(excelVO.getGroupName());
            String materialGroupCode = groupMatches != null && groupMatches.size() == 1
                    ? groupMatches.get(0).getGroupCode() : null;
            if (StrUtil.isEmpty(materialGroupCode)) {
                errorMsg.add(excelVO.getGroupName() + "物料分组不存在、未启用或名称不唯一");
            } else {
                excelVO.setGroupCode(materialGroupCode);
            }
            // 校验基本单位是否存在
            String materialUnit = materialUnitMap.get(excelVO.getBasicUnit());
            if (StrUtil.isEmpty(materialUnit)) {
                errorMsg.add(excelVO.getBasicUnit() + "单位不存在");
            } else {
                excelVO.setBasicUnit(materialUnit);
            }
            // 校验物料型态是否存在
            String materialType = materialTypeMap.get(excelVO.getMaterialType());
            if (StrUtil.isEmpty(materialType)) {
                errorMsg.add(excelVO.getMaterialType() + "物料型态不存在");
            } else {
                excelVO.setMaterialType(materialType);
            }
            // 校验物料属性是否存在
            String materialAttribute = materialAttributeMap.get(excelVO.getMaterialAttribute());
            if (StrUtil.isEmpty(materialAttribute)) {
                errorMsg.add(excelVO.getMaterialAttribute() + "物料属性不存在");
            } else {
                excelVO.setMaterialAttribute(materialAttribute);
            }

            // 数据合法情况
            if (CollUtil.isEmpty(errorMsg)) {
                MdmMaterialEntity row = insertExcel(excelVO);
                applyMaintenanceScope(row);
                try {
                    validateMaintenanceGroup(row);
                    MdmMaterialMaintenanceValidator.validate(row);
                    insertList.add(row);
                    materialMap.put(excelVO.getMaterialCode(), excelVO.getMaterialCode());
                } catch (CheckedException error) {
                    errorMessageList.add(new ErrorMessage(excelVO.getLineNum() + 1, Set.of(error.getMessage())));
                }
            } else {
                // 数据不合法情况
                errorMessageList.add(new ErrorMessage(excelVO.getLineNum() + 1, errorMsg));
            }
        }
        if (CollUtil.isNotEmpty(errorMessageList)) {
            return R.failed(errorMessageList);
        }
        requireMaterial(!insertList.isEmpty(), "导入内容不能为空");
        requireMaterial(this.saveBatch(insertList), "物料导入未全部保存");
        return R.ok(null, "物料导入成功");
    }

    /**
     * 导出excel 表格
     *
     * @param mdmMaterial 查询条件
     * @param ids         导出指定ID
     * @return excel 文件流
     */
    @Override
    public List<MdmMaterialExcelVO> export(MdmMaterialEntity mdmMaterial, Long[] ids) {
        // 物料属性字典
        Map<String, String> materialAttributeMap = remoteDictService.getDictByType("material_attribute")
                .getData()
                .stream()
                .collect(Collectors.toMap(SysDictItemEntity::getItemValue, SysDictItemEntity::getLabel, (a, b) -> a));
        // 物料型态字典
        Map<String, String> materialTypeMap = remoteDictService.getDictByType("material_type")
                .getData()
                .stream()
                .collect(Collectors.toMap(SysDictItemEntity::getItemValue, SysDictItemEntity::getLabel, (a, b) -> a));
        return this.list(Wrappers.lambdaQuery(mdmMaterial).in(ArrayUtil.isNotEmpty(ids), MdmMaterialEntity::getId, ids))
                .stream()
                .map(material -> {
                    MdmMaterialExcelVO materialExcelVO = new MdmMaterialExcelVO();
                    BeanUtils.copyProperties(material, materialExcelVO);
                    materialExcelVO.setMaterialAttribute(materialAttributeMap.get(material.getMaterialAttribute()));
                    materialExcelVO.setMaterialType(materialTypeMap.get(material.getMaterialType()));
                    return materialExcelVO;
                })
                .collect(Collectors.toList());
    }

    /**
     * 物料打印
     *
     * @param printReq 打印Req
     * @return R 对象
     */
    @Override
    public String print(PrintReq printReq) {
        List<MdmMaterialEntity> materialList = this.listByIds(Arrays.asList(printReq.getIds()));

        // 处理文件URL，从JSON格式中提取url并转换为完整的HTTP访问URL
        materialList.forEach(material -> {
            if (StrUtil.isNotEmpty(material.getFilePath())) {
                try {
                    // 解析JSON格式的文件路径，提取url字段
                    JSONObject filePathJson = JSONUtil.parseObj(material.getFilePath());
                    String fileUrl = filePathJson.getStr("url");
                    if (StrUtil.isNotEmpty(fileUrl)) {
                        String fullFileUrl = fileUrlUtil.buildFileUrl(fileUrl);
                        material.setFilePath(fullFileUrl);
                    }
                } catch (Exception e) {
                    // 如果解析失败，按原逻辑处理
                    String fullFileUrl = fileUrlUtil.buildFileUrl(material.getFilePath());
                    material.setFilePath(fullFileUrl);
                }
            }
        });

        PreviewObj obj = new PreviewObj();
        obj.setTemplateCode(printReq.getTemplateCode());
        String jsonData = JSONUtil.toJsonStr(materialList);
        obj.setJsonData(jsonData);
        return remotePrintService.preview(obj).getData();
    }

    /**
     * 插入excel
     */
    private MdmMaterialEntity insertExcel(MdmMaterialExcelVO excelVO) {
        MdmMaterialEntity material = new MdmMaterialEntity();
        BeanUtils.copyProperties(excelVO, material);
        material.setStatus(true);
        return material;
    }

}
