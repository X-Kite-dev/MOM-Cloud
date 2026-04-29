package com.kitex.mom.wms.task;

import com.kitex.mom.wms.entity.WmsDeliverGoodsAbnormalRecordEntity;
import com.kitex.mom.wms.entity.WmsDeliverGoodsPlanBaseEntity;
import com.kitex.mom.wms.enums.DeliverStateEnum;
import com.kitex.mom.wms.enums.ShowHiddenStateEnum;
import com.kitex.mom.wms.service.WmsDeliverGoodsAbnormalRecordService;
import com.kitex.mom.wms.service.WmsDeliverGoodsPlanBaseService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;


@Slf4j
@Component
@RequiredArgsConstructor
public class DeliverAbnormalWarnTask {

    private final WmsDeliverGoodsPlanBaseService wmsDeliverGoodsPlanBaseService;
    private final WmsDeliverGoodsAbnormalRecordService wmsDeliverGoodsAbnormalRecordService;


    /**
     * 发货异常处置预警
     * 执行时间间隔：一天一次
     * 执行时间：每日凌晨
     */
    @XxlJob("deliver_abnormal_warn")
    public void deliverAbnormalWarnTask() {
        log.info("开始发货异常预警定时任务 start==>");
        LocalDate now = LocalDate.now();
        List<WmsDeliverGoodsAbnormalRecordEntity> results = new ArrayList<>();
        //todo 防止同一条数据被多次预警,这里应该加一下过滤，以及预警强制完成的或强制关闭的只预警一次
        List<WmsDeliverGoodsPlanBaseEntity> allDeliverPlan = wmsDeliverGoodsPlanBaseService.list();
        //1.强制关闭或者完成的发货计划
        List<WmsDeliverGoodsPlanBaseEntity> close = allDeliverPlan.stream().filter(item -> item.getDeliveredNum().compareTo(item.getDeliverNum()) < 0
                && DeliverStateEnum.CLOSE.getCode().equals(item.getDeliverState())).toList();
        results.addAll(
                close.stream().map(item -> {
                    WmsDeliverGoodsAbnormalRecordEntity record = new WmsDeliverGoodsAbnormalRecordEntity();
                    buildEntity(record, item, "强制关闭");
                    return record;
                }).toList()
        );
        List<WmsDeliverGoodsPlanBaseEntity> complete = allDeliverPlan.stream().filter(item -> item.getDeliveredNum().compareTo(item.getDeliverNum()) < 0
                && DeliverStateEnum.COMPLETE.getCode().equals(item.getDeliverState())).toList();

        results.addAll(
                complete.stream().map(item -> {
                    WmsDeliverGoodsAbnormalRecordEntity record = new WmsDeliverGoodsAbnormalRecordEntity();
                    buildEntity(record, item, "强制完成");
                    return record;
                }).toList()
        );
        //2.到发货时间未完成的发货计划
        List<WmsDeliverGoodsPlanBaseEntity> unComplete = allDeliverPlan.stream().filter(
                item -> item.getPlanDeliverTime().isBefore(now)
                        && (DeliverStateEnum.NOT_START.getCode().equals(item.getDeliverState())
                        || DeliverStateEnum.ING.getCode().equals(item.getDeliverState()))).toList();
        results.addAll(
                unComplete.stream().map(item -> {
                    WmsDeliverGoodsAbnormalRecordEntity record = new WmsDeliverGoodsAbnormalRecordEntity();
                    buildEntity(record, item, "规定计划时间内未完成");
                    return record;
                }).toList()
        );
        wmsDeliverGoodsAbnormalRecordService.saveBatch(results);
        log.info("结束发货异常预警定时任务 ==>end");
    }


    private void buildEntity(WmsDeliverGoodsAbnormalRecordEntity record, WmsDeliverGoodsPlanBaseEntity planBase, String reason) {
        BeanUtils.copyProperties(planBase, record, "id");
        //独有字段
        record.setAbnormalReason(reason);
        record.setState(ShowHiddenStateEnum.CREATE.getCode());
    }


}
