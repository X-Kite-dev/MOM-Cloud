package com.kitex.mom.wms.api.feign;

import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.NoToken;
import com.kitex.mom.wms.Constants;
import com.kitex.mom.wms.entity.WmsDeliverGoodsPlanBaseEntity;
import com.kitex.mom.wms.entity.WmsDeliverGoodsRecordEntity;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 查询实时库存
 */
@FeignClient(contextId = "remoteDeliverService", value = Constants.Name)
public interface RemoteDeliverService {

    /**
     * 通过orderCode和明细id查询发货计划
     */
    @NoToken
    @GetMapping("/wmsDeliverGoodsPlanBase/getByOrder")
    R<List<WmsDeliverGoodsPlanBaseEntity>> getByOrder(@RequestParam("orderCode") String orderCode,
                                                      @RequestParam("detailsId") Long detailsId);

    /**
     * 更新发货计划
     */
    @PutMapping("/wmsDeliverGoodsPlanBase/update")
    R<Boolean> update(@RequestBody WmsDeliverGoodsPlanBaseEntity wmsDeliverGoodsPlanBase);


    /**
     * 删除发货计划
     */
    @DeleteMapping("/wmsDeliverGoodsPlanBase")
    R<Boolean> removeById(@RequestBody Long[] ids);

    /**
     * 批量保存发货计划
     */
    @NoToken
    @PostMapping("/wmsDeliverGoodsPlanBase/saveBatchPlanBase")
    R<Boolean> saveBatchPlanBase(@RequestBody List<WmsDeliverGoodsPlanBaseEntity> planBaseList);


    /**
     * 批量更新发货计划
     */
    @NoToken
    @PutMapping("/wmsDeliverGoodsPlanBase/updateBatchPlanBase")
    R<Boolean> updateBatchPlanBase(@RequestBody List<WmsDeliverGoodsPlanBaseEntity> wmsDeliverGoodsPlanBase);

    /**
     * 根据订单号 查询所有 发货记录
     */
    @GetMapping("/wmsDeliverGoodsRecord/getDeliverRecordListByOrder")
    R<List<WmsDeliverGoodsRecordEntity>> getDeliverRecordListByOrder(@RequestParam("orderCode") String orderCode);

    /**
     * 根据发货计划 查询所有 发货记录
     */
    @GetMapping("/wmsDeliverGoodsRecord/getDeliverRecordListByPlan")
    R<List<WmsDeliverGoodsRecordEntity>> getDeliverRecordListByPlan(@RequestParam("planCodeList") List<String> planCodeList);

}
