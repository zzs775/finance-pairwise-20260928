package exer.model;

import java.util.List;

/** 一个批次成功提交后的结果（不可变）。 */
public record BatchResult(String batchId, List<Obligation> obligations) {
}
