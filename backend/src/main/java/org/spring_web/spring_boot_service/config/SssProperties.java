package org.spring_web.spring_boot_service.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 系统自有配置项，全部带安全默认值，开箱即用。
 *
 * <p>仓库的 {@code application.yaml} 已给出本地默认值；如需覆盖，在部署配置里加 {@code sss.*} 即可，例如：</p>
 * <pre>
 * sss:
 *   ai:
 *     base-url: http://127.0.0.1:8000
 *     internal-token: your-strong-token
 *   storage:
 *     local-dir: ./data/objects
 *   security:
 *     trusted-proxies: 127.0.0.1,10.0.0.0/8
 *   cors:
 *     allowed-origins: http://localhost:5173
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "sss")
public class SssProperties {

    private Ai ai = new Ai();
    private Storage storage = new Storage();
    private Limits limits = new Limits();
    private Cors cors = new Cors();
    private Security security = new Security();
    private Admin admin = new Admin();
    private Student student = new Student();
    private Upload upload = new Upload();
    private Defense defense = new Defense();

    /** Python AI 服务（FastAPI）连接参数。 */
    @Data
    public static class Ai {
        private String baseUrl = "http://127.0.0.1:8000";
        private String internalToken = "change-me";
        private Duration connectTimeout = Duration.ofSeconds(3);
        private Duration readTimeout = Duration.ofSeconds(90);

        /** 多文件评审的分片参数：先逐份摘要，再汇总出总分。 */
        private Review review = new Review();
    }

    /**
     * 多文件评审（map-reduce）参数。
     *
     * <p>以前是把 9 份代码拼成一段、超 6 万字符直接截断，靠后的包只进统计不进正文。
     * 现在改成「每份单独评一次 → 再汇总」，每个文件都有独立结论，
     * 代价是 N+1 次模型调用，所以要能控并发与单份预算。</p>
     */
    @Data
    public static class Review {
        /** 分文件评审的并发数。太高会撞 AI 服务或模型限流。 */
        private int maxConcurrent = 3;

        /** 单份文件送审的字符上限；超了就截断这一份并标注，不影响其它份。 */
        private int perFileChars = 30_000;

        /** 是否开启分文件评审。关掉则退回「合并成一次调用」。 */
        private boolean perFileEnabled = true;

        /** 单份评审的等待上限（秒）。AI 客户端自身也有 read-timeout 兜底。 */
        private int perFileTimeoutSeconds = 120;

        /**
         * 看门狗：状态停在 PENDING / RUNNING 超过这个分钟数就判 FAILED。
         *
         * <p>没有它，「任务线程被重启 / OOM 杀掉」会让记录永远停在 RUNNING，
         * 前端就只能一直转圈——这正是报告页卡住的根因之一。</p>
         */
        private int staleMinutes = 15;

        /** 看门狗是否启用（关掉便于本地调试「永远 RUNNING」的分支）。 */
        private boolean watchdogEnabled = true;

        /** 看门狗扫描间隔（秒）。 */
        private int watchdogIntervalSeconds = 120;
    }

    /** 本地文件存储参数（MinIO / OSS 预留，见 storage 字段）。 */
    @Data
    public static class Storage {
        private String localDir = "./data/objects";
        // 单文件大小上限不在这里：提交物上限见 Upload.Submission，题目附件见 Upload.TopicAttachment
    }

    /** 限流与幂等参数。 */
    @Data
    public static class Limits {
        private int loginAttemptsPerMinute = 5;
        private int idempotencyTtlHours = 24;
        private int selectionBatchMax = 10;
        private int gradeAdjustReasonMinLength = 5;
    }

    /**
     * 跨域白名单：开发期放行本机任意端口（用 localhost 或 127.0.0.1 打开都不会 403）。
     * 生产部署必须显式改成固定的前端域名，例如 {@code https://sss.example.com}。
     *
     * <p>这里是 Spring 的 origin <b>pattern</b>：端口写 {@code [*]} 表示任意端口，
     * 写 {@code [8080,8081]} 表示只允许这两个端口。</p>
     */
    @Data
    public static class Cors {
        private String[] allowedOrigins = {"http://localhost:[*]", "http://127.0.0.1:[*]"};
    }

