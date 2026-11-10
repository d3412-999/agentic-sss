package com.example.book;

import java.util.Objects;

/**
 * 图书实体。
 *
 * <p>只承载数据与状态判断，不依赖任何仓储或业务服务，保证领域对象可以独立测试。</p>
 */
public class Book {

    private final String isbn;
    private final String title;
    private final String author;
    private boolean borrowed;

    /**
     * 创建一本图书。
     *
     * @param isbn   国际标准书号，不能为空
     * @param title  书名，不能为空
     * @param author 作者，不能为空
     */
    public Book(String isbn, String title, String author) {
        this.isbn = requireText(isbn, "isbn");
        this.title = requireText(title, "title");
        this.author = requireText(author, "author");
    }

    /**
     * 借出图书；已经借出时抛出业务异常。
     */
    public void borrow() {
        if (borrowed) {
            throw new IllegalStateException("图书已借出：" + title);
        }
        borrowed = true;
    }

    /**
     * 归还图书；未借出时抛出业务异常。
     */
    public void giveBack() {
        if (!borrowed) {
            throw new IllegalStateException("图书未借出：" + title);
        }
        borrowed = false;
    }

    public String getIsbn() {
        return isbn;
    }

    public String getTitle() {
        return title;
    }

    public String getAuthor() {
        return author;
    }

    public boolean isBorrowed() {
        return borrowed;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.trim();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Book book)) {
            return false;
        }
        return isbn.equals(book.isbn);
    }

    @Override
    public int hashCode() {
        return Objects.hash(isbn);
    }

    @Override
    public String toString() {
        return "Book{" + isbn + ", " + title + ", " + author + ", borrowed=" + borrowed + '}';
    }
}
