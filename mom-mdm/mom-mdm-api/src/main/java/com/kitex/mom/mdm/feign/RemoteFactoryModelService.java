package com.kitex.mom.mdm.feign;

import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.Constants;
import com.kitex.mom.mdm.vo.MdmFactoryModelTreeVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 工厂建模远程调用接口
 *
 * @author claude
 * @date 2025-07-30
 */
@FeignClient(value = Constants.Name, contextId = "remoteFactoryModelService")
public interface RemoteFactoryModelService {

    /**
     * 获取工厂建模树形结构
     */
    @GetMapping("/mdm/factory-model/tree")
    R<List<MdmFactoryModelTreeVO>> getFactoryModelTree(@RequestParam(required = false) String factoryCode);

    /**
     * 根据父节点ID获取子节点列表
     */
    @GetMapping("/mdm/factory-model/children")
    R<List<MdmFactoryModelTreeVO>> getChildrenByParentId(@RequestParam(required = false) Long parentId,
                                                         @RequestParam(required = false) String factoryCode);

    /**
     * 根据级别获取节点列表
     */
    @GetMapping("/mdm/factory-model/by-level")
    R<List<MdmFactoryModelTreeVO>> getNodesByLevel(@RequestParam Integer level,
                                                   @RequestParam(required = false) String factoryCode);

    /**
     * 根据类型获取节点列表
     */
    @GetMapping("/mdm/factory-model/by-type")
    R<List<MdmFactoryModelTreeVO>> getNodesByType(@RequestParam String type,
                                                  @RequestParam(required = false) String factoryCode);

    /**
     * 获取节点的完整路径
     */
    @GetMapping("/mdm/factory-model/{id}/path")
    R<String> getNodePath(@PathVariable Long id);

    /**
     * 根据路径查找节点
     */
    @GetMapping("/mdm/factory-model/find-by-path")
    R<Long> getNodeByPath(@RequestParam String path,
                          @RequestParam(required = false) String factoryCode);

    /**
     * 验证节点编码是否重复
     */
    @GetMapping("/mdm/factory-model/validate-code")
    R<Boolean> validateNodeCode(@RequestParam String code,
                                @RequestParam(required = false) String factoryCode,
                                @RequestParam(required = false) Long excludeId);
}