    /**
     * 安全相关：可信反向代理白名单。
     *
     * <p>默认空 = 永不信任 {@code X-Forwarded-For}，客户端无法伪造来源 IP；只有部署在
     * Nginx / 网关后面时才填写代理自身的 IP 或 IPv4 CIDR（如 {@code 10.0.0.0/8}）。</p>
     */
    @Data
    public static class Security {
        private List<String> trustedProxies = new ArrayList<>();
    }

    /**
     * 管理员端配置。
     *
     * <p>教师账号由管理员创建，统一使用默认初始密码并强制首次登录修改；
     * 部署时可通过 {@code TEACHER_DEFAULT_PASSWORD} 覆盖，避免继续使用弱口令。</p>
     */
    @Data
    public static class Admin {
        private String defaultTeacherPassword = "123456";
        private int maxBatchSize = 100;
    }

    /**
     * 学生账号与名单导入配置。
     *
     * <p>学生账号由教师在教学班里批量导入，不再逐个生成「一次性初始密码」：
     * 建号时按 {@code defaultPasswordMode} 决定初始密码（默认「密码即学号」），
     * 并置 {@code must_change_password=1}，首次登录由前端强制改密。</p>
     *
     * <p>{@code FIXED} 模式回退到 {@code defaultPassword}（默认与教师初始密码一致），
     * 适合演示环境；{@code STUDENT_NO} 是本项目默认值，符合校园「初始密码=学号」的惯例。</p>
     *
     * <p>导入限额同样只在后端定义一次，前端通过 {@code GET /meta/student-import-policy} 取同一份值。</p>
     */
    @Data
    public static class Student {

        /** 初始密码策略：{@code STUDENT_NO}（密码=学号）或 {@code FIXED}（统一默认密码）。 */
        private PasswordMode defaultPasswordMode = PasswordMode.STUDENT_NO;

        /** {@code FIXED} 模式下新建学生账号的统一密码；前端永不展示，只提示「首次登录需修改」。 */
        private String defaultPassword = "123456";

        /** 单个导入文件的大小上限（字节）。 */
        private long importMaxFileBytes = 2L * 1024 * 1024;
        /** 单次导入的学生条数上限（文本粘贴与文件导入共用）。 */
        private int importMaxRows = 500;
        /** 允许导入的文件扩展名；.doc 会明确提示「另存为 .docx / .xlsx」。 */
        private List<String> importAllowedExtensions =
                new ArrayList<>(List.of("xlsx", "xls", "docx", "csv"));
        /** 逐行事务的提交粒度（条），避免 500 行挤在一个长事务里。 */
        private int importChunkSize = 200;
    }

    /** 学生初始密码策略。 */
    public enum PasswordMode {
        /** 初始密码 = 学号（默认）。 */
        STUDENT_NO,
        /** 所有新账号共用 {@code sss.student.default-password}。 */
        FIXED
    }

    /**
     * 上传策略。白名单与限额只在这里定义一次，前端通过 {@code GET /meta/upload-policy} 取同一份值，
     * 避免「前端 accept 写一套、后端校验写另一套」的经典漂移。
     */
    @Data
    public static class Upload {
        private TopicAttachment topicAttachment = new TopicAttachment();
        private Submission submission = new Submission();
    }

    /**
     * 提交物（源码 / 报告）的限额。两类互相独立：源码调上限不影响报告，
     * 将来只放宽报告也不必动源码的校验分支。
     */
    @Data
    public static class Submission {
        private SubmissionType source = SubmissionType.source();
        private SubmissionType report = SubmissionType.report();
        private Similarity similarity = new Similarity();
    }

    /** 单类提交物的允许扩展名与数量/大小上限。 */
    @Data
    public static class SubmissionType {
        /** 允许的扩展名（小写、不带点）。 */
        private List<String> allowedExtensions = new ArrayList<>();
        /** 单次提交该类文件数上限（含 1，即至少 1 份）。 */
        private int maxFiles = 9;
        /** 单个文件字节上限。 */
        private long maxFileBytes = 100L * 1024 * 1024;

        public SubmissionType() {
        }

        public SubmissionType(List<String> allowedExtensions, int maxFiles, long maxFileBytes) {
            this.allowedExtensions = new ArrayList<>(allowedExtensions);
            this.maxFiles = maxFiles;
            this.maxFileBytes = maxFileBytes;
        }

