package com.sparrowwallet.tern.http.client;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;

public class HttpClientServiceTest {
    @Test
    public void test() throws HttpException {
        HttpClientService httpClientService = new HttpClientService();
        IHttpClient httpClient = httpClientService.getHttpClient(HttpUsage.DEFAULT);
        Todo todo = httpClient.getJson("https://jsonplaceholder.typicode.com/todos/1", Todo.class, Map.of(), false);
        Assertions.assertEquals(1, todo.userId);
        Assertions.assertEquals("delectus aut autem", todo.title);
    }

    private record Todo(long userId, long id, String title, boolean completed) {}
}
