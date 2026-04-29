package com.kitex.mom.wms.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ArrayUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.wms.entity.WmsMaterialWarningEntity;
import com.kitex.mom.wms.mapper.WmsMaterialWarningMapper;
import com.kitex.mom.wms.service.WmsMaterialWarningService;
import com.kitex.mom.wms.vo.WmsMaterialWarningExcelVO;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 物料预警表
 *
 * @author kitex
 * @date 2024-12-23 14:58:53
 */
@Service
public class WmsMaterialWarningServiceImpl extends ServiceImpl<WmsMaterialWarningMapper, WmsMaterialWarningEntity> implements WmsMaterialWarningService {

    /**
     * 导出excel 表格
     *
     * @param wmsMaterialWarning 查询条件
     * @param ids                导出指定ID
     * @return excel 文件流
     */
    @Override
    public List<WmsMaterialWarningExcelVO> export(WmsMaterialWarningEntity wmsMaterialWarning, Long[] ids) {
        return this.list(Wrappers.lambdaQuery(wmsMaterialWarning).in(ArrayUtil.isNotEmpty(ids), WmsMaterialWarningEntity::getId, ids))
                .stream()
                .map(materialWarning -> {
                    WmsMaterialWarningExcelVO wmsMaterialWarningExcelVO = new WmsMaterialWarningExcelVO();
                    BeanUtil.copyProperties(materialWarning, wmsMaterialWarningExcelVO);
                    return wmsMaterialWarningExcelVO;
                })
                .collect(Collectors.toList());
    }
}