        public static SubmissionType source() {
            return new SubmissionType(List.of("zip", "jar"), 9, 100L * 1024 * 1024);
        }

        public static SubmissionType report() {
            return new SubmissionType(List.of("pdf", "docx"), 9, 50L * 1024 * 1024);
        }
    }

    /**
     * 相似度检测。
     *
     * <p>1.0 = 内容完全相同（同一 sha256）；0.75 = 源码包结构一致但内容不同
     * （条目名集合相同、逐个文件被改写）。低于阈值的差异不记录，避免噪声。</p>
     */
    @Data
    public static class Similarity {
        private boolean enabled = true;
        private double threshold = 0.75;
        /** 同一学生不同提交之间是否比对——同一个人反复改稿是正常行为，默认不比。 */
        private boolean compareWithinSameStudent = false;

        /** 内容指纹（归一化 + MinHash）的命中门槛。比结构阈值高，避免误报。 */
        private double contentThreshold = 0.85;

        /** 是否计算内容指纹。关掉后只剩「完全相同 / 结构相同」两级。 */
        private boolean fingerprintEnabled = true;

        /**
         * 超过这个大小的文件不算指纹。
         *
         * <p>计算要解压并归一化全文，100MB 的包约 1~3 秒。超过上限的文件仍参与
         * sha256 与结构比对，只是拿不到内容级分数——这是有意的降级，不是静默丢弃。</p>
         */
        private long maxFingerprintBytes = 50L * 1024 * 1024;

        /** 教师端「高亮」门槛：低于它的命中记录只做提示，不标红。 */
        private double highlightThreshold = 0.85;
    }

    /**
     * 答辩现场能力：只读源码查看 + 违规终止。
     *
     * <p>两块的限额全部在这里定义一次，Controller / Service 不写魔法数字：
     * 改一个上限不必翻三个文件，也不会出现「前端按 2MB 截断、后端按 1MB 拒绝」的漂移。</p>
     */
    @Data
    public static class Defense {
        private Source source = new Source();
        private Violation violation = new Violation();
        private QuestionRule questionRule = new QuestionRule();
        private Principle principle = new Principle();
        private Batch batch = new Batch();
    }

    /**
     * 题数规则：基础题 + 按源码评分分档加题，上限封顶。
     *
     * <p>替代原来的「代码评分 / 10 + 2」：线性规则下 90 分要答 11 题（约 33 分钟），
     * 而 60 分只有 8 题且区分度不足；分档后同分同量、可解释，也能通过配置调整。</p>
     *
     * <p>全部字段都可由 {@code sss.defense.question-rule.*} 覆盖，
     * 前端通过 {@code GET /teacher/defense/question-rule} 读同一份配置，禁止两边各写一套。</p>
     */
    @Data
    public static class QuestionRule {
        /** 基础题数：三原则各 2 题。 */
        private int baseCount = 6;
        /** 总题数上限（不含加分题）。 */
        private int maxCount = 10;
        /** 固定生成的加分题数量，默认 1。 */
        private int bonusCount = 1;
        /** 加分题作答时限（秒）。 */
        private int bonusMaxAnswerSeconds = 120;
        /** 加分题是否允许跳过。 */
        private boolean bonusSkippable = true;
        /** 基础难度配比 [基础, 进阶, 挑战]，百分数。 */
        private List<Integer> baseMix = new ArrayList<>(List.of(40, 40, 20));
        /** 高分段难度配比：挑战题占比提高，用于区分「真会」和「背稿」。 */
        private List<Integer> highTierMix = new ArrayList<>(List.of(30, 40, 30));
        /** 加题数达到该值即视为高分段，改用 {@link #highTierMix}。 */
        private int highTierThreshold = 3;
        /** 分值档位；按 {@code min <= score <= max} 匹配，未命中任何档位按 0 加题。 */
        private List<Tier> tiers = new ArrayList<>(List.of(
                new Tier("T0", 0, 59, 0),
                new Tier("T1", 60, 74, 1),
                new Tier("T2", 75, 84, 2),
                new Tier("T3", 85, 94, 3),
                new Tier("T4", 95, 100, 4)));
    }

