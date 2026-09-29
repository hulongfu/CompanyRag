package com.company.rag.rag.eval.answer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

/**
 * 钉死 selectDataset 的 SELECT 列顺序与 {@link LabelledEvalSample} record 组件顺序一致。
 *
 * 背景（真库才暴露的雷）：MyBatis 未开启 argNameBasedConstructorAutoMapping，
 * record 的构造器自动映射按「列的位置」匹配，列顺序与 record 组件顺序错位时，
 * 会把 boolean 列喂给 Long 参数，运行期抛「不良的类型值 long : f」。
 * Service 层单测把 Mapper 整个 mock 掉，无法覆盖此雷，故用本测试在编译期之外守住顺序。
 */
class AnswerEvalResultMapperColumnOrderTest {

    @Test
    void selectDataset_columnOrderMatchesRecordComponentOrder() throws Exception {
        Select select = AnswerEvalResultMapper.class
                .getMethod("selectDataset", String.class, Long.class,
                        java.time.LocalDateTime.class, java.time.LocalDateTime.class, int.class)
                .getAnnotation(Select.class);
        assertNotNull(select, "selectDataset 必须有 @Select");

        List<String> columns = parseInnerColumnNames(String.join("\n", select.value()));
        List<String> components = Arrays.stream(RecordComponent[].class.cast(
                        LabelledEvalSample.class.getRecordComponents()))
                .map(RecordComponent::getName)
                .toList();

        assertEquals(components, columns,
                "selectDataset 列顺序必须与 LabelledEvalSample 组件顺序一致（MyBatis 按位置映射 record）");
    }

    /** 取内层 DISTINCT ON 之后、FROM 之前的列清单，别名转 camelCase。 */
    private static List<String> parseInnerColumnNames(String sql) {
        int start = sql.indexOf("DISTINCT ON");
        int from = sql.indexOf("FROM", start);
        assertEquals(true, start > 0 && from > start, "SQL 应包含 DISTINCT ON 子查询");
        String columnPart = sql.substring(sql.indexOf(')', start) + 1, from);

        List<String> names = new ArrayList<>();
        for (String raw : columnPart.split(",")) {
            String col = raw.trim();
            int as = col.toUpperCase().lastIndexOf(" AS ");
            String name = as >= 0 ? col.substring(as + 4).trim() : col.substring(col.lastIndexOf('.') + 1).trim();
            names.add(toCamelCase(name));
        }
        return names;
    }

    private static String toCamelCase(String snake) {
        String[] parts = snake.replace("\"", "").split("_");
        StringBuilder sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].isEmpty()) {
                continue;
            }
            sb.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
        }
        return sb.toString();
    }
}
