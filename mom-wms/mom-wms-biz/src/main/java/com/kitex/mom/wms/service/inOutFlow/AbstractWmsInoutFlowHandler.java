package com.kitex.mom.wms.service.inOutFlow;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.mom.wms.entity.WmsInoutFlowEntity;
import com.kitex.mom.wms.enums.*;
import com.kitex.mom.wms.mapper.WmsInoutFlowMapper;
import com.kitex.mom.wms.vo.WmsInoutFlowExcelVO;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 出入库流水处理器
 *
 * @author kitex
 * @date 2024-12-18 18:48:20
 */
public abstract class AbstractWmsInoutFlowHandler implements WmsInoutFlowHandler<WmsInoutFlowEntity> {

    @Autowired
    private WmsInoutFlowMapper wmsInoutFlowMapper;

    @Override
    public Page<WmsInoutFlowEntity> page(Page page, LambdaQueryWrapper<WmsInoutFlowEntity> wrapper) {
        return wmsInoutFlowMapper.selectPage(page, wrapper);
    }

    @Override
    public List<WmsInoutFlowEntity> list(LambdaQueryWrapper<WmsInoutFlowEntity> wrapper) {
        return wmsInoutFlowMapper.selectList(wrapper);
    }

    /**
     * 获取导出列表
     */
    @Override
    public List<WmsInoutFlowExcelVO> listExcel(LambdaQueryWrapper<WmsInoutFlowEntity> wrapper) {
        List<WmsInoutFlowEntity> list = list(wrapper);
        List<WmsInoutFlowExcelVO> result = new ArrayList<>();
        Map<String, String> billTypes = Arrays.stream(InBillTypeEnum.values()).collect(Collectors.toMap(InBillTypeEnum::getCode, InBillTypeEnum::getName));
        Map<String, String> outTypes = Arrays.stream(OutBillTypeEnum.values()).collect(Collectors.toMap(OutBillTypeEnum::getCode, OutBillTypeEnum::getDesc));
        Map<String, String> inOtherTypes = Arrays.stream(OtherInBillTypeEnum.values()).collect(Collectors.toMap(OtherInBillTypeEnum::getCode, OtherInBillTypeEnum::getName));
        Map<String, String> outOtherTypes = Arrays.stream(OtherOutBillTypeEnum.values()).collect(Collectors.toMap(OtherOutBillTypeEnum::getCode, OtherOutBillTypeEnum::getDesc));
        billTypes.putAll(outTypes);
        billTypes.putAll(inOtherTypes);
        billTypes.putAll(outOtherTypes);
        for (int i = 0; i < list.size(); i++) {
            WmsInoutFlowEntity inoutFlow = list.get(i);
            WmsInoutFlowExcelVO excelVO = new WmsInoutFlowExcelVO();
            BeanUtil.copyProperties(inoutFlow, excelVO);
            excelVO.setRowNo(i + 1);
            excelVO.setMatNum(inoutFlow.getMatNum() + inoutFlow.getMatUnit());
            InOutEnum inOutEnum = InOutEnum.getByCode(inoutFlow.getOperateType());
            if (inOutEnum != null) {
                excelVO.setOperateType(inOutEnum.getDesc());
            }
            excelVO.setBillType(Optional.ofNullable(billTypes.get(inoutFlow.getBillType())).orElse(inoutFlow.getBillType()));
            result.add(excelVO);
        }
        return result;
    }
}