package com.kitex.mom.qms.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.common.query.enums.CommonQuery;
import com.kitex.mom.qms.dto.ReportListRespDTO;
import com.kitex.mom.qms.dto.inspection.InspectionAppendParam;
import com.kitex.mom.qms.dto.inspection.InspectionCheckResultBatchParam;
import com.kitex.mom.qms.dto.inspection.InspectionCheckResultParam;
import com.kitex.mom.qms.dto.inspection.InspectionProductCreateParam;
import com.kitex.mom.qms.dto.req.InspectionProductTaskReq;
import com.kitex.mom.qms.dto.resp.*;
import com.kitex.mom.qms.entity.QmsInspectionProductTaskDetailEntity;
import com.kitex.mom.qms.entity.QmsInspectionProductTaskEntity;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

/**
 * 成品质检
 *
 * @author kitex
 * @date 2022/12/20
 */
public interface InspectionProductTaskService extends IService<QmsInspectionProductTaskEntity> {

    /**
     * 创建质检任务
     */
    void create(InspectionProductCreateParam createParam);

    /**
     * 修改质检任务
     *
     * @param updateParam
     */
    void update(InspectionProductCreateParam updateParam);

    /**
     * 追加质检任务
     */
    void append(InspectionAppendParam appendParam);

    /**
     * 作废质检任务
     */
    void canceled(Long taskId, String reason);

    /**
     * 免检质检任务
     */
    void exemption(Long taskId);

    QmsInspectionProductTaskDTO findById(Long id);

    /**
     * 判断是否已经有同类型任务
     *
     * @param matCode      物料号
     * @param supplierCode 供应商号
     * @param batchNo      批次号
     * @return 是否已经存在
     */
    boolean existTask(String matCode, String supplierCode, String batchNo);

    /**
     * 根据物料/供应商/批次号 查询质检任务, 创建质检时使用
     */
    QmsInspectionProductTaskEntity getTaskByMatCodeAndBatchNo(String matCode, String batchNo);

    /**
     * 获取质检项列表
     */
    List<QmsInspectionProductTaskDetailEntity> findDetailsByTaskId(Long taskId);

    /**
     * 获取质检项详情
     */
    QmsInspectionProductTaskDetailEntity findDetailById(Long detailId);

    /**
     * 暂存质检内容
     */
    void temporarilySave(InspectionCheckResultParam param);

    void batchTemporarilySave(InspectionCheckResultBatchParam param);

    /**
     * 提交质检记录结果
     */
    void submitInspectionResult(Long taskId);


    /**
     * 分页
     */
    Page<QmsInspectionProductTaskEntity> page(Page<QmsInspectionProductTaskEntity> page, InspectionProductTaskReq param,
                                              CommonQuery commonQuery);

    /**
     * app分页查询
     *
     * @param page
     * @param param
     * @param commonQuery
     * @return
     */
    QmsInspectionProductTaskAppVO queryAppPage(Page<QmsInspectionProductTaskEntity> page,
                                               InspectionProductTaskReq param, CommonQuery commonQuery);

    /**
     * 查询状态栏
     *
     * @return
     */
    List<QmsInspectionStatusVO> queryStatusList();

    /**
     * 获取各状态数量统计
     *
     * @param param 查询参数
     * @return 状态统计
     */
    Map<String, Long> getStatusCount(InspectionProductTaskReq param);

    /**
     * 根据任务Id获取质检项报表
     *
     * @param taskId
     * @return
     */
    ReportListRespDTO findReportListByTaskId(Long taskId);

    /**
     * 处置完成
     *
     * @param dto
     */
    void handleFinish(HandleFinishDTO dto);

    /**
     * 返工返修
     *
     * @param id
     */
    void handleRework(Long id);

    /**
     * 详情
     *
     * @param id
     * @return
     */
    QmsInspectionProductResultDTO queryById(Long id);

    /**
     * 导出报表
     *
     * @param taskId
     * @return
     */
    ResponseEntity<byte[]> download(Long taskId, Integer num);


}
