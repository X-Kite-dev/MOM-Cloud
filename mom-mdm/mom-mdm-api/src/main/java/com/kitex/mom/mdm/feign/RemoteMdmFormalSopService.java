package com.kitex.mom.mdm.feign;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.feign.annotation.InternalCall;
import com.kitex.mom.mdm.Constants;
import com.kitex.mom.mdm.dto.MdmFormalSopDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * MES下达冻结与Agent引用共享的正式SOP固定来源合同。
 */
@FeignClient(contextId = "remoteMdmFormalSopService", value = Constants.Name)
public interface RemoteMdmFormalSopService {
    /**
     * 只查询当前可新下达的正式版本摘要。
     */
    @InternalCall(userTokenRequired = true)
    @PostMapping("/agent/knowledge/sops/search")
    R<Page<MdmFormalSopDTO.Source>> search(@RequestBody MdmFormalSopDTO.SearchQuery query);

    /**
     * 核验精确版本与原字节；普通退役不同于安全撤回。
     */
    @InternalCall(userTokenRequired = true)
    @PostMapping("/agent/knowledge/sops/verify")
    R<MdmFormalSopDTO.Verification> verify(@RequestBody MdmFormalSopDTO.VerifyQuery query);

    /**
     * 新下达读取指定节点的完整正式清单；已有失效绑定明确拒绝。
     */
    @InternalCall(userTokenRequired = true)
    @PostMapping("/agent/knowledge/sops/resolve-for-issue")
    R<MdmFormalSopDTO.Manifest> resolveForIssue(@RequestBody MdmFormalSopDTO.ResolveQuery query);

    /**
     * MES核自身派工阅读权后代理原冻结版字节，仍由MDM核实际工厂和原件hash。
     */
    @InternalCall(userTokenRequired = true)
    @PostMapping("/agent/knowledge/sops/original")
    ResponseEntity<byte[]> original(@RequestBody MdmFormalSopDTO.VerifyQuery query);
}
