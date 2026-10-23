package dev.jbaby.ditto.opensearch.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.OpenSearchTransport;

import dev.jbaby.ditto.opensearch.OpenSearchAccessException;

class OpenSearchHttpTest {

    private final OpenSearchTransport transport = mock(OpenSearchTransport.class);
    private final OpenSearchHttp http = new OpenSearchHttp(new OpenSearchClient(transport));

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void aTimeoutLeavesTheThreadAlone() throws IOException {
        fail(new SocketTimeoutException("Read timed out"));

        assertThatThrownBy(this::search).isInstanceOf(OpenSearchAccessException.class)
                .hasMessage("POST /products/_search failed: Read timed out");
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void anInterruptMarksTheThreadInterruptedAgain() throws IOException {
        // how the HttpClient 5 transport reports an interrupt, with the flag already cleared
        fail(new RuntimeException("thread waiting for the response was interrupted", new InterruptedException()));

        assertThatThrownBy(this::search).isInstanceOf(OpenSearchAccessException.class)
                .hasMessage("POST /products/_search was interrupted");
        assertThat(Thread.interrupted()).isTrue();

        // how the AWS transport reports it
        fail(new IOException("HttpRequest was interrupted", new InterruptedException()));
        assertThatThrownBy(this::search).hasMessageContaining("was interrupted");
        assertThat(Thread.interrupted()).isTrue();

        fail(new InterruptedIOException());
        assertThatThrownBy(this::search).hasMessageContaining("was interrupted");
        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    void otherFailuresPassThrough() throws IOException {
        var unexpected = new IllegalStateException("bug");
        fail(unexpected);

        assertThatThrownBy(this::search).isSameAs(unexpected);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    private void fail(Exception e) throws IOException {
        doThrow(e).when(transport).performRequest(any(), any(), any());
    }

    private void search() {
        http.execute("POST", "/products/_search", Map.of(), (byte[]) null, "application/json");
    }
}
