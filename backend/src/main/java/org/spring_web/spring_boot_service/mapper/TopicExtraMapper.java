package org.spring_web.spring_boot_service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.spring_web.spring_boot_service.entity.Topic;

/**
 * 题目已选人数的原子增减，手写 SQL，绕开 MyBatis-Plus 的 update(null, wrapper) 与乐观锁插件。
 *
 * <p><b>为什么不用「先 SELECT 再 UPDATE」</b>：两个并发事务会同时读到同一个计数，
 * 各自 +1 造成丢更新。下面两条语句把「判断 + 增减」压进同一条 UPDATE 里，
 * 由 MySQL 行锁串行化执行，返回影响行数 1 = 成功、0 = 条件不满足。</p>
 *
 * <p><b>V9 起没有容量上限</b>：预占不再比较 max_select_count（该列已删除），
 * 只校验题目未被删除；释放仍要求 selected_count &gt; 0，避免减成负数。
 * 字段名沿用 selected_count，语义从「容量预占」变成「已选人数」。</p>
 *
 * <p>version 手动 +1 是为了让并发的「教师编辑题目」（updateById 带 @Version）能感知到
 * 计数变化，避免后写覆盖前写；updated_at 取数据库时间，避免应用服务器时钟不一致。</p>
 *
 * <p>这两条是自定义 @Update，参数里没有实体对象，MyBatis-Plus 的
 * OptimisticLockerInnerInterceptor 不会改写它们，行为完全可预期。</p>
 */
@Mapper
public interface TopicExtraMapper extends BaseMapper<Topic> {

    /**
     * 原子预占 1 个名额（V9 起没有容量上限，只是计数 +1）。
     *
     * <p>返回 0 表示题目已被删除。虽然不再卡上限，但「判断 + 递增」仍在同一条 SQL 内完成，
     * 保证并发选题时计数不会丢更新。</p>
     */
    @Update("""
            UPDATE topic
               SET selected_count = selected_count + 1,
                   version = version + 1,
                   updated_at = NOW(3)
             WHERE id = #{topicId}
               AND deleted = 0
            """)
    int reserveOne(@Param("topicId") long topicId);

    /** 原子释放 1 个名额；返回 0 表示计数已是 0（数据不一致），此时不阻塞退选。 */
    @Update("""
            UPDATE topic
               SET selected_count = selected_count - 1,
                   version = version + 1,
                   updated_at = NOW(3)
             WHERE id = #{topicId}
               AND deleted = 0
               AND selected_count > 0
            """)
    int releaseOne(@Param("topicId") long topicId);
}