    /** 一个分值档位：{@code [min, max]} 分对应加 {@code add} 题。 */
    @Data
    public static class Tier {
        private String code = "T0";
        private int min;
        private int max;
        private int add;

        public Tier() {
        }

        public Tier(String code, int min, int max, int add) {
            this.code = code;
            this.min = min;
            this.max = max;
            this.add = add;
        }
    }

    /**
     * 三原则判定参数。
     *
     * <p>阈值统一 60：同分同判、可解释；判定「完全依赖 AI」需要两项及以上不通过，
     * 只挂一项只记录不判定——避免学生某题紧张答砸就被贴标签。</p>
     */
    @Data
    public static class Principle {
        private int threshold = 60;
        private List<String> dimensions = new ArrayList<>(
                List.of("P1_RETELL", "P2_REASON", "P3_MODIFY"));
        /** 几项不通过判定为 AI_DEPENDENT。 */
        private int failCountForAiDependent = 2;
        /** 覆盖判定的理由最短长度。 */
        private int overrideReasonMinLength = 5;
    }

    /** 批量发起答辩参数。 */
    @Data
    public static class Batch {
        /** 单次最多选多少人。 */
        private int maxStudents = 50;
        /** 并发度（1~3）：太高会把本地 AI 服务打满。 */
        private int concurrency = 2;
        /** 已发布成绩的选题是否允许再次发起答辩。 */
        private boolean allowWhenGradePublished = false;
    }

    /**
     * 源码查看（只读、双权限）参数。
     *
     * <p>这里只限制「解压出来的文本」，与上传时的 100MB 包大小上限是两件事：
     * 上传上限管磁盘，这里管一次请求能往浏览器推多少内容。</p>
     */
    @Data
    public static class Source {
        /** 单文件展示上限；超过只返回前 N 字节并标记 truncated，不静默假装完整。 */
        private long maxFileBytes = 5L * 1024 * 1024;
        /** 单次最多返回的文本文件数（超出直接拒绝，避免一屏塞几千个文件）。 */
        private int maxFiles = 500;
        /** 解压后总字节上限（防压缩炸弹）。 */
        private long maxTotalBytes = 200L * 1024 * 1024;
        /** 单次解压总超时（秒）；到点即中断，宁可少给也不拖死请求线程。 */
        private int timeoutSeconds = 60;
        /** 同一学生每分钟最多触发几次「冷解压」（命中缓存不计）。 */
        private int coldRequestsPerMinute = 2;
        /** 是否把首次访问的 IP 绑定到会话；换 IP 再访问会 403，用于降低共享会话风险。 */
        private boolean bindIp = true;
        /** 解压结果内存缓存时长。 */
        private Duration cacheTtl = Duration.ofMinutes(5);
        /** 单份缓存允许占用的最大字节；超过就不缓存（这种包很少见，重解压可接受）。 */
        private long cacheMaxEntryBytes = 16L * 1024 * 1024;
        /** 缓存最多保留多少个会话版本。 */
        private int cacheMaxEntries = 8;
    }

    /**
     * 违规判定参数。
     *
     * <p>只有 {@code countedTypes} 里的类型计入阈值，其余类型（复制拦截、快捷键拦截）
     * 会被记录成 MINOR 证据但不推进终止——「拦截了」不等于「违规了」，
     * 否则学生按一次 Ctrl+C 就被记一次违规，误杀率太高。</p>
     */
    @Data
    public static class Violation {
        /** 累计几次 MAJOR 违规终止答辩。 */
        private int threshold = 2;
        /** 计入阈值的类型；教师「恢复答辩」后重新从 0 计。 */
        private List<String> countedTypes = new ArrayList<>(
                List.of("TAB_SWITCH", "DEVTOOLS", "PASTE_PATTERN"));
    }

    /** 题目附件：允许的扩展名 / MIME / 单文件与单次总量 / 单题附件数。 */
    @Data
    public static class TopicAttachment {
        private List<String> allowedExtensions = new ArrayList<>(List.of("pdf", "doc", "docx"));
        private List<String> allowedContentTypes = new ArrayList<>(List.of(
                "application/pdf",
                "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
        private long maxFileBytes = 20L * 1024 * 1024;
        private int maxFilesPerRequest = 5;
        private long maxTotalBytes = 50L * 1024 * 1024;
        private int maxPerTopic = 20;
    }
}
