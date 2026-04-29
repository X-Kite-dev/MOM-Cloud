package com.kitex.mom.pms.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ArrayUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.pms.entity.PmsWarningEntity;
import com.kitex.mom.pms.mapper.PmsWarningMapper;
import com.kitex.mom.pms.service.PmsWarningService;
import com.kitex.mom.pms.vo.PmsWarningExcelVO;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 采购异常表
 *
 * @author kitex
 * @date 2025-01-08 10:29:32
 */
@Service
public class PmsWarningServiceImpl extends ServiceImpl<PmsWarningMapper, PmsWarningEntity> implements PmsWarningService {

    /**
     * 导出excel 表格
     *
     * @param pmsWarning 查询条件
     * @param ids        导出指定ID
     * @return excel 文件流
     */
    @Override
    public List<PmsWarningExcelVO> export(PmsWarningEntity pmsWarning, Long[] ids) {
        return this.list(Wrappers.lambdaQuery(pmsWarning).in(ArrayUtil.isNotEmpty(ids), PmsWarningEntity::getId, ids))
                .stream()
                .map(warning -> {
                    PmsWarningExcelVO pmsWarningExcelVO = new PmsWarningExcelVO();
                    BeanUtil.copyProperties(warning, pmsWarningExcelVO);
                    pmsWarningExcelVO.setStatus(warning.getStatus() ? "已确认" : "未确认");
                    return pmsWarningExcelVO;
                }).collect(Collectors.toList());
    }
}