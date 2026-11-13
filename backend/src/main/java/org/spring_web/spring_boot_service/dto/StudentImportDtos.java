package org.spring_web.spring_boot_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 教学班学生名单的批量导入契约。
 *
 * <p>解析与校验全部在后端完成（文本粘贴 / Excel / Word / CSV 共用同一套规则），
 * 前端只负责展示预览结果，避免「前端正则一套、后端正则另一套」的漂移。</p>
 */
public final class StudentImportDtos {

    private StudentImportDtos() {
    }

    /** 预览行的状态码：NEW 将建号、LINK 只建关联、IN_ROSTER 已在本班、INVALID 不可导入。 */
    public enum RowState {
        NEW, LINK, IN_ROSTER, INVALID
    }

    /** 确认导入的单行结果：CREATED 新建账号、LINKED 复用账号、SKIPPED 已在本班、FAILED 失败。 */
    public enum RowResult {
        CREATED, LINKED, SKIPPED, FAILED
    }

    public record TextParseRequest(
            @NotBlank(message = "请粘贴学生数据") @Size(max = 200_000, message = "粘贴内容过长，请分批导入")
            String text) {
    }

    /**
     * 确认导入的单行入参。
     *
     * <p>{@code rowNo} 只是回显用的原始行号；服务端会重新校验学号 / 姓名 / 重复，
     * 不信任前端预览结果。</p>
     */
    public record RowInput(String studentNo, String name, String email, Integer rowNo) {
    }

    public record ConfirmRequest(
            @NotEmpty(message = "没有可导入的数据")
            @Size(max = 2000, message = "单次提交的行数超出上限")
            List<RowInput> rows) {
    }

    public record PreviewRow(
            int rowNo,
            String studentNo,
            String name,
            String email,
            RowState state,
            String message) {
    }

    /** {@code hint} 是整批级别的提示（例如首行未能识别为表头），没有则为 null。 */
    public record PreviewVO(
            List<PreviewRow> rows,
            int total,
            int importable,
            int skipped,
            int invalid,
            boolean headerDetected,
            String hint,
            String source) {
    }

    public record ConfirmRow(
            int rowNo,
            String studentNo,
            String name,
            RowResult result,
            String message) {
    }

    public record ConfirmVO(
            int total,
            int created,
            int linked,
            int skipped,
            int failed,
            List<ConfirmRow> rows) {
    }

    /** 导入限额：前端 accept、单文件大小提示与后端校验共用同一份配置。 */
    public record ImportPolicyVO(List<String> allowedExtensions, long maxFileBytes, int maxRows) {
    }

    public record BatchRemoveRequest(
            @NotEmpty(message = "请先选择要移除的学生") List<String> studentIds) {
    }

    public record RemoveRow(String studentId, String name, boolean removed, String message) {
    }

    public record BatchRemoveVO(int total, int removed, int failed, List<RemoveRow> rows) {
    }
}
