package com.kitex.mom.common.flow.handler;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.mom.common.flow.entity.FlowInstanceBizRecordEntity;
import com.kitex.mom.common.flow.service.FlowInstanceBizRecordService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestTemplate;

/**
 * 入库单处理器抽象实现
 * 提供通用的业务逻辑实现，子类可以通过继承该类来实现特定类型的入库单处理
 *
 * @author kitex
 * @since 2024-12-16 14:28:29
 */

public abstract class AbstractFlowHandler implements FlowHandler {

    @Autowired
    private RestTemplate restTemplateAdapter;

    @Autowired
    private FlowInstanceBizRecordService flowInstanceBizRecordService;

    /**
     * 流程结束保存数据
     *
     * @param startFlowData
     */
    @Override
    public Boolean backProcessCompleteSaveData(FlowInstanceBizRecordEntity startFlowData) {
        // 用流程实例id查找数据库
        FlowInstanceBizRecordEntity flowBizRecord = flowInstanceBizRecordService.getOne(new LambdaQueryWrapper<FlowInstanceBizRecordEntity>()
                        .eq(FlowInstanceBizRecordEntity::getInstanceId, startFlowData.getInstanceId())
                        .eq(FlowInstanceBizRecordEntity::getFlowType, startFlowData.getFlowType())
                , false);
        // 取得业务单据id、回调地址
        String bizService = flowBizRecord.getBizService();
        String backUrl = flowBizRecord.getBackUrl();
        // 保存审批结果、结束类型
        flowInstanceBizRecordService.update(new LambdaUpdateWrapper<FlowInstanceBizRecordEntity>()
                .set(FlowInstanceBizRecordEntity::getResult, startFlowData.getResult())
                .set(FlowInstanceBizRecordEntity::getEndType, startFlowData.getEndType())
                .eq(BaseEntity::getId, flowBizRecord.getId())
        );
        // 通过回调地址请求业务模块接口
        BeanUtil.copyProperties(startFlowData, flowBizRecord, CopyOptions.create().ignoreNullValue());
        String url = "http://" + bizService + backUrl;
        HttpEntity<FlowInstanceBizRecordEntity> httpEntity = new HttpEntity<>(flowBizRecord, new HttpHeaders());
        R aBoolean = restTemplateAdapter.postForObject(url, httpEntity, R.class);
        if (aBoolean == null) {
            throw new CheckedException(String.format("业务模块【%s】接口【%s】返回结果为空", flowBizRecord.getBizService(), flowBizRecord.getBackUrl()));
        }
        if (!aBoolean.isOk()) {
            throw new CheckedException(String.format("业务模块【%s】接口【%s】返回错误：%s", flowBizRecord.getBizService(), flowBizRecord.getBackUrl(), aBoolean.getMsg()));
        }
        return Boolean.TRUE;
    }
}
