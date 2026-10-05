package com.company.rag.rag.eval.answer;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 回归重跑报告快照实体（每租户 schema 一张 eval_regression_report，列对齐
 * {@code EvalRegressionReportDdl}）。
 *
 * <p>命名二分（spec 十/十一轮）：本表 {@code avgScore} 是<b>本次重跑</b>三维均分合成的综合得分；
 * 与 {@code LabelledEvalSample.persistedScore}（落库历史 score）语义不同，勿混淆。
 */
@Data
@TableName("eval_regression_report")
public class EvalRegressionReportEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    private LocalDateTime runTime;

    /** 本次重跑使用的判定规则版本（快照列，支撑跨版本趋势归因） */
    private String ruleVersion;

    /** 数据集指纹：md5(排序后 session_row_id 逗号拼接) */
    private String datasetFingerprint;

    private LocalDateTime datasetFrom;

    private LocalDateTime datasetTo;

    private Integer sampleCount;

    /** 重跑 pass 占比 */
    private Double passRate;

    /** 重跑三维均分合成的综合得分 */
    private Double avgScore;

    private Double avgRelevancy;

    private Double avgCorrectness;

    private Double avgFaithfulness;

    /** 重跑 pass 与落库 pass 的一致率 */
    private Double persistedPassAgree;

    private Integer tp;

    private Integer tn;

    private Integer fp;

    private Integer fn;

    private Double accuracy;

    private Double precision;

    private Double recall;

    private Double f1;

    /** 负类召回 = tn/(tn+fp) */
    private Double negativeRecall;

    /** 三维一致率：重跑维度布尔与 humanLabel 正负同向的占比 */
    private Double relevancyAgree;

    private Double correctnessAgree;

    private Double faithfulnessAgree;
}
