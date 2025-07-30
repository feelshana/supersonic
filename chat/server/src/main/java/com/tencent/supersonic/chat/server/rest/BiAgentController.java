package com.tencent.supersonic.chat.server.rest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.tencent.supersonic.chat.server.agent.Agent;
import com.tencent.supersonic.chat.server.service.BiAgentService;
import com.tencent.supersonic.common.bi.BiModelConfig;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;


@RestController
@RequestMapping({"/inner/api/bi/agent"})
public class BiAgentController {

    @Autowired
    private BiAgentService biAgentService;
    
    @PostMapping
    public Agent createAgent(@RequestBody BiModelConfig config) throws Exception {
        return biAgentService.createBiAgent(config);
    }
    
}
