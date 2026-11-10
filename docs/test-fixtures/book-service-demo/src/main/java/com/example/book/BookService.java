package com.example.book;

import java.util.List;
import java.util.Objects;

/**
 * 图书借阅服务。
 *
 * <p>业务规则集中在这一层：检查图书是否存在、是否已借出、借阅成功后更新状态。
 * 构造函数注入仓储，方便单元测试替换成内存实现或 Mock。</p>
 */
public class BookService {

    private final BookRepository repository;

    /**
     * @param repository 图书仓储，不能为空
     */
    public BookService(BookRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
    }

    /**
     * 借阅图书。
     *
     * @param isbn ISBN
     * @return 借阅成功的图书
     * @throws BookNotFoundException 图书不存在时抛出
     */
    public Book borrow(String isbn) {
        Book book = repository.findByIsbn(isbn)
                .orElseThrow(() -> new BookNotFoundException("图书不存在：" + isbn));
        book.borrow();
        repository.save(book);
        return book;
    }

    /**
     * 归还图书。
     *
     * @param isbn ISBN
     * @return 归还成功的图书
     * @throws BookNotFoundException 图书不存在时抛出
     */
    public Book giveBack(String isbn) {
        Book book = repository.findByIsbn(isbn)
                .orElseThrow(() -> new BookNotFoundException("图书不存在：" + isbn));
        book.giveBack();
        repository.save(book);
        return book;
    }

    /**
     * 查询当前可借图书。
     */
    public List<Book> availableBooks() {
        return repository.findAll().stream()
                .filter(book -> !book.isBorrowed())
                .toList();
    }

    /**
     * 馆藏总数。
     */
    public int totalBooks() {
        return repository.count();
    }

    /**
     * 业务异常：图书不存在。
     */
    public static class BookNotFoundException extends RuntimeException {
        public BookNotFoundException(String message) {
            super(message);
        }
    }
}
