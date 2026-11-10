package com.example.book;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 图书仓储（内存实现）。
 *
 * <p>课程设计里通常直接写 JDBC；这里用 Map 实现同样的仓储接口，
 * 目的是让 Service 层只依赖抽象，后续替换成 MySQL 实现时不用改业务代码。</p>
 */
public class BookRepository {

    private final Map<String, Book> storage = new LinkedHashMap<>();

    /**
     * 保存图书；同一 ISBN 视为同一本书。
     *
     * @return 保存后的图书
     */
    public Book save(Book book) {
        if (book == null) {
            throw new IllegalArgumentException("book 不能为空");
        }
        storage.put(book.getIsbn(), book);
        return book;
    }

    /**
     * 按 ISBN 查询。
     */
    public Optional<Book> findByIsbn(String isbn) {
        if (isbn == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(storage.get(isbn));
    }

    /**
     * 查询全部图书，返回不可变副本，避免调用方直接修改内部集合。
     */
    public List<Book> findAll() {
        return new ArrayList<>(storage.values());
    }

    /**
     * 统计馆藏数量。
     */
    public int count() {
        return storage.size();
    }
}
