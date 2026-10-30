package com.kitex.mom.mdm.feign;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.Constants;
import com.kitex.mom.mdm.dto.MdmProcessRouteDTO;
import com.kitex.mom.mdm.dto.MdmRouteMaterialAssignmentQueryDTO;
import com.kitex.mom.mdm.entity.MdmProcessProcedureMatEntity;
import com.kitex.mom.mdm.entity.MdmProcessRouteEntity;
import com.kitex.mom.mdm.entity.MdmProcessRouteWorkingEntity;
import com.kitex.mom.mdm.vo.MdmProcedureSopVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.SpringQueryMap;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@FeignClient(contextId = "remoteProcessRouteService", value = Constants.Name)
public interface RemoteProcessRouteService {

    /**
     * 分页查询工艺路线信息
     *
     * @param current         当前页码
     * @param size            每页记录数
     * @param mdmProcessRoute 工艺路线实体
     * @return R
     */
    @GetMapping("/mdmProcessRoute/page")
    R<Page<MdmProcessRouteEntity>> getMdmProcessRoutePage(@RequestParam("current") Integer current,
                                                          @RequestParam("size") Integer size, @SpringQueryMap MdmProcessRouteEntity mdmProcessRoute);

    /**
     * 获取工艺路线列表
     *
     * @return R
     */
    @GetMapping("/mdmProcessRoute/list")
    R<List<MdmProcessRouteEntity>> getList();

    /**
     * 新增工艺路线信息
     *
     * @param mdmProcessRoute 工艺路线实体
     * @return R
     */
    @PostMapping("/mdmProcessRoute")
    R<Boolean> save(@RequestBody MdmProcessRouteEntity mdmProcessRoute);

    /**
     * 更新工艺路线信息
     *
     * @param mdmProcessRoute 工艺路线实体
     * @return R
     */
    @PutMapping("/mdmProcessRoute")
    R<Boolean> updateById(@RequestBody MdmProcessRouteEntity mdmProcessRoute);

    /**
     * 删除工艺路线信息
     *
     * @param ids 工艺路线 ID数组
     * @return R
     */
    @DeleteMapping("/mdmProcessRoute")
    R<Boolean> removeById(@RequestBody Long[] ids);

    /**
     * 查询工艺路线详情
     *
     * @param id 工艺路线 ID
     * @return R
     */
    @GetMapping("/mdmProcessRoute/{id}")
    R<MdmProcessRouteEntity> getDetails(@PathVariable Long id);


    /**
     * 根据工艺路线编码、工序编码、获取SOP
     *
     * @param routeCode   工艺路线编码
     * @param processCode 工序编码
     * @return
     */
    @GetMapping("/mdmProcessRoute/getSopByCode")
    R<List<MdmProcedureSopVO>> getSopByCode(@RequestParam("routeCode") String routeCode, @RequestParam("processCode") String processCode);

    /**
     * 根据工艺路线编码、工序编码、获取投料
     *
     * @param routeCode
     * @param processCode
     * @return
     */
    @GetMapping("/mdmProcessRoute/getMatByCode")
    R<List<MdmProcessProcedureMatEntity>> getMatByCode(@RequestParam("routeCode") String routeCode, @RequestParam("processCode") String processCode);

    @PostMapping("/mdmProcessRoute/materialAssignments")
    R<List<MdmProcessProcedureMatEntity>> getMaterialAssignments(@RequestBody MdmRouteMaterialAssignmentQueryDTO query);

    @GetMapping("/mdmProcessRoute/getProcessWorkingById")
    R<MdmProcessRouteWorkingEntity> getProcessWorkingById(@RequestParam("id") Long id);

    /**
     * 读取当前组织路线的真实节点清单，供旧按主工序维护的消费者先核定唯一归属。
     */
    @GetMapping("/mdmProcessRoute/getProcedureList")
    R<List<MdmProcessRouteWorkingEntity>> getProcedureList(@RequestParam("routeCode") String routeCode);

    @GetMapping("/mdmProcessRoute/getDeviceList")
    R<List<String>> getDeviceList(@RequestParam("id") Long id);

    /**
     * 根据产品编码查询默认启用工艺路线
     */
    @GetMapping("/mdmProcessRoute/getDefaultByProductCode")
    R<MdmProcessRouteEntity> getDefaultByProductCode(@RequestParam("productCode") String productCode);

    /**
     * 批量查询启用的默认工艺路线；保留重复行，由调用方识别主数据冲突。
     */
    @PostMapping("/mdmProcessRoute/defaultsByProductCodes")
    R<List<MdmProcessRouteEntity>> getDefaultsByProductCodes(@RequestBody List<String> productCodes);

    /**
     * 批量查询产品全部启用工艺路线。每条启用路线代表该产品已经维护并批准使用的
     * 产线/速率关系，供MPS粗能力平衡显式选择合格替代产线。
     */
    @PostMapping("/mdmProcessRoute/enabledByProductCodes")
    R<List<MdmProcessRouteEntity>> getEnabledByProductCodes(@RequestBody List<String> productCodes);

    /**
     * 批量获取工艺路线、工序和启用工位能力，用于APS生成工序级资源占用。
     */
    @PostMapping("/mdmProcessRoute/mdmProcessRouteByIds")
    R<List<MdmProcessRouteDTO>> getDetailByIds(@RequestBody List<Long> ids);
}
