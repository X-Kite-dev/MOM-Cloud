package com.kitex.mom.mdm.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.vo.ErrorMessage;
import com.kitex.mom.mdm.constant.AvailableStatusEnum;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.entity.MdmProcedureEntity;
import com.kitex.mom.mdm.entity.MdmTimePieceEntity;
import com.kitex.mom.mdm.enums.MatAttributeEnum;
import com.kitex.mom.mdm.enums.MatTypeEnum;
import com.kitex.mom.mdm.enums.TimePieceTypeEnum;
import com.kitex.mom.mdm.mapper.MdmTimePieceMapper;
import com.kitex.mom.mdm.service.MdmMaterialService;
import com.kitex.mom.mdm.service.MdmProcedureService;
import com.kitex.mom.mdm.service.MdmTimePieceService;
import com.kitex.mom.mdm.vo.excel.MdmTimePieceExcelVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.BindingResult;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 计时计件表
 *
 * @author kitex
 * @date 2025-09-03 17:23:53
 */
@Service
@RequiredArgsConstructor
public class MdmTimePieceServiceImpl extends ServiceImpl<MdmTimePieceMapper, MdmTimePieceEntity> implements MdmTimePieceService {

    private final MdmMaterialService mdmMaterialService;

    private final MdmProcedureService mdmProcedureService;

    /**
     * 新增计时计件表
     *
     * @param mdmTimePiece 计时计件表
     * @return R
     */
    @Override
    public Boolean saveTimePiece(MdmTimePieceEntity mdmTimePiece) {
        // 校验产品和工序是否已维护
        checkMatProcedure(mdmTimePiece);
        return this.save(mdmTimePiece);
    }

    /**
     * 修改计时计件表
     *
     * @param mdmTimePiece 计时计件表
     * @return R
     */
    @Override
    public Boolean updateTimePiece(MdmTimePieceEntity mdmTimePiece) {
        // 校验产品和工序是否已维护
        checkMatProcedure(mdmTimePiece);
        return this.updateById(mdmTimePiece);
    }

    /**
     * 导出excel 表格
     *
     * @param mdmTimePiece 查询条件
     * @param ids          导出指定ID
     * @return excel 文件流
     */
    @Override
    public List<MdmTimePieceExcelVO> exportTimePiece(MdmTimePieceEntity mdmTimePiece, Long[] ids) {
        return this.list(Wrappers.lambdaQuery(mdmTimePiece).in(ArrayUtil.isNotEmpty(ids), MdmTimePieceEntity::getId, ids))
                .stream()
                .map(timePiece -> {
                    MdmTimePieceExcelVO mdmTimePieceExcelVO = new MdmTimePieceExcelVO();
                    BeanUtils.copyProperties(timePiece, mdmTimePieceExcelVO);
                    mdmTimePieceExcelVO.setType(TimePieceTypeEnum.getNameByCode(timePiece.getType()));
                    mdmTimePieceExcelVO.setStatus(AvailableStatusEnum.labelOf(timePiece.getStatus()));
                    return mdmTimePieceExcelVO;
                }).collect(Collectors.toList());
    }

    /**
     * 导入计时计件数据
     *
     * @param excelVOList
     * @param bindingResult
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public R importTimePiece(List<MdmTimePieceExcelVO> excelVOList, BindingResult bindingResult) {
        // 通用校验获取失败的数据
        List<ErrorMessage> errorMessageList = (List<ErrorMessage>) bindingResult.getTarget();

        // 获取所有启用自制的产品数据
        Map<String, MdmMaterialEntity> matMap = mdmMaterialService.list(new LambdaQueryWrapper<MdmMaterialEntity>()
                        .eq(MdmMaterialEntity::getStatus, Boolean.TRUE)
                        .eq(MdmMaterialEntity::getMaterialAttribute, MatAttributeEnum.MES.getCode())
                        .eq(MdmMaterialEntity::getMaterialType, MatTypeEnum.PRODUCT.getCode()))
                .stream()
                .collect(Collectors.toMap(MdmMaterialEntity::getMaterialCode, Function.identity(), (a, b) -> a));

        // 获取所有启用的工序数据
        Map<String, String> procedureMap = mdmProcedureService.list(new LambdaQueryWrapper<MdmProcedureEntity>().eq(MdmProcedureEntity::getStatus, Boolean.TRUE))
                .stream()
                .collect(Collectors.toMap(MdmProcedureEntity::getProcessCode, MdmProcedureEntity::getProcessName, (a, b) -> a));

        // 获取所有启用的计时计件数据
        Map<String, MdmTimePieceEntity> timePieceMap = this.list(new LambdaQueryWrapper<MdmTimePieceEntity>().eq(MdmTimePieceEntity::getStatus, Boolean.TRUE))
                .stream()
                .collect(Collectors.toMap(
                        timePiece -> timePiece.getMatCode() + "_" + timePiece.getProcedureCode(),
                        Function.identity()
                ));
        // 构建插入数据
        List<MdmTimePieceEntity> insertList = new ArrayList<>();
        for (MdmTimePieceExcelVO excelVO : excelVOList) {
            Set<String> errorMsg = new HashSet<>();
            if (timePieceMap.containsKey(excelVO.getMatCode() + "_" + excelVO.getProcedureCode())) {
                errorMsg.add("产品和工序已维护");
            }
            MdmMaterialEntity mdmMaterialEntity = matMap.get(excelVO.getMatCode());
            if (mdmMaterialEntity == null) {
                errorMsg.add("产品编码不存在");
            } else {
                excelVO.setMatName(mdmMaterialEntity.getMaterialName());
                excelVO.setMatSpec(mdmMaterialEntity.getSpecificationModel());
            }

            String procedureName = procedureMap.get(excelVO.getProcedureCode());
            if (procedureName == null) {
                errorMsg.add("工序编码不存在");
            } else {
                excelVO.setProcedureName(procedureName);
            }
            if (CollUtil.isEmpty(errorMsg)) {
                MdmTimePieceEntity mdmTimePieceEntity = new MdmTimePieceEntity();
                BeanUtils.copyProperties(excelVO, mdmTimePieceEntity);
                mdmTimePieceEntity.setStatus(Boolean.TRUE);
                mdmTimePieceEntity.setType(TimePieceTypeEnum.getCodeByName(excelVO.getType()));
                insertList.add(mdmTimePieceEntity);
                timePieceMap.put(excelVO.getMatCode() + "_" + excelVO.getProcedureCode(), mdmTimePieceEntity);
            } else {
                // 数据不合法情况
                errorMessageList.add(new ErrorMessage(excelVO.getLineNum() + 1, errorMsg));
            }
        }
        this.saveBatch(insertList);
        if (CollUtil.isNotEmpty(errorMessageList)) {
            return R.failed(errorMessageList);
        }
        return R.ok(null, "计时计件导入成功");
    }

    /**
     * 校验产品和工序是否已维护
     */
    private void checkMatProcedure(MdmTimePieceEntity mdmTimePiece) {
        long count = this.count(new LambdaQueryWrapper<MdmTimePieceEntity>().eq(MdmTimePieceEntity::getMatCode, mdmTimePiece.getMatCode())
                .eq(MdmTimePieceEntity::getProcedureCode, mdmTimePiece.getProcedureCode())
                .eq(MdmTimePieceEntity::getStatus, Boolean.TRUE));
        if (count > 0) {
            throw new CheckedException("该产品和工序已维护");
        }
    }
}