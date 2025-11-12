package com.tencent.supersonic.chat.server.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class NL2SQLParserConfig {
    @Value("${s2.manage.parser.agent.ids:43}")
    private List<Integer> simpleModelAgentIds;


    public List<Integer> getSimpleModelAgentIds() {
        return simpleModelAgentIds;
    }
}
