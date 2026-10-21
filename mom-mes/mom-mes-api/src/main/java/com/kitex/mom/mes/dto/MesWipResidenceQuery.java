package com.kitex.mom.mes.dto;

import lombok.Data;

/** 在制驻留查询条件；编码均精确匹配，组织范围由服务端当前授权上下文限定。 */
@Data
public class MesWipResidenceQuery {

    /** 物料编码，不把不同物料合并为一个在制数量。 */
    private String matCode;

    /** 原库存物料批次。 */
    private String matBatch;

    /** 原库存条码或流转码。 */
    private String barcode;

    /** 原派工所属生产工单编码。 */
    private String taskCode;

    /** 原派工及实际工序任务身份核实后，按实际任务编码精确筛选。 */
    private String produceTaskCode;

    /** 按MATCHED或LEGACY来源已核实的原冻结工程路线精确筛选，不补造节点。 */
    private String processRouteCode;

    /** 仅筛选节点合同核实为MATCHED的稳定节点，不桥接历史参数节点。 */
    private String sourceRouteNodeCode;

    /** 主工序编码，不表示同一路线中的具体执行节点。 */
    private String routeCode;

    /** 原派工的实际工位，不冒充当前实物位置。 */
    private String worksiteCode;

    /** 默认按执行节点分组；PROCESS为用户明确选择的主工序汇总。 */
    private String groupBy = "NODE";

    /** 精确库存行主键，供来源详情查询使用。 */
    private Long stockId;

    /** 关注类型：ALL、SOURCE_GAP、TIME_UNKNOWN、TIMEOUT、PROMISE_DUE。 */
    private String focus = "ALL";

    /** 当前观察时刻相对入库时刻的最小驻留小时数，非纯排队工时。 */
    private Integer minHours;

    /** 页码，从1开始；统计口径仍为完整筛选集合。 */
    private int current = 1;

    /** 每页行数，服务端限制1至100。 */
    private int size = 50;
}
