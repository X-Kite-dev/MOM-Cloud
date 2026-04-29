package com.kitex.mom.mdm.service.impl;

import cn.hutool.core.util.ArrayUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.mdm.entity.MdmMatUnitConversionEntity;
import com.kitex.mom.mdm.mapper.MdmMatUnitConversionMapper;
import com.kitex.mom.mdm.service.MdmMatUnitConversionService;
import com.kitex.mom.mdm.vo.excel.MdmMatUnitConversionExcelVO;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 物料单位
 *
 * @author kitex
 * @date 2024-12-12 10:59:24
 */
@Service
@AllArgsConstructor
public class MdmMatUnitConversionServiceImpl extends ServiceImpl<MdmMatUnitConversionMapper, MdmMatUnitConversionEntity> implements MdmMatUnitConversionService {

    /**
     * 导出excel 表格
     *
     * @param mdmMatUnit 查询条件
     * @param ids        导出指定ID
     * @return excel 文件流
     */
    @Override
    public List<MdmMatUnitConversionExcelVO> exportExcel(MdmMatUnitConversionEntity mdmMatUnit, Long[] ids) {
        return this.list(Wrappers.lambdaQuery(mdmMatUnit).in(ArrayUtil.isNotEmpty(ids), MdmMatUnitConversionEntity::getId, ids))
                .stream()
                .map(matUnit -> {
                    MdmMatUnitConversionExcelVO mdmMatUnitConversionExcelVO = new MdmMatUnitConversionExcelVO();
                    BeanUtils.copyProperties(matUnit, mdmMatUnitConversionExcelVO);
                    return mdmMatUnitConversionExcelVO;
                }).collect(Collectors.toList());
    }
}