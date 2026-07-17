package com.tencent.supersonic.chat.server.rest;

import com.tencent.supersonic.auth.api.authentication.utils.UserHolder;
import com.tencent.supersonic.chat.server.agent.Agent;
import com.tencent.supersonic.chat.server.agent.AgentContextResp;
import com.tencent.supersonic.chat.server.agent.AgentDataSetInfoDTO;
import com.tencent.supersonic.chat.server.agent.AgentToolType;
import com.tencent.supersonic.chat.server.agent.DimensionValueCheckReq;
import com.tencent.supersonic.chat.server.agent.DimensionValueCheckResp;
import com.tencent.supersonic.chat.server.agent.TermDTO;
import com.tencent.supersonic.chat.server.service.AgentService;
import com.tencent.supersonic.chat.server.service.DimensionValueValidationService;
import com.tencent.supersonic.common.config.SystemConfig;
import com.tencent.supersonic.common.pojo.ResultData;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.AuthType;
import com.tencent.supersonic.common.service.SystemConfigService;
import com.tencent.supersonic.common.util.ContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping({"/api/chat/agent", "/openapi/chat/agent"})
public class AgentController {

    @Autowired
    private AgentService agentService;

    @Autowired
    private DimensionValueValidationService dimensionValueValidationService;

    @PostMapping
    public Agent createAgent(@RequestBody Agent agent, HttpServletRequest httpServletRequest,
            HttpServletResponse httpServletResponse) {
        User user = UserHolder.findUser(httpServletRequest, httpServletResponse);
        return agentService.createAgent(agent, user);
    }

    @PutMapping
    public Agent updateAgent(@RequestBody Agent agent, HttpServletRequest httpServletRequest,
            HttpServletResponse httpServletResponse) {
        User user = UserHolder.findUser(httpServletRequest, httpServletResponse);
        return agentService.updateAgent(agent, user);
    }

    @DeleteMapping("/{id}")
    public boolean deleteAgent(@PathVariable("id") Integer id) {
        agentService.deleteAgent(id);
        return true;
    }

    @RequestMapping("/getAgentList")
    public List<Agent> getAgentList(
            @RequestParam(value = "authType", required = false) AuthType authType,
            HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) {
        User user = UserHolder.findUser(httpServletRequest, httpServletResponse);
        return agentService.getAgents(user, authType);
    }

    @GetMapping("/getAgentDetail")
    public Agent getAgentDetail(@RequestParam(value = "agentId", required = false) Integer agentId,
            HttpServletRequest request, HttpServletResponse response) {
        User user = UserHolder.findUser(request, response);
        return agentService.getAgentDetail(agentId, user);
    }

    @GetMapping("/getAgentPrompt")
    public String getAgentPrompt(@RequestParam(value = "agentId", required = false) Integer agentId,
            @RequestParam(value = "queryText", required = false) String queryText,
            HttpServletRequest request, HttpServletResponse response) {
        User user = UserHolder.findUser(request, response);
        return agentService.getAgentPrompt(agentId, queryText, user);
    }

    @GetMapping("/getAgentDataSetInfo")
    public String getAgentDataSetInfo(
            @RequestParam(value = "agentId", required = false) Integer agentId,
            @RequestParam(value = "queryText", required = false) String queryText,
            HttpServletRequest request, HttpServletResponse response) {
        User user = UserHolder.findUser(request, response);
        return agentService.getAgentDataSetInfo(agentId, queryText, user);
    }

    @GetMapping("/getAgentTerms")
    public List<TermDTO> getAgentTerms(@RequestParam(value = "agentId") Integer agentId,
            @RequestParam(value = "termName", required = false) String termName,
            @RequestParam(value = "alias", required = false) String alias,
            HttpServletRequest request, HttpServletResponse response) {
        User user = UserHolder.findUser(request, response);
        return agentService.getAgentTerms(agentId, termName, alias, user);
    }

    @GetMapping("/getAgentContext")
    public AgentContextResp getAgentContext(@RequestParam(value = "agentId") Integer agentId,
            @RequestParam(value = "queryText", required = false) String queryText,
            @RequestParam(value = "termName", required = false) String termName,
            @RequestParam(value = "alias", required = false) String alias,
            HttpServletRequest request, HttpServletResponse response) {
        User user = UserHolder.findUser(request, response);
        return agentService.getAgentContext(agentId, queryText, termName, alias, user);
    }

    @GetMapping("/getRedSeaDataSetInfo")
    public List<AgentDataSetInfoDTO> getRedSeaDataSetInfo(
            @RequestParam("agentIds") List<Integer> agentIds,
            @RequestParam(value = "queryText", required = false) String queryText,
            @RequestParam(value = "queryType", required = false) String queryType,
            HttpServletRequest request, HttpServletResponse response) {
        User user = UserHolder.findUser(request, response);
        return agentService.getRedSeaDataSetInfo(agentIds, queryText, queryType, user);
    }

    @RequestMapping("/getToolTypes")
    public Map<AgentToolType, String> getToolTypes() {
        return AgentToolType.getToolTypes();
    }


    @GetMapping("/hasAgentRight")
    public ResultData hasAgentList(@RequestParam(value = "id", required = true) Integer agentId,
            @RequestParam(value = "userName", required = true) String userName) {
        Agent agent = agentService.getAgent(agentId);
        SystemConfigService sysParameterService = ContextUtils.getBean(SystemConfigService.class);
        SystemConfig systemConfig = sysParameterService.getSystemConfig();
        if (!CollectionUtils.isEmpty(systemConfig.getAdmins())
                && systemConfig.getAdmins().contains(userName)) {
            return ResultData.success(true);
        }
        return ResultData.success(
                agent.getAdmins().contains(userName) || agent.getViewers().contains(userName));

    }

    /**
     * 维度值校验接口 用于校验用户问题中的维度值是否明确，支持三级匹配：预存值、向量库、数据库
     */
    @PostMapping("/validateDimensionValues")
    public DimensionValueCheckResp validateDimensionValues(@RequestBody DimensionValueCheckReq req,
            HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) {
        User user = UserHolder.findUser(httpServletRequest, httpServletResponse);
        return dimensionValueValidationService.validate(req, user);
    }

}
