package com.kitex.mom.mdm.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.vo.ErrorMessage;
import com.kitex.mom.mdm.entity.MdmProcedureEntity;
import com.kitex.mom.mdm.mapper.MdmProcedureMapper;
import com.kitex.mom.mdm.service.MdmProcedureService;
import com.kitex.mom.mdm.vo.excel.MdmWorkingProcedureExcelVO;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.validation.BindingResult;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 工序表
 *
 * @author kitex
 * @date 2024-10-31 17:16:43
 */
@Service
@AllArgsConstructor
public class MdmProcedureServiceImpl extends ServiceImpl<MdmProcedureMapper, MdmProcedureEntity>
        implements MdmProcedureService {


    /**
     * 导入excel 表格
     *
     * @param excelVOList   工序列表
     * @param bindingResult 错误信息列表
     * @return R
     */
    @Override
    public R importWorkingProcedure(List<MdmWorkingProcedureExcelVO> excelVOList, BindingResult bindingResult) {
        // 通用校验获取失败的数据
        List<ErrorMessage> errorMessageList = (List<ErrorMessage>) bindingResult.getTarget();
        // 工序列表
        Map<String, String> workingProcedureMap = this.list()
                .stream()
                .collect(Collectors.toMap(MdmProcedureEntity::getProcessCode,
                        MdmProcedureEntity::getProcessCode, (a, b) -> a));
        // 保存的数据列表
        List<MdmProcedureEntity> insertList = new ArrayList<>();
        for (MdmWorkingProcedureExcelVO excelVO : excelVOList) {
            Set<String> errorMsg = new HashSet<>();

            // 校验工序编码是否存在
            boolean processCode = workingProcedureMap.containsKey(excelVO.getProcessCode());
            if (processCode) {
                errorMsg.add(excelVO.getProcessCode() + "工序编码已存在");
            }

            // 数据合法情况
            if (CollUtil.isEmpty(errorMsg)) {
                insertList.add(insertExcel(excelVO));
                workingProcedureMap.put(excelVO.getProcessCode(), excelVO.getProcessCode());
            } else {
                // 数据不合法情况
                errorMessageList.add(new ErrorMessage(excelVO.getLineNum() + 1, errorMsg));
            }
        }
        this.saveBatch(insertList);
        if (CollUtil.isNotEmpty(errorMessageList)) {
            return R.failed(errorMessageList);
        }
        return R.ok(null, "工序导入成功");
    }

    /**
     * 导出excel 表格
     *
     * @param mdmWorkingProcedure 查询条件
     * @param ids                 导出指定ID
     * @return excel 文件流
     */
    @Override
    public List<MdmWorkingProcedureExcelVO> export(MdmProcedureEntity mdmWorkingProcedure, Long[] ids) {
        return this
                .list(Wrappers.lambdaQuery(mdmWorkingProcedure)
                        .in(ArrayUtil.isNotEmpty(ids), MdmProcedureEntity::getId, ids))
                .stream()
                .map(material -> {
                    MdmWorkingProcedureExcelVO mdmWorkingProcedureExcelVO = new MdmWorkingProcedureExcelVO();
                    BeanUtils.copyProperties(material, mdmWorkingProcedureExcelVO);
                    return mdmWorkingProcedureExcelVO;
                })
                .collect(Collectors.toList());
    }

    /**
     * 插入excel
     */
    private MdmProcedureEntity insertExcel(MdmWorkingProcedureExcelVO excelVO) {
        MdmProcedureEntity material = new MdmProcedureEntity();
        BeanUtils.copyProperties(excelVO, material);
        material.setStatus(Boolean.TRUE);
        return material;
    }

}