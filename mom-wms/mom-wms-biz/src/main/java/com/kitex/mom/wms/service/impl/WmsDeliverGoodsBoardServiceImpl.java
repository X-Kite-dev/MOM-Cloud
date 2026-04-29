package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.mom.wms.dto.SalesBoardLineDto;
import com.kitex.mom.wms.dto.SalesBoardPieDto;
import com.kitex.mom.wms.dto.SalesLedgerLineDto;
import com.kitex.mom.wms.dto.SalesLedgerPageDto;
import com.kitex.mom.wms.entity.WmsDeliverGoodsPlanBaseEntity;
import com.kitex.mom.wms.entity.WmsDeliverGoodsRecordEntity;
import com.kitex.mom.wms.enums.DeliverStateEnum;
import com.kitex.mom.wms.mapper.WmsDeliverGoodsRecordMapper;
import com.kitex.mom.wms.service.WmsDeliverGoodsBoardService;
import com.kitex.mom.wms.service.WmsDeliverGoodsPlanBaseService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 发货看板报表
 *
 * @author kitex
 */
@Service
@AllArgsConstructor
public class WmsDeliverGoodsBoardServiceImpl implements WmsDeliverGoodsBoardService {

    private final WmsDeliverGoodsPlanBaseService wmsDeliverGoodsPlanBaseService;
    private final WmsDeliverGoodsRecordMapper wmsDeliverGoodsRecordMapper;

    /**
     * 销售出货看板分页查询
     */
    @Override
    public Page<WmsDeliverGoodsPlanBaseEntity> salesBoardPage(Page<WmsDeliverGoodsPlanBaseEntity> toMpPage) {
        LambdaQueryWrapper<WmsDeliverGoodsPlanBaseEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.and(wq ->
                wq.eq(WmsDeliverGoodsPlanBaseEntity::getDeliverState, DeliverStateEnum.NOT_START.getCode())
                        .or()
                        .eq(WmsDeliverGoodsPlanBaseEntity::getDeliverState, DeliverStateEnum.ING.getCode()));
        queryWrapper.orderByAsc(WmsDeliverGoodsPlanBaseEntity::getPlanDeliverTime);
        return wmsDeliverGoodsPlanBaseService.page(toMpPage, queryWrapper);
    }

    /**
     * 销售出货看板饼图
     */
    @Override
    public List<SalesBoardPieDto> salesBoardPie() {
        LocalDate endTime = LocalDate.now();
        LocalDate startTime = endTime.minusDays(6);
        return wmsDeliverGoodsRecordMapper.salesBoardPie(startTime, endTime);
    }

    /**
     * 销售出货看板近30天走势图
     */
    @Override
    public List<SalesBoardLineDto> salesBoardLine() {
        LocalDate endTime = LocalDate.now();
        LocalDate startTime = endTime.minusDays(29);
        List<LocalDate> timeList = getDateList(startTime, endTime);
        //查询发货数据
        List<SalesBoardLineDto> lineDtoList = wmsDeliverGoodsRecordMapper.salesBoardLine(startTime, endTime);
        Map<LocalDate, BigDecimal> deliverMap = lineDtoList.stream().collect(Collectors.toMap(SalesBoardLineDto::getDeliverTime, SalesBoardLineDto::getDeliveredNum));
        //构建返回值
        List<SalesBoardLineDto> results = new ArrayList<>();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("M.dd");
        int index = 1;
        for (LocalDate item : timeList) {
            BigDecimal deliverNum = deliverMap.get(item);
            SalesBoardLineDto dto = new SalesBoardLineDto();
            dto.setDeliverTime(item);
            dto.setTime(item.format(formatter));
            dto.setIndex(index);
            dto.setDeliveredNum(ObjectUtil.isNotEmpty(deliverNum) ? deliverNum : BigDecimal.ZERO);
            results.add(dto);
            index++;
        }
        return results;
    }

    private List<LocalDate> getDateList(LocalDate startTime, LocalDate endTime) {
        List<LocalDate> dateList = new ArrayList<>();
        while (startTime.isBefore(endTime) || startTime.equals(endTime)) {
            dateList.add(startTime);
            startTime = startTime.plusDays(1);
        }
        return dateList;
    }

    /**
     * 销售发货台账分页
     */
    @Override
    public Page<SalesLedgerPageDto> salesLedgerPage(Page<SalesLedgerPageDto> toMpPage, SalesLedgerPageDto query) {
        if (ObjectUtil.isNotEmpty(query.getDateRange())) {
            query.setStartTime(query.getDateRange()[0]);
            query.setEndTime(query.getDateRange()[1]);
        }
        return wmsDeliverGoodsRecordMapper.salesLedgerPage(toMpPage, query);
    }

    /**
     * 销售发货台账 - 折线图
     */
    @Override
    public List<SalesLedgerLineDto> salesLedgerLine() {
        LocalDate now = LocalDate.now();
        LocalDate startTime = now.withMonth(1).withDayOfMonth(1);
        LocalDate endTime = startTime.plusYears(1).minusDays(1);
        List<WmsDeliverGoodsRecordEntity> recordEntityList = wmsDeliverGoodsRecordMapper.selectList(new LambdaQueryWrapper<WmsDeliverGoodsRecordEntity>()
                .ge(WmsDeliverGoodsRecordEntity::getDeliverTime, startTime)
                .le(WmsDeliverGoodsRecordEntity::getDeliverTime, endTime));

        //拿到x轴
        List<String> mouthList = getMouthList(now.getYear());
        //构建数据
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("M月");
        DateTimeFormatter ymdFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        List<SalesLedgerLineDto> results = new ArrayList<>();
        mouthList.forEach(item -> {
            LocalDate parse = LocalDate.parse(item + "-01");
            SalesLedgerLineDto salesLedgerLineDto = new SalesLedgerLineDto();
            salesLedgerLineDto.setTime(parse.format(formatter));
            List<WmsDeliverGoodsRecordEntity> mouthData = recordEntityList.stream()
                    .filter(record -> StrUtil.startWith(record.getDeliverTime().format(ymdFormatter), item)).toList();
            BigDecimal deliveredNum = mouthData.stream().map(WmsDeliverGoodsRecordEntity::getDeliveredNum).reduce(BigDecimal.ZERO, BigDecimal::add);
            salesLedgerLineDto.setDeliveredNum(deliveredNum);
            results.add(salesLedgerLineDto);
        });
        return results;
    }

    private List<String> getMouthList(int currentYear) {
        List<String> mouthList = new ArrayList<>();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM");
        for (int month = 1; month <= 12; month++) {
            YearMonth yearMonth = YearMonth.of(currentYear, month);
            mouthList.add(yearMonth.format(formatter));
        }
        return mouthList;
    }


}