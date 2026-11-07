package com.kitex.mom.qms.service;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.qms.dto.req.QmsBadHandleReq;
import com.kitex.mom.qms.dto.req.QmsBadJudgeReq;
import com.kitex.mom.qms.dto.req.QmsBadVerifyReq;
import com.kitex.mom.qms.entity.QmsBadInfoDetailEntity;
import com.kitex.mom.qms.entity.QmsBadInfoEntity;
import com.kitex.mom.qms.entity.QmsBadOperationEntity;

import java.util.List;
import java.util.Map;

/**
 * 不合格品管理
 *
 * @author kitex
 * @since 2025-01-21
 */
public interface QmsBadInfoService extends IService<QmsBadInfoEntity> {

    /**
     * 分页
     *
     * @param page
     * @param qmsBadInfoEntity
     * @return
     */
    Page<QmsBadInfoEntity> queryPage(Page<QmsBadInfoEntity> page, QmsBadInfoEntity qmsBadInfoEntity);

    /**
     * 判定分页
     *
     * @param page
     * @param qmsBadInfoDetailEntity
     * @return
     */
    Page<QmsBadInfoDetailEntity> queryDetailPage(Page<QmsBadInfoDetailEntity> page, QmsBadInfoDetailEntity qmsBadInfoDetailEntity);

    /**
     * 状态数
     *
     * @param qmsBadInfoDetailEntity
     * @return
     */
    Map<String, Long> detailCount(QmsBadInfoDetailEntity qmsBadInfoDetailEntity);

    /**
     * 查询详情
     *
     * @param id
     * @return
     */
    QmsBadInfoDetailEntity queryDetailById(Long id);

    /**
     * 填报
     *
     * @param qmsBadInfoEntity
     */
    void add(QmsBadInfoEntity qmsBadInfoEntity);

    /**
     * 判定
     *
     * @param req
     */
    void judge(QmsBadJudgeReq req);

    /**
     * 处置
     *
     * @param req
     */
    void handle(QmsBadHandleReq req);

    void verify(QmsBadVerifyReq req);

    void executeMes(com.kitex.mom.qms.dto.req.QmsBadMesExecuteReq req);

    void syncMes(Long detailId);

    void discoverMesReconcile(Long detailId);

    Map<String, Object> syncMesInBackground(com.kitex.mom.qms.entity.QmsMesReceiptReconcileEntity job);

    com.kitex.mom.qms.dto.QmsMesCaseDecision mesDecision(Long detailId);

    Map<String, Object> mesContext(Long detailId);

    /**
     * 沿原NCR和固定WMS请求号恢复初始隔离；特殊退回只使用永久Target，不能重新选同批库存。
     * 专用登记必须在本地注册事务提交后，通过本服务独立代理调用；已成功特殊来源回读保持原号。
     *
     * @param id 当前实际租户工厂的NCR主键
     */
    void retryHold(Long id);

    void retryDisposition(Long detailId);

    List<QmsBadOperationEntity> operations(Long id);

}
