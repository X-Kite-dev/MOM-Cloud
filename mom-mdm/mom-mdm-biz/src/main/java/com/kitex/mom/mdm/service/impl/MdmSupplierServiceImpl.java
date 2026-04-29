package com.kitex.mom.mdm.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.admin.api.entity.SysDictItemEntity;
import com.kitex.admin.api.feign.RemoteDictService;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.vo.ErrorMessage;
import com.kitex.mom.mdm.entity.MdmSupplierClassEntity;
import com.kitex.mom.mdm.entity.MdmSupplierEntity;
import com.kitex.mom.mdm.enums.SupplierCheckStatusEnum;
import com.kitex.mom.mdm.mapper.MdmSupplierMapper;
import com.kitex.mom.mdm.service.MdmSupplierClassService;
import com.kitex.mom.mdm.service.MdmSupplierService;
import com.kitex.mom.mdm.vo.excel.MdmSupplierExcelVO;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.BindingResult;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 供应商
 *
 * @author kitex
 * @date 2024-10-25 14:17:27
 */
@Service
@AllArgsConstructor
public class MdmSupplierServiceImpl extends ServiceImpl<MdmSupplierMapper, MdmSupplierEntity>
        implements MdmSupplierService {

    private final RemoteDictService remoteDictService;

    private final MdmSupplierClassService mdmSupplierClassService;

    /**
     * 导出供应商信息
     *
     * @param mdmSupplier
     * @param ids
     * @return
     */
    @Override
    public List<MdmSupplierExcelVO> export(MdmSupplierEntity mdmSupplier, Long[] ids) {
        // 查询字典
        List<SysDictItemEntity> supplierDict = remoteDictService.getDictByType("supplier_type").getData();
        supplierDict.addAll(remoteDictService.getDictByType("tax_type").getData());
        supplierDict.addAll(remoteDictService.getDictByType("delivery_type").getData());
        Map<String, String> dictMap = supplierDict.stream()
                .collect(Collectors.toMap(SysDictItemEntity::getItemValue, SysDictItemEntity::getLabel));

        return this.list(Wrappers.lambdaQuery(mdmSupplier).in(ArrayUtil.isNotEmpty(ids), MdmSupplierEntity::getId, ids))
                .stream()
                .map(mdmSupplier1 -> {
                    MdmSupplierExcelVO mdmSupplierExcelVO = new MdmSupplierExcelVO();
                    BeanUtil.copyProperties(mdmSupplier1, mdmSupplierExcelVO);
                    mdmSupplierExcelVO.setSupplierType(dictMap.get(mdmSupplier1.getSupplierType()));
                    mdmSupplierExcelVO.setTaxType(dictMap.get(mdmSupplier1.getTaxType()));
                    mdmSupplierExcelVO.setDeliveryMethod(dictMap.get(mdmSupplier1.getDeliveryMethod()));
                    return mdmSupplierExcelVO;
                })
                .collect(Collectors.toList());
    }

    /**
     * 导入excel 表格
     *
     * @param excelVOList   供应商列表
     * @param bindingResult 错误信息列表
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public R importSupplier(List<MdmSupplierExcelVO> excelVOList, BindingResult bindingResult) {
        // 通用校验获取失败的数据
        List<ErrorMessage> errorMessageList = (List<ErrorMessage>) bindingResult.getTarget();

        // 查询字典
        List<SysDictItemEntity> supplierDict = remoteDictService.getDictByType("supplier_type").getData();
        supplierDict.addAll(remoteDictService.getDictByType("tax_type").getData());
        supplierDict.addAll(remoteDictService.getDictByType("delivery_type").getData());
        Map<String, String> dictMap = supplierDict.stream()
                .collect(Collectors.toMap(SysDictItemEntity::getLabel, SysDictItemEntity::getItemValue));

        // 查询供应商分类
        Map<String, String> supplierClassMap = mdmSupplierClassService.list()
                .stream()
                .collect(Collectors.toMap(MdmSupplierClassEntity::getName, MdmSupplierClassEntity::getCode, (a, b) -> a));
        // 获取供应商列表
        Map<String, String> supplierMap = this.list()
                .stream()
                .collect(Collectors.toMap(MdmSupplierEntity::getSupplierCode, MdmSupplierEntity::getSupplierCode,
                        (a, b) -> a));
        List<MdmSupplierEntity> mdmSupplierList = new ArrayList<>();
        for (MdmSupplierExcelVO excelVO : excelVOList) {
            Set<String> errorMsg = new HashSet<>();
            // 校验供应商编码是否存在
            boolean code = supplierMap.containsKey(excelVO.getSupplierCode());
            if (code) {
                errorMsg.add(excelVO.getSupplierCode() + "供应商编码已存在");
            }
            // 校验供应商分类是否存在
            String supplierCode = supplierClassMap.get(excelVO.getSupplierClassName());
            if (StrUtil.isEmpty(supplierCode)) {
                errorMsg.add(excelVO.getSupplierClassName() + "供应商分类不存在");
            } else {
                excelVO.setSupplierClassCode(supplierCode);
            }
            if (StrUtil.isNotBlank(excelVO.getSupplierType())) {
                String supplier = dictMap.get(excelVO.getSupplierType());
                if (supplier == null) {
                    errorMsg.add(excelVO.getSupplierType() + "供应商类型不存在");
                }
                excelVO.setSupplierType(supplier);
            }
            if (StrUtil.isNotBlank(excelVO.getTaxType())) {
                String tax = dictMap.get(excelVO.getTaxType());
                if (tax == null) {
                    errorMsg.add(excelVO.getTaxType() + "税种不存在");
                }
                excelVO.setTaxType(tax);
            }
            if (StrUtil.isNotBlank(excelVO.getDeliveryMethod())) {
                String delivery = dictMap.get(excelVO.getDeliveryMethod());
                if (delivery == null) {
                    errorMsg.add(excelVO.getDeliveryMethod() + "交货方式不存在");
                }
                excelVO.setDeliveryMethod(delivery);
            }
            if (CollUtil.isEmpty(errorMsg)) {
                MdmSupplierEntity mdmSupplierEntity = insertExcel(excelVO);
                mdmSupplierList.add(mdmSupplierEntity);
                supplierMap.put(mdmSupplierEntity.getSupplierCode(), mdmSupplierEntity.getSupplierCode());
            } else {
                // 数据不合法情况
                errorMessageList.add(new ErrorMessage(excelVO.getLineNum() + 1, errorMsg));
            }
        }
        // 批量插入供应商
        this.saveBatch(mdmSupplierList);
        if (CollUtil.isNotEmpty(errorMessageList)) {
            return R.failed(errorMessageList);
        }
        return R.ok(null, "供应商导入成功");
    }

    /**
     * 审核通过/驳回
     *
     * @param mdmSupplierEntity 供应商表
     * @return R
     */
    @Override
    public Boolean check(MdmSupplierEntity mdmSupplierEntity) {
        return this.updateById(mdmSupplierEntity);
    }

    /**
     * 插入excel
     */
    private MdmSupplierEntity insertExcel(MdmSupplierExcelVO excelVO) {
        MdmSupplierEntity supplier = new MdmSupplierEntity();
        BeanUtils.copyProperties(excelVO, supplier);
        supplier.setStatus(true);
        supplier.setCheckStatus(SupplierCheckStatusEnum.NO_CHECK.getCode());
        return supplier;
    }

}