package com.example.book;

/**
 * 演示入口：演示借书、还书与可借列表。
 *
 * <p>仅用于本地演示，真实项目中由 Controller 调用 BookService。</p>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        BookRepository repository = new BookRepository();
        BookService service = new BookService(repository);

        repository.save(new Book("978-7-111-11111-1", "Java 编程思想", "Bruce Eckel"));
        repository.save(new Book("978-7-222-22222-2", "重构", "Martin Fowler"));

        System.out.println("馆藏数量：" + service.totalBooks());
        service.borrow("978-7-111-11111-1");
        System.out.println("借阅后剩余可借：" + service.availableBooks().size());
        service.giveBack("978-7-111-11111-1");
        System.out.println("归还后剩余可借：" + service.availableBooks().size());
    }
}
