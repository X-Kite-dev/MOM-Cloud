package com.kitex.mom.qms.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.common.query.enums.CommonQuery;
import com.kitex.mom.qms.dto.ReportListRespDTO;
import com.kitex.mom.qms.dto.inspection.InspectionAppendParam;
import com.kitex.mom.qms.dto.inspection.InspectionCheckResultBatchParam;
import com.kitex.mom.qms.dto.inspection.InspectionCheckResultParam;
import com.kitex.mom.qms.dto.inspection.InspectionPurchaseCreateParam;
import com.kitex.mom.qms.dto.req.InspectionPurchaseTaskReq;
import com.kitex.mom.qms.dto.resp.*;
import com.kitex.mom.qms.entity.QmsInspectionPurchaseTaskDetailEntity;
import com.kitex.mom.qms.entity.QmsInspectionPurchaseTaskEntity;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

/**
 * 来料质检
 *
 * @author kitex
 * @date 2022/12/1
 */
public interface InspectionPurchaseTaskService extends IService<QmsInspectionPurchaseTaskEntity> {

    /**
     * app分页查询
     *
     * @param page
     * @param param
     * @param commonQuery
     * @return
     */
    QmsInspectionPurchaseTaskAppVO queryAppPage(Page<QmsInspectionPurchaseTaskEntity> page,
                                                InspectionPurchaseTaskReq param, CommonQuery commonQuery);

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
    Map<String, Long> getStatusCount(InspectionPurchaseTaskReq param);

    /**
     * 创建质检任务
     */
    Long create(InspectionPurchaseCreateParam createParam);

    /**
     * 批量创建
     *
     * @param createParams
     * @return
     */
    Map<String, Long> batchCreate(List<InspectionPurchaseCreateParam> createParams);

    /**
     * 修改质检任务
     *
     * @param updateParam
     */
    void update(InspectionPurchaseCreateParam updateParam);

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

    /**
     * 查询详情
     *
     * @param id
     * @return
     */
    QmsInspectionPurchaseTaskDTO findById(Long id);

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
    QmsInspectionPurchaseTaskEntity getTaskByMatCodeAndSupplierCodeAndBatchNo(String matCode, String supplierCode,
                                                                              String batchNo);

    /**
     * 获取质检项列表
     */
    List<QmsInspectionPurchaseTaskDetailEntity> findDetailsByTaskId(Long taskId);

    /**
     * 获取质检项详情
     */
    QmsInspectionPurchaseTaskDetailEntity findDetailById(Long detailId);

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
    Page<QmsInspectionPurchaseTaskEntity> page(Page<QmsInspectionPurchaseTaskEntity> page, InspectionPurchaseTaskReq param,
                                               CommonQuery commonQuery);


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
     * 导出报表
     *
     * @param taskId
     * @return
     */
    ResponseEntity<byte[]> download(Long taskId, Integer num);

    /**
     * 详情
     *
     * @param id
     * @return
     */
    QmsInspectionPurchaseResultDTO queryById(Long id);


//	MdmMat matInfo(String matCode);

//	void addInNotice(AddInBillNoticeReq inspectionProductDTO, HttpServletRequest request);

}
