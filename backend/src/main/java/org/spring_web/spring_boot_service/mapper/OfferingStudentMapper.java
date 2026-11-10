package org.spring_web.spring_boot_service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.spring_web.spring_boot_service.entity.OfferingStudent;

/** 开课学生名单 Mapper。 */
@Mapper
public interface OfferingStudentMapper extends BaseMapper<OfferingStudent> {

    /**
     * 对「学生 + 开课」这一行加行锁，用于串行化同一学生的并发选题请求。
     *
     * <p>选题限额是「已有条数 + 本次条数 ≤ 上限」的读后写判断，两个并发请求可能同时
     * 读到相同的已有条数而双双通过。加锁读会让第二个事务在第一个提交后才继续，
     * 从而使限额判断看到最新数据。返回 null 表示该学生不在名单里。</p>
     */
    @Select("SELECT id FROM offering_student "
            + "WHERE offering_id = #{offeringId} AND student_id = #{studentId} AND deleted = 0 "
            + "ORDER BY id LIMIT 1 FOR UPDATE")
    Long lockStudentRelation(@Param("offeringId") long offeringId, @Param("studentId") long studentId);
}
