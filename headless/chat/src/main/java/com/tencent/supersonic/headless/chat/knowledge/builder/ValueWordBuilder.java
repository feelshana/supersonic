package com.tencent.supersonic.headless.chat.knowledge.builder;

import com.google.common.collect.Lists;
import com.tencent.supersonic.common.pojo.enums.DictWordType;
import com.tencent.supersonic.headless.api.pojo.SchemaElement;
import com.tencent.supersonic.headless.chat.knowledge.DictWord;
import com.tencent.supersonic.headless.chat.knowledge.KnowledgeBaseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

@Service
@Slf4j
public class ValueWordBuilder extends BaseWordWithAliasBuilder {

    @Override
    public List<DictWord> doGet(String word, SchemaElement schemaElement) {
        List<DictWord> result = Lists.newArrayList();
        if (Objects.nonNull(schemaElement)) {
            result.addAll(getOneWordNatureAlias(schemaElement, false));
        }
        return result;
    }

    public DictWord getOneWordNature(String word, SchemaElement schemaElement, boolean isSuffix) {
        DictWord dictWord = new DictWord();
        Long modelId = schemaElement.getModel();
        String nature = DictWordType.NATURE_SPILT + modelId + DictWordType.NATURE_SPILT
                + schemaElement.getId();
        dictWord.setNatureWithFrequency(String.format("%s " + DEFAULT_FREQUENCY, nature));
        dictWord.setWord(word);
        DictWord name2AliaWord = new DictWord();
        // 加入真实值->别名值到dimValueAliasMap中
        name2AliaWord.setWord(schemaElement.getDimValueMaps().get(0).getValue());
        name2AliaWord.setAlias(word);
        name2AliaWord.setNatureWithFrequency(String.format("%s " + DEFAULT_FREQUENCY, nature));
        KnowledgeBaseService.addDimValueAlias(schemaElement.getId(), Arrays.asList(name2AliaWord));

        return dictWord;
    }
}
