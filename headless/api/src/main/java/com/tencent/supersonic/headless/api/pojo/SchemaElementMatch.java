package com.tencent.supersonic.headless.api.pojo;

import lombok.*;

import java.io.Serializable;
import java.util.Objects;

@Data
@ToString
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SchemaElementMatch implements Serializable {
    private SchemaElement element;
    private double offset;
    private double similarity;
    private String detectWord;
    private String word;
    private Long frequency;
    private boolean isInherited;
    private boolean llmMatched;

    public boolean isFullMatched() {
        return 1.0 == similarity;
    }


    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        SchemaElementMatch that = (SchemaElementMatch) o;
        return Objects.equals(detectWord, that.detectWord) && Objects.equals(word, that.word);
    }

    @Override
    public int hashCode() {
        return Objects.hash(detectWord, word);
    }
}
