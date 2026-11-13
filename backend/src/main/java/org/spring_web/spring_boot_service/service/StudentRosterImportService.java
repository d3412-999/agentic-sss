package org.spring_web.spring_boot_service.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.spring_web.spring_boot_service.common.BizAssert;
import org.spring_web.spring_boot_service.common.BizException;
import org.spring_web.spring_boot_service.common.CsvUtil;
import org.spring_web.spring_boot_service.common.ErrorCode;
import org.spring_web.spring_boot_service.config.SssProperties;
import org.spring_web.spring_boot_service.dto.StudentImportDtos;
import org.spring_web.spring_boot_service.entity.OfferingStudent;
import org.spring_web.spring_boot_service.entity.Selection;
import org.spring_web.spring_boot_service.entity.SysUser;
import org.spring_web.spring_boot_service.mapper.OfferingStudentMapper;
import org.spring_web.spring_boot_service.mapper.SelectionMapper;
import org.spring_web.spring_boot_service.mapper.SysUserMapper;
import org.spring_web.spring_boot_service.service.OfferingStudentService.EnrollStatus;
import org.spring_web.spring_boot_service.service.OfferingStudentService.Enrollment;
import org.spring_web.spring_boot_service.service.StudentRosterParser.ParsedRow;
import org.spring_web.spring_boot_service.service.StudentRosterParser.ParsedSheet;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * 教学班学生名单的批量导入 / 导出。
 *
 * <p><b>两段式交互</b>：先 {@code preview} 把原文解析成逐行状态（新建 / 加入 / 已在本班 / 不合法），
 * 教师确认后再 {@code confirm} 真正写库。预览只读，不产生任何脏数据。</p>
 *
 * <p><b>逐行事务</b>：确认导入时每行一个短事务，单行失败只影响该行并回传原因，
 * 不会因为第 37 行学号重复而把前 36 行一起回滚；500 行也不会撑出长事务超时。</p>
 */
