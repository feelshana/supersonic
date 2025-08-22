package com.tencent.supersonic.chat.server.rest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.tencent.supersonic.chat.server.service.BiAgentTaskService;
import com.tencent.supersonic.common.bi.BiAgentConfig;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;


@RestController
@RequestMapping({"/private/api/bi/agent"})
public class BiAgentController {

    @Autowired
    private BiAgentTaskService biAgentTaskService;
    
    @PostMapping
    public String createAgent(@RequestBody BiAgentConfig config) throws Exception {
        biAgentTaskService.addBiAgentTask(config);
        return "success";
    }
    
}
