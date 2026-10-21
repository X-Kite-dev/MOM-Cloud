package com.kitex.mom.mdm.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.admin.api.entity.SysDictItemEntity;
import com.kitex.admin.api.feign.RemoteDictService;
import com.kitex.common.core.util.R;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.common.excel.vo.ErrorMessage;
import com.kitex.mom.common.barcode.api.feign.RemotePrintService;
import com.kitex.mom.common.barcode.dto.req.PreviewObj;
import com.kitex.mom.common.barcode.dto.req.PrintReq;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.dto.MdmMaterialMeasureDTO;
import com.kitex.mom.mdm.entity.MdmMaterialGroupEntity;
import com.kitex.mom.mdm.mapper.MdmMaterialMapper;
import com.kitex.mom.mdm.service.MdmMaterialGroupService;
import com.kitex.mom.mdm.service.MdmMaterialService;
import com.kitex.mom.mdm.util.FileUrlUtil;
import com.kitex.mom.mdm.vo.excel.MdmMaterialExcelVO;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
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
        if (rows.size() != 1 || !Boolean.TRUE.equals(rows.get(0).getStatus()) || StrUtil.isBlank(rows.get(0).getBasicUnit())) {
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
        // 物料列表
        Map<String, String> materialMap = this.list()
                .stream()
                .collect(Collectors.toMap(MdmMaterialEntity::getMaterialCode, MdmMaterialEntity::getMaterialCode,
                        (a, b) -> a));
        // 物料分组
        Map<String, String> materialGroupMap = mdmMaterialGroupService.list()
                .stream()
                .collect(Collectors.toMap(MdmMaterialGroupEntity::getGroupName,
                        MdmMaterialGroupEntity::getGroupCode, (a, b) -> a));
        List<MdmMaterialEntity> insertList = new ArrayList<>();
        for (MdmMaterialExcelVO excelVO : excelVOList) {
            Set<String> errorMsg = new HashSet<>();
            // 校验物料编码是否存在
            boolean exsitMaterialCode = materialMap.containsKey(excelVO.getMaterialCode());
            if (exsitMaterialCode) {
                errorMsg.add(excelVO.getMaterialCode() + "物料编码已存在");
            }
            // 校验物料分组是否存在
            String materialGroupCode = materialGroupMap.get(excelVO.getGroupName());
            if (StrUtil.isEmpty(materialGroupCode)) {
                errorMsg.add(excelVO.getGroupCode() + "物料分组不存在");
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
                insertList.add(insertExcel(excelVO));
                materialMap.put(excelVO.getMaterialCode(), excelVO.getMaterialCode());
            } else {
                // 数据不合法情况
                errorMessageList.add(new ErrorMessage(excelVO.getLineNum() + 1, errorMsg));
            }
        }
        this.saveBatch(insertList);
        if (CollUtil.isNotEmpty(errorMessageList)) {
            return R.failed(errorMessageList);
        }
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
