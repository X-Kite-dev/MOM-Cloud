package com.kitex.mom.mdm.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.vo.ErrorMessage;
import com.kitex.mom.mdm.entity.MdmCustomerEntity;
import com.kitex.mom.mdm.entity.MdmCustomerPartEntity;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.mapper.MdmCustomerMapper;
import com.kitex.mom.mdm.mapper.MdmCustomerPartMapper;
import com.kitex.mom.mdm.mapper.MdmMaterialMapper;
import com.kitex.mom.mdm.service.MdmCustomerPartService;
import com.kitex.mom.mdm.vo.excel.MdmCustomerPartExcelVO;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.BindingResult;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 客户零件号
 *
 * @author kitex
 * @date 2024-10-30 14:03:10
 */
@Service
@AllArgsConstructor
public class MdmCustomerPartServiceImpl extends ServiceImpl<MdmCustomerPartMapper, MdmCustomerPartEntity>
        implements MdmCustomerPartService {

    private final MdmMaterialMapper mdmMaterialMapper;

    private final MdmCustomerMapper mdmCustomerMapper;

    /**
     * 导出excel 表格
     *
     * @param mdmCustomerPart 查询条件
     * @param ids             导出指定ID
     * @return excel 文件流
     */
    @Override
    public List<MdmCustomerPartExcelVO> export(MdmCustomerPartEntity mdmCustomerPart, Long[] ids) {
        return this
                .list(Wrappers.lambdaQuery(mdmCustomerPart)
                        .in(ArrayUtil.isNotEmpty(ids), MdmCustomerPartEntity::getId, ids))
                .stream()
                .map(customerPart -> {
                    MdmCustomerPartExcelVO mdmCustomerPartExcelVO = new MdmCustomerPartExcelVO();
                    BeanUtils.copyProperties(customerPart, mdmCustomerPartExcelVO);
                    return mdmCustomerPartExcelVO;
                })
                .collect(Collectors.toList());
    }

    /**
     * 导入excel 表格
     *
     * @param excelVOList   客户零件号列表
     * @param bindingResult 错误信息列表
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public R importCustomerPart(List<MdmCustomerPartExcelVO> excelVOList, BindingResult bindingResult) {
        // 通用校验获取失败的数据
        List<ErrorMessage> errorMessageList = (List<ErrorMessage>) bindingResult.getTarget();

        // 客户列表
        Map<String, String> customerMap = mdmCustomerMapper
                .selectList(new LambdaQueryWrapper<MdmCustomerEntity>().eq(MdmCustomerEntity::getStatus, Boolean.TRUE))
                .stream()
                .collect(Collectors.toMap(MdmCustomerEntity::getCustomerCode, MdmCustomerEntity::getCustomerName,
                        (a, b) -> a));
        // 物料列表
        Map<String, String> materialMap = mdmMaterialMapper
                .selectList(new LambdaQueryWrapper<MdmMaterialEntity>().eq(MdmMaterialEntity::getStatus, Boolean.TRUE))
                .stream()
                .collect(Collectors.toMap(MdmMaterialEntity::getMaterialCode, MdmMaterialEntity::getMaterialName,
                        (a, b) -> a));
        // 获取已存在的客户零件号
        Map<String, String> customerPartMap = this.list()
                .stream()
                .collect(Collectors.toMap(MdmCustomerPartEntity::getPartNumber, MdmCustomerPartEntity::getPartNumber,
                        (a, b) -> a));
        List<MdmCustomerPartEntity> customerPartList = new ArrayList<>();
        for (MdmCustomerPartExcelVO excelVO : excelVOList) {
            Set<String> errorMsg = new HashSet<>();
            // 校验客户编码
            String customerName = customerMap.get(excelVO.getCustomerCode());
            if (customerName == null) {
                errorMsg.add(excelVO.getCustomerCode() + "客户编码不存在");
            } else {
                excelVO.setCustomerName(customerName);
            }
            // 校验物料编码
            String materialName = materialMap.get(excelVO.getMaterialCode());
            if (materialName == null) {
                errorMsg.add(excelVO.getMaterialCode() + "物料编码不存在");
            } else {
                excelVO.setMaterialName(materialName);
            }
            // 数据合法情况
            if (CollUtil.isEmpty(errorMsg)) {
                MdmCustomerPartEntity mdmCustomerPartEntity = insertExcel(excelVO);
                customerPartList.add(mdmCustomerPartEntity);
                customerPartMap.put(mdmCustomerPartEntity.getPartNumber(), mdmCustomerPartEntity.getPartNumber());
            } else {
                // 数据不合法情况
                errorMessageList.add(new ErrorMessage(excelVO.getLineNum() + 1, errorMsg));
            }
        }
        this.saveBatch(customerPartList);
        if (CollUtil.isNotEmpty(errorMessageList)) {
            return R.failed(errorMessageList);
        }
        return R.ok(null, "客户零件号导入成功");
    }

    /**
     * 插入excel
     */
    private MdmCustomerPartEntity insertExcel(MdmCustomerPartExcelVO excelVO) {
        MdmCustomerPartEntity customerPart = new MdmCustomerPartEntity();
        BeanUtils.copyProperties(excelVO, customerPart);
        customerPart.setStatus(Boolean.TRUE);
        return customerPart;
    }

}