@Service
@RequiredArgsConstructor
public class StudentRosterImportService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final SysUserMapper userMapper;
    private final OfferingStudentMapper rosterMapper;
    private final SelectionMapper selectionMapper;
    private final OfferingService offeringService;
    private final OfferingStudentService studentService;
    private final SssProperties properties;
    private final TransactionTemplate transactionTemplate;

    /* ------------------------------------------------------------------ */
    /* 预览                                                                */
    /* ------------------------------------------------------------------ */

    public StudentImportDtos.PreviewVO previewText(String offeringId, String text) {
        Long oid = ownedOffering(offeringId);
        ParsedSheet sheet = StudentRosterParser.parseText(text, maxRows());
        return preview(oid, sheet, "TEXT");
    }

    public StudentImportDtos.PreviewVO previewFile(String offeringId, MultipartFile file) {
        Long oid = ownedOffering(offeringId);
        BizAssert.param(file != null && !file.isEmpty(), "请选择要导入的文件");
        long limit = properties.getStudent().getImportMaxFileBytes();
        BizAssert.check(file.getSize() <= limit, ErrorCode.FILE_INVALID,
                "文件超过 " + humanSize(limit) + " 上限，请精简后重试");
        ParsedSheet sheet = StudentRosterParser.parseFile(file, maxRows());
        return preview(oid, sheet, "FILE");
    }

    private StudentImportDtos.PreviewVO preview(Long oid, ParsedSheet sheet, String source) {
        List<ParsedRow> parsed = sheet.rows();
        BizAssert.param(!parsed.isEmpty(),
                "没有解析到任何学生数据，请确认每行是「学号 姓名」（支持空格 / 制表符 / 逗号 / 短横线分隔）");
        int maxRows = maxRows();
        BizAssert.param(parsed.size() <= maxRows,
                "单次最多导入 " + maxRows + " 条，本次解析出 " + parsed.size() + " 条，请拆分后重试");

        Map<String, SysUser> existing = loadUsers(parsed.stream()
                .map(row -> row.studentNo() == null ? "" : row.studentNo().strip())
                .filter(no -> !no.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        Set<Long> inRoster = loadRosterIds(oid, existing.values().stream().map(SysUser::getId)
                .collect(Collectors.toSet()));

        Map<String, Integer> firstSeenRow = new HashMap<>();
        List<StudentImportDtos.PreviewRow> rows = new ArrayList<>();
        int importable = 0;
        int skipped = 0;
        int invalid = 0;

        for (ParsedRow row : parsed) {
            String studentNo = strip(row.studentNo());
            String name = strip(row.name());
            String email = strip(row.email());
            StudentImportDtos.RowState state;
            String message;

            if (studentNo.isEmpty()) {
                state = StudentImportDtos.RowState.INVALID;
                message = "学号为空";
            } else if (!StudentRosterParser.isValidStudentNo(studentNo)) {
                state = StudentImportDtos.RowState.INVALID;
                message = "学号格式不合法：只能包含字母、数字、下划线或短横线（2~32 位）";
            } else if (name.isEmpty()) {
                state = StudentImportDtos.RowState.INVALID;
                message = "姓名为空";
            } else if (name.length() > 50) {
                state = StudentImportDtos.RowState.INVALID;
                message = "姓名超过 50 个字符";
            } else if (email.length() > 128) {
                state = StudentImportDtos.RowState.INVALID;
                message = "邮箱超过 128 个字符";
            } else {
                Integer duplicated = firstSeenRow.putIfAbsent(studentNo, row.rowNo());
                if (duplicated != null) {
                    state = StudentImportDtos.RowState.INVALID;
                    message = "与第 " + duplicated + " 行学号重复";
                } else {
                    SysUser user = existing.get(studentNo);
                    if (user == null) {
                        state = StudentImportDtos.RowState.NEW;
                        message = "将创建账号并加入本班";
                    } else if (inRoster.contains(user.getId())) {
                        state = StudentImportDtos.RowState.IN_ROSTER;
                        message = "已在本班，将跳过";
                    } else if (!"STUDENT".equals(user.getRole())) {
                        state = StudentImportDtos.RowState.INVALID;
                        message = "该学号已被教师或管理员账号占用";
                    } else if (user.getName() != null && !user.getName().equals(name)) {
                        state = StudentImportDtos.RowState.LINK;
                        message = "已有账号（系统内姓名：" + user.getName() + "），只加入本班，不覆盖姓名";
                    } else {
                        state = StudentImportDtos.RowState.LINK;
                        message = "已有账号，将直接加入本班";
                    }
                }
            }

            switch (state) {
                case NEW, LINK -> importable++;
                case IN_ROSTER -> skipped++;
                default -> invalid++;
            }
            rows.add(new StudentImportDtos.PreviewRow(row.rowNo(), studentNo, name, email, state, message));
        }

        // 首行既不是表头、也不是合法数据行时，八成是表头写错了（例如「编号A / 名字B」）
        String hint = null;
        if (!sheet.headerDetected() && !rows.isEmpty()
                && rows.get(0).state() == StudentImportDtos.RowState.INVALID) {
            hint = "首行未能识别为表头：表头须为「学号、姓名」，可选「邮箱」；若确实没有表头，请检查第一列是否为学号。";
        }
        return new StudentImportDtos.PreviewVO(rows, rows.size(), importable, skipped, invalid,
                sheet.headerDetected(), hint, source);
    }

    /* ------------------------------------------------------------------ */
    /* 确认导入                                                            */
    /* ------------------------------------------------------------------ */

    public StudentImportDtos.ConfirmVO confirm(String offeringId, StudentImportDtos.ConfirmRequest request) {
        Long oid = ownedOffering(offeringId);
        List<StudentImportDtos.RowInput> rows = request == null ? null : request.rows();
        BizAssert.param(rows != null && !rows.isEmpty(), "没有可导入的数据");
        int maxRows = maxRows();
        BizAssert.param(rows.size() <= maxRows, "单次最多导入 " + maxRows + " 条");

        // 第一遍：纯内存校验（空值 / 格式 / 批内重号），有问题的行直接判定失败，不进事务
        Map<String, Integer> firstSeenRow = new HashMap<>();
        List<StudentImportDtos.ConfirmRow> results = new ArrayList<>();
        List<PreparedRow> prepared = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            StudentImportDtos.RowInput row = rows.get(i);
            int rowNo = row == null || row.rowNo() == null ? i + 1 : row.rowNo();
            String studentNo = row == null ? "" : strip(row.studentNo());
            String name = row == null ? "" : strip(row.name());
            String email = row == null ? "" : strip(row.email());
            if (studentNo.isEmpty() || name.isEmpty()) {
                results.add(new StudentImportDtos.ConfirmRow(rowNo, studentNo, name,
                        StudentImportDtos.RowResult.FAILED, "学号或姓名为空"));
                continue;
            }
            if (!StudentRosterParser.isValidStudentNo(studentNo)) {
                results.add(new StudentImportDtos.ConfirmRow(rowNo, studentNo, name,
                        StudentImportDtos.RowResult.FAILED, "学号格式不合法"));
                continue;
            }
            Integer duplicated = firstSeenRow.putIfAbsent(studentNo, rowNo);
            if (duplicated != null) {
                results.add(new StudentImportDtos.ConfirmRow(rowNo, studentNo, name,
                        StudentImportDtos.RowResult.FAILED, "与第 " + duplicated + " 行学号重复"));
                continue;
            }
            prepared.add(new PreparedRow(rowNo, studentNo, name, email.isEmpty() ? null : email));
        }

        // 初始密码默认「密码 = 学号」，每人不同，无法整批复用一个哈希：
        // 这里先整批算好（并行 + 同密码去重），500 行约 3 秒，避免在事务里逐行 BCrypt 拖到 30 秒
        Map<String, String> passwordHashes = studentService.hashInitialPasswords(
                prepared.stream().map(PreparedRow::studentNo).toList());
        int chunkSize = Math.max(1, properties.getStudent().getImportChunkSize());
        for (int from = 0; from < prepared.size(); from += chunkSize) {
            List<PreparedRow> chunk = prepared.subList(from, Math.min(from + chunkSize, prepared.size()));
            try {
                // 一批一个短事务：500 行只需要几次提交，避免逐行 commit 的 fsync 开销
                List<StudentImportDtos.ConfirmRow> chunkResults =
                        transactionTemplate.execute(status -> enrollChunk(oid, chunk, passwordHashes));
                if (chunkResults != null) {
                    results.addAll(chunkResults);
                }
            } catch (RuntimeException ex) {
                // 整批回退成逐行：一行重复学号不应该让同批其他 199 行一起失败
                results.addAll(enrollRowByRow(oid, chunk, passwordHashes));
            }
        }

        Map<StudentImportDtos.RowResult, Integer> counts = new HashMap<>();
        for (StudentImportDtos.ConfirmRow row : results) {
            counts.merge(row.result(), 1, Integer::sum);
        }
        int created = counts.getOrDefault(StudentImportDtos.RowResult.CREATED, 0);
        int linked = counts.getOrDefault(StudentImportDtos.RowResult.LINKED, 0);
        int skipped = counts.getOrDefault(StudentImportDtos.RowResult.SKIPPED, 0);
        int failed = counts.getOrDefault(StudentImportDtos.RowResult.FAILED, 0);
        results.sort(java.util.Comparator.comparingInt(StudentImportDtos.ConfirmRow::rowNo));
        return new StudentImportDtos.ConfirmVO(rows.size(), created, linked, skipped, failed, results);
    }

    /** 确认导入的中间态：已通过内存校验、待落库的一行。 */
    private record PreparedRow(int rowNo, String studentNo, String name, String email) {
    }

    /** 一个事务内处理一整批；任一行抛异常则整批回滚，由调用方退回逐行模式定位问题行。 */
    private List<StudentImportDtos.ConfirmRow> enrollChunk(
            Long offeringId, List<PreparedRow> chunk, Map<String, String> passwordHashes) {
        List<StudentImportDtos.ConfirmRow> results = new ArrayList<>(chunk.size());
        for (PreparedRow row : chunk) {
            results.add(enrollOne(offeringId, row, passwordHashes.get(row.studentNo())));
        }
        return results;
    }

    /** 逐行事务兜底：把失败精确到行，不让同批的好行陪葬。 */
    private List<StudentImportDtos.ConfirmRow> enrollRowByRow(
            Long offeringId, List<PreparedRow> chunk, Map<String, String> passwordHashes) {
        List<StudentImportDtos.ConfirmRow> results = new ArrayList<>(chunk.size());
        for (PreparedRow row : chunk) {
            String passwordHash = passwordHashes.get(row.studentNo());
            try {
                StudentImportDtos.ConfirmRow single = transactionTemplate.execute(
                        status -> enrollOne(offeringId, row, passwordHash));
                if (single != null) {
                    results.add(single);
                }
            } catch (BizException ex) {
                results.add(new StudentImportDtos.ConfirmRow(row.rowNo(), row.studentNo(), row.name(),
                        StudentImportDtos.RowResult.FAILED, ex.getMessage()));
            } catch (DuplicateKeyException ex) {
                results.add(new StudentImportDtos.ConfirmRow(row.rowNo(), row.studentNo(), row.name(),
                        StudentImportDtos.RowResult.FAILED, "学号已被占用（可能有并发导入），请重试"));
            } catch (RuntimeException ex) {
                results.add(new StudentImportDtos.ConfirmRow(row.rowNo(), row.studentNo(), row.name(),
                        StudentImportDtos.RowResult.FAILED, "导入失败，请稍后重试"));
            }
        }
        return results;
    }

    private StudentImportDtos.ConfirmRow enrollOne(Long offeringId, PreparedRow row, String passwordHash) {
        Enrollment enrollment = studentService.enroll(offeringId, row.studentNo(), row.name(), null,
                row.email(), passwordHash);
        EnrollStatus status = enrollment == null ? EnrollStatus.ALREADY_IN_ROSTER : enrollment.status();
        return switch (status) {
            case CREATED -> new StudentImportDtos.ConfirmRow(row.rowNo(), row.studentNo(), row.name(),
                    StudentImportDtos.RowResult.CREATED, "已创建账号并加入本班");
            case LINKED -> {
                String mismatch = enrollment.existingName() != null && !enrollment.existingName().equals(row.name())
                        ? "（保留系统姓名：" + enrollment.existingName() + "）" : "";
                yield new StudentImportDtos.ConfirmRow(row.rowNo(), row.studentNo(), row.name(),
                        StudentImportDtos.RowResult.LINKED, "已加入本班" + mismatch);
            }
            default -> new StudentImportDtos.ConfirmRow(row.rowNo(), row.studentNo(), row.name(),
                    StudentImportDtos.RowResult.SKIPPED, "已在本班，已跳过");
        };
    }

    /* ------------------------------------------------------------------ */
    /* 样例文件 / 导出 / 批量移除                                          */
    /* ------------------------------------------------------------------ */

    /** 样例 xlsx：表头固定「学号 / 姓名 / 邮箱」，学号列预设为文本格式，避免被 Excel 转成科学计数法。 */
    public byte[] template(String offeringId) {
        ownedOffering(offeringId);
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("学生名单");
            CellStyle textStyle = workbook.createCellStyle();
            textStyle.setDataFormat(workbook.createDataFormat().getFormat("@"));
            CellStyle headStyle = workbook.createCellStyle();
            Font font = workbook.createFont();
            font.setBold(true);
            headStyle.setFont(font);

            Row header = sheet.createRow(0);
            String[] titles = {"学号", "姓名", "邮箱"};
            for (int i = 0; i < titles.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(titles[i]);
                cell.setCellStyle(headStyle);
                sheet.setDefaultColumnStyle(i, textStyle);
                sheet.setColumnWidth(i, (i == 2 ? 28 : 18) * 256);
            }

            String[][] samples = {
                    {"2023010104", "赵六", "zhaoliu@sss.local"},
                    {"2023010105", "钱七", ""},
            };
            for (int r = 0; r < samples.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < samples[r].length; c++) {
                    row.createCell(c).setCellValue(samples[r][c]);
                }
            }

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "样例文件生成失败");
        }
    }

    /** 下载名单：与页面列一致的 CSV，逐单元格过 {@link CsvUtil}（加引号 + 防公式注入）。 */
    public String exportCsv(String offeringId) {
        Long oid = ownedOffering(offeringId);
        List<OfferingStudent> roster = rosterMapper.selectList(new LambdaQueryWrapper<OfferingStudent>()
                .eq(OfferingStudent::getOfferingId, oid)
                .orderByAsc(OfferingStudent::getJoinedAt));
        Map<Long, SysUser> users = roster.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(roster.stream().map(OfferingStudent::getStudentId).toList())
                        .stream().collect(Collectors.toMap(SysUser::getId, Function.identity()));

        Map<Long, Long> selectionCounts = new HashMap<>();
        List<Map<String, Object>> counted = selectionMapper.selectMaps(new QueryWrapper<Selection>()
                .select("student_id AS sid", "COUNT(*) AS cnt")
                .eq("offering_id", oid)
                .ne("status", "CANCELED")
                .groupBy("student_id"));
        for (Map<String, Object> row : counted) {
            Object sid = row.get("sid");
            Object cnt = row.get("cnt");
            if (sid instanceof Number id && cnt instanceof Number count) {
                selectionCounts.put(id.longValue(), count.longValue());
            }
        }

        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append(CsvUtil.field("学号")).append(',')
                .append(CsvUtil.field("姓名")).append(',')
                .append(CsvUtil.field("班级")).append(',')
                .append(CsvUtil.field("状态")).append(',')
                .append(CsvUtil.field("选题数")).append(',')
                .append(CsvUtil.field("最近登录")).append('\n');
        for (OfferingStudent relation : roster) {
            SysUser user = users.get(relation.getStudentId());
            if (user == null) {
                continue;
            }
            csv.append(CsvUtil.field(user.getStudentNo())).append(',')
                    .append(CsvUtil.field(user.getName())).append(',')
                    .append(CsvUtil.field(user.getClassName())).append(',')
                    .append(CsvUtil.field("ACTIVE".equals(relation.getStatus()) ? "在用" : "已停用")).append(',')
                    .append(CsvUtil.field(selectionCounts.getOrDefault(user.getId(), 0L))).append(',')
                    .append(CsvUtil.field(user.getLastLoginAt() == null ? "" : user.getLastLoginAt().format(TIME_FORMAT)))
                    .append('\n');
        }
        return csv.toString();
    }

    /** 批量移除：逐行调用单条移除，任何一行被成绩 / 提交物挡住都单独回传，不影响其他行。 */
    public StudentImportDtos.BatchRemoveVO batchRemove(
            String offeringId, StudentImportDtos.BatchRemoveRequest request) {
        Long oid = ownedOffering(offeringId);
        List<String> ids = request == null ? null : request.studentIds();
        BizAssert.param(ids != null && !ids.isEmpty(), "请先选择要移除的学生");
        BizAssert.param(ids.size() <= maxRows(), "单次最多移除 " + maxRows() + " 位学生");

        List<StudentImportDtos.RemoveRow> rows = new ArrayList<>();
        int removed = 0;
        for (String rawId : ids) {
            String name = "";
            try {
                Long uid = BizAssert.id(rawId, "studentId");
                SysUser user = userMapper.selectById(uid);
                name = user == null ? "" : user.getName();
                studentService.delete(String.valueOf(oid), rawId);
                removed++;
                rows.add(new StudentImportDtos.RemoveRow(rawId, name, true, "已移出本班"));
            } catch (BizException ex) {
                rows.add(new StudentImportDtos.RemoveRow(rawId, name, false, ex.getMessage()));
            }
        }
        return new StudentImportDtos.BatchRemoveVO(ids.size(), removed, ids.size() - removed, rows);
    }

    public StudentImportDtos.ImportPolicyVO policy() {
        return new StudentImportDtos.ImportPolicyVO(
                List.copyOf(properties.getStudent().getImportAllowedExtensions()),
                properties.getStudent().getImportMaxFileBytes(),
                maxRows());
    }

    /* ------------------------------------------------------------------ */
    /* 内部工具                                                            */
    /* ------------------------------------------------------------------ */

    private Long ownedOffering(String offeringId) {
        Long oid = BizAssert.id(offeringId, "offeringId");
        // 归属校验：教师只能操作自己的教学班
        offeringService.ownedOffering(offeringId);
        return oid;
    }

    private int maxRows() {
        return Math.max(1, properties.getStudent().getImportMaxRows());
    }

    private Map<String, SysUser> loadUsers(Set<String> studentNos) {
        if (studentNos.isEmpty()) {
            return Map.of();
        }
        List<SysUser> users = userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .in(SysUser::getStudentNo, studentNos)
                .or()
                .in(SysUser::getUsername, studentNos));
        Map<String, SysUser> byAccount = new LinkedHashMap<>();
        for (SysUser user : users) {
            if (StringUtils.hasText(user.getStudentNo())) {
                byAccount.putIfAbsent(user.getStudentNo(), user);
            }
            byAccount.putIfAbsent(user.getUsername(), user);
        }
        return byAccount;
    }

    private Set<Long> loadRosterIds(Long offeringId, Set<Long> studentIds) {
        if (studentIds.isEmpty()) {
            return Set.of();
        }
        return rosterMapper.selectList(new LambdaQueryWrapper<OfferingStudent>()
                        .eq(OfferingStudent::getOfferingId, offeringId)
                        .in(OfferingStudent::getStudentId, studentIds))
                .stream().map(OfferingStudent::getStudentId).collect(Collectors.toSet());
    }

    private static String strip(String value) {
        return value == null ? "" : value.strip();
    }

    private static String humanSize(long bytes) {
        if (bytes >= 1024 * 1024) {
            return "%.0f MB".formatted(bytes / 1024.0 / 1024.0);
        }
        return "%.0f KB".formatted(bytes / 1024.0);
    }